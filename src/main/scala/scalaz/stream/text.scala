package scalaz.stream

import java.nio.charset.Charset
import java.util.regex.Pattern
import scodec.bits.ByteVector

import process1._

/**
 * Module for text related processes.
 */
object text {
  private val utf8Charset = Charset.forName("UTF-8")

  /** Converts UTF-8 encoded `ByteVector` inputs to `String`. */
  val utf8Decode: Process1[ByteVector,String] = {
    /**
     * Returns the number of continuation bytes if `b` is an ASCII byte or a
     * leading byte of a multi-byte sequence, and -1 otherwise.
     */
    def continuationBytes(b: Byte): Int = {
      if      ((b & 0x80) == 0x00) 0 // ASCII byte
      else if ((b & 0xE0) == 0xC0) 1 // leading byte of a 2 byte seq
      else if ((b & 0xF0) == 0xE0) 2 // leading byte of a 3 byte seq
      else if ((b & 0xF8) == 0xF0) 3 // leading byte of a 4 byte seq
      else                        -1 // continuation byte or garbage
    }

    /**
     * Returns the length of an incomplete multi-byte sequence at the end of
     * `bs`. If `bs` ends with an ASCII byte or a complete multi-byte sequence,
     * 0 is returned.
     */
    def lastIncompleteBytes(bs: ByteVector): Int = {
      val lastThree = bs.toIndexedSeq.reverseIterator.take(3)
      lastThree.map(continuationBytes).zipWithIndex.find {
        case (c, _) => c >= 0
      } map {
        case (c, i) => if (c == i) 0 else i + 1
      } getOrElse 0
    }

    def splitAtLastIncompleteChar(bs: ByteVector): (Option[ByteVector], Option[ByteVector]) = {
      val splitIndex = bs.length - lastIncompleteBytes(bs)

      if (bs.isEmpty || splitIndex == bs.length)
        (Some(bs), None)
      else if (splitIndex == 0)
        (None, Some(bs))
      else {
        val (complete, rest) = bs.splitAt(splitIndex)
        (Some(complete), Some(rest))
      }
    }

    repartition2(splitAtLastIncompleteChar)
      .map(bs => new String(bs.toArray, utf8Charset))
  }

  /** Converts `String` inputs to UTF-8 encoded `ByteVector`. */
  val utf8Encode: Process1[String,ByteVector] =
    lift(s => ByteVector.view(s.getBytes(utf8Charset)))

  /**
   * Repartition `String` input by line endings. If `maxLineLength` is greater
   * than zero and a line exceeding `maxLineLength` is found, the `Process`
   * fails with an `Exception`. The default `maxLineLength` is 1 MB for
   * `Charset`s with an average char size of 1 byte such as UTF-8.
   */
  def lines(maxLineLength: Int = 1024*1024): Process1[String, String] = {
    import scalaz.std.string._

    val pattern = Pattern.compile("\r\n|\n")
    repartition[String]{ s =>
      val chunks = pattern.split(s, -1)
      if (maxLineLength > 0) chunks.find(_.length > maxLineLength) match  {
        case Some(_) => throw new Exception(s"Input exceeded maximum line length: $maxLineLength")
        case None    => chunks
      } else chunks
    }.dropLastIf(_.isEmpty)
  }
}
