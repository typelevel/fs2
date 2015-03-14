package scalaz.stream

import java.nio.charset.Charset
import java.util.regex.Pattern
import scalaz.std.string._
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
   * Exception that is thrown by `[[lines]](maxLength)` if it encounters an
   * input whose length exceeds `maxLength`. The `input` parameter is set to the
   * input `String` that caused `lines` to fail.
   */
  case class LengthExceeded(maxLength: Int, input: String) extends Exception {
    override def getMessage: String = {
      val n = 10
      val shortened = if (input.length <= n) input else input.take(n) + " ..."

      s"Input '$shortened' exceeded maximum length: $maxLength"
    }
  }

  /**
   * Repartitions `String` inputs by line endings. If `maxLineLength` is
   * greater than zero and a line exceeding `maxLineLength` is found, the
   * `Process` fails with an `LengthExceeded` exception. The default
   * `maxLineLength` is 1024<sup>2</sup>.
   *
   * @example {{{
   * scala> Process("Hel", "lo\nWo", "rld!\n").pipe(text.lines()).toList
   * res0: List[String] = List(Hello, World!)
   * }}}
   */
  def lines(maxLineLength: Int = 1024 * 1024): Process1[String, String] = {
    val pattern = Pattern.compile("\r\n|\n")
    def splitLines(s: String): IndexedSeq[String] = pattern.split(s, -1)

    val repartitionProcess =
      if (maxLineLength > 0)
        repartition { (s: String) =>
          val chunks = splitLines(s)
          if (chunks.forall(_.length <= maxLineLength)) chunks
          else throw LengthExceeded(maxLineLength, s)
        }
      else repartition(splitLines)
    repartitionProcess.dropLastIf(_.isEmpty)
  }
}
