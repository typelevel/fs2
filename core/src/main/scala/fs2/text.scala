package fs2

import java.nio.charset.Charset

import fs2.Stream.Handle

object text {
  private val utf8Charset = Charset.forName("UTF-8")

  /** Converts UTF-8 encoded `Chunk[Byte]` inputs to `String`. */
  def utf8Decode[F[_]]: Pipe[F, Chunk[Byte], String] = {
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
    def lastIncompleteBytes(bs: Chunk[Byte]): Int = {
      val lastThree = bs.drop(0 max bs.size - 3).toArray.reverseIterator
      lastThree.map(continuationBytes).zipWithIndex.find {
        case (c, _) => c >= 0
      } map {
        case (c, i) => if (c == i) 0 else i + 1
      } getOrElse 0
    }

    def processSingleChunk(outputAndBuffer: (List[String], Chunk[Byte]), nextBytes: Chunk[Byte]): (List[String], Chunk[Byte]) = {
      val (output, buffer) = outputAndBuffer
      val allBytes = Chunk.bytes(Array.concat(buffer.toArray, nextBytes.toArray))
      val splitAt = allBytes.size - lastIncompleteBytes(allBytes)

      if (splitAt == allBytes.size)
        (new String(allBytes.toArray, utf8Charset) :: output, Chunk.empty)
      else if (splitAt == 0)
        (output, allBytes)
      else
        (new String(allBytes.take(splitAt).toArray, utf8Charset) :: output, allBytes.drop(splitAt))
    }

    def doPull(buf: Chunk[Byte])(h: Handle[Pure, Chunk[Byte]]): Pull[Pure, String, Handle[Pure, Chunk[Byte]]] = {
      h.await.optional flatMap {
        case Some(byteChunks #: tail) =>
          val (output, nextBuffer) = byteChunks.foldLeft((List.empty[String], buf))(processSingleChunk)
          Pull.output(Chunk.seq(output.reverse)) >> doPull(nextBuffer)(tail)
        case None if !buf.isEmpty =>
          Pull.output1(new String(buf.toArray, utf8Charset)) >> Pull.done
        case None =>
          Pull.done
      }
    }

    pipe.covary[F, Chunk[Byte], String](_.open.flatMap(doPull(Chunk.empty) _).run)
  }

  /** Converts `String` to UTF-8 encoded `Chunk[Byte]`. */
  def utf8Encode[F[_]]: Pipe[F, String, Chunk[Byte]] =
    _.mapChunks(_.map(s => Chunk.bytes(s.getBytes(utf8Charset))))
}
