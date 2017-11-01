package fs2

import java.nio.charset.Charset

/** Provides utilities for working with streams of text (e.g., encoding byte streams to strings). */
object text {
  private val utf8Charset = Charset.forName("UTF-8")

  /** Converts UTF-8 encoded byte stream to a stream of `String`. */
  def utf8Decode[F[_]]: Pipe[F, Byte, String] =
    _.chunks.through(utf8DecodeC)

  /** Converts UTF-8 encoded `Chunk[Byte]` inputs to `String`. */
  def utf8DecodeC[F[_]]: Pipe[F, Chunk[Byte], String] = {
    /*
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

    /*
     * Returns the length of an incomplete multi-byte sequence at the end of
     * `bs`. If `bs` ends with an ASCII byte or a complete multi-byte sequence,
     * 0 is returned.
     */
    def lastIncompleteBytes(bs: Array[Byte]): Int = {
      val lastThree = bs.drop(0 max bs.size - 3).toArray.reverseIterator
      lastThree.map(continuationBytes).zipWithIndex.find {
        case (c, _) => c >= 0
      } map {
        case (c, i) => if (c == i) 0 else i + 1
      } getOrElse 0
    }

    def processSingleChunk(outputAndBuffer: (List[String], Chunk[Byte]), nextBytes: Chunk[Byte]): (List[String], Chunk[Byte]) = {
      val (output, buffer) = outputAndBuffer
      val allBytes = Array.concat(buffer.toArray, nextBytes.toArray)
      val splitAt = allBytes.size - lastIncompleteBytes(allBytes)

      if (splitAt == allBytes.size)
        (new String(allBytes.toArray, utf8Charset) :: output, Chunk.empty)
      else if (splitAt == 0)
        (output, Chunk.bytes(allBytes))
      else
        (new String(allBytes.take(splitAt).toArray, utf8Charset) :: output, Chunk.bytes(allBytes.drop(splitAt)))
    }

    def doPull(buf: Chunk[Byte], s: Stream[Pure, Chunk[Byte]]): Pull[Pure, String, Option[Stream[Pure, Chunk[Byte]]]] = {
      s.pull.unconsChunk.flatMap {
        case Some((byteChunks, tail)) =>
          val (output, nextBuffer) = byteChunks.toList.foldLeft((Nil: List[String], buf))(processSingleChunk)
          Pull.output(Chunk.seq(output.reverse)) *> doPull(nextBuffer, tail)
        case None if !buf.isEmpty =>
          Pull.output1(new String(buf.toArray, utf8Charset)) *> Pull.pure(None)
        case None =>
          Pull.pure(None)
      }
    }

    ((in: Stream[Pure,Chunk[Byte]]) => doPull(Chunk.empty, in).stream).covary[F]
  }

  /** Encodes a stream of `String` in to a stream of bytes using the UTF-8 charset. */
  def utf8Encode[F[_]]: Pipe[F, String, Byte] =
    _.flatMap(s => Stream.chunk(Chunk.bytes(s.getBytes(utf8Charset))))

  /** Encodes a stream of `String` in to a stream of `Chunk[Byte]` using the UTF-8 charset. */
  def utf8EncodeC[F[_]]: Pipe[F, String, Chunk[Byte]] =
    _.map(s => Chunk.bytes(s.getBytes(utf8Charset)))

  /** Transforms a stream of `String` such that each emitted `String` is a line from the input. */
  def lines[F[_]]: Pipe[F, String, String] = {

    def linesFromString(string: String): (Vector[String], String) = {
      var i = 0
      var start = 0
      var out = Vector.empty[String]
      while (i < string.size) {
        string(i) match {
          case '\n' =>
            out = out :+ string.substring(start, i)
            start = i + 1
          case '\r' =>
            if (i + 1 < string.size && string(i + 1) == '\n') {
              out = out :+ string.substring(start, i)
              start = i + 2
              i += 1
            }
          case c =>
            ()
        }
        i += 1
      }
      val carry = string.substring(start, string.size)
      (out, carry)
    }

    def extractLines(buffer: Vector[String], chunk: Chunk[String], pendingLineFeed: Boolean): (Chunk[String], Vector[String], Boolean) = {
      @annotation.tailrec
      def loop(remainingInput: Vector[String], buffer: Vector[String], output: Vector[String], pendingLineFeed: Boolean): (Chunk[String], Vector[String], Boolean) = {
        if (remainingInput.isEmpty) {
          (Chunk.indexedSeq(output), buffer, pendingLineFeed)
        } else {
          val next = remainingInput.head
          if (pendingLineFeed) {
            if (next.headOption == Some('\n')) {
              val out = (buffer.init :+ buffer.last.init).mkString
              loop(next.tail +: remainingInput.tail, Vector.empty, output :+ out, false)
            } else {
              loop(remainingInput, buffer, output, false)
            }
          } else {
            val (out, carry) = linesFromString(next)
            val pendingLF = if (carry.nonEmpty) carry.last == '\r' else pendingLineFeed
            loop(remainingInput.tail,
              if (out.isEmpty) buffer :+ carry else Vector(carry),
              if (out.isEmpty) output else output ++ ((buffer :+ out.head).mkString +: out.tail), pendingLF)
          }
        }
      }
      loop(chunk.toVector, buffer, Vector.empty, pendingLineFeed)
    }

    def go(buffer: Vector[String], pendingLineFeed: Boolean, s: Stream[F, String]): Pull[F, String, Option[Unit]] = {
      s.pull.unconsChunk.flatMap {
        case Some((chunk, s)) =>
          val (toOutput, newBuffer, newPendingLineFeed) = extractLines(buffer, chunk, pendingLineFeed)
          Pull.output(toOutput) *> go(newBuffer, newPendingLineFeed, s)
        case None if buffer.nonEmpty => Pull.output1(buffer.mkString) *> Pull.pure(None)
        case None => Pull.pure(None)
      }
    }

    s => go(Vector.empty, false, s).stream
  }
}
