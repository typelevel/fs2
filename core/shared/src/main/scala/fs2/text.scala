package fs2

import java.nio.{Buffer, CharBuffer}
import java.nio.charset.Charset

import scala.annotation.tailrec

import scodec.bits.{Bases, ByteVector}

/** Provides utilities for working with streams of text (e.g., encoding byte streams to strings). */
object text {
  private val utf8Charset = Charset.forName("UTF-8")
  private val utf8Bom: Chunk[Byte] = Chunk(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

  /** Converts UTF-8 encoded byte stream to a stream of `String`. */
  def utf8Decode[F[_]]: Pipe[F, Byte, String] =
    _.chunks.through(utf8DecodeC)

  /** Converts UTF-8 encoded `Chunk[Byte]` inputs to `String`. */
  def utf8DecodeC[F[_]]: Pipe[F, Chunk[Byte], String] = {
    /*
     * Returns the number of continuation bytes if `b` is an ASCII byte or a
     * leading byte of a multi-byte sequence, and -1 otherwise.
     */
    def continuationBytes(b: Byte): Int =
      if ((b & 0x80) == 0x00) 0 // ASCII byte
      else if ((b & 0xE0) == 0xC0) 1 // leading byte of a 2 byte seq
      else if ((b & 0xF0) == 0xE0) 2 // leading byte of a 3 byte seq
      else if ((b & 0xF8) == 0xF0) 3 // leading byte of a 4 byte seq
      else -1 // continuation byte or garbage

    /*
     * Returns the length of an incomplete multi-byte sequence at the end of
     * `bs`. If `bs` ends with an ASCII byte or a complete multi-byte sequence,
     * 0 is returned.
     */
    def lastIncompleteBytes(bs: Array[Byte]): Int = {
      val lastThree = bs.drop(0.max(bs.size - 3)).toArray.reverseIterator
      lastThree
        .map(continuationBytes)
        .zipWithIndex
        .find {
          case (c, _) => c >= 0
        }
        .map {
          case (c, i) => if (c == i) 0 else i + 1
        }
        .getOrElse(0)
    }

    def processSingleChunk(
        outputAndBuffer: (List[String], Chunk[Byte]),
        nextBytes: Chunk[Byte]
    ): (List[String], Chunk[Byte]) = {
      val (output, buffer) = outputAndBuffer
      val allBytes = Array.concat(buffer.toArray, nextBytes.toArray)
      val splitAt = allBytes.size - lastIncompleteBytes(allBytes)

      if (splitAt == allBytes.size)
        (new String(allBytes.toArray, utf8Charset) :: output, Chunk.empty)
      else if (splitAt == 0)
        (output, Chunk.bytes(allBytes))
      else
        (
          new String(allBytes.take(splitAt).toArray, utf8Charset) :: output,
          Chunk.bytes(allBytes.drop(splitAt))
        )
    }

    def doPull(buf: Chunk[Byte], s: Stream[F, Chunk[Byte]]): Pull[F, String, Unit] =
      s.pull.uncons.flatMap {
        case Some((byteChunks, tail)) =>
          val (output, nextBuffer) =
            byteChunks.toList.foldLeft((Nil: List[String], buf))(processSingleChunk)
          Pull.output(Chunk.seq(output.reverse)) >> doPull(nextBuffer, tail)
        case None if !buf.isEmpty =>
          Pull.output1(new String(buf.toArray, utf8Charset))
        case None =>
          Pull.done
      }

    def processByteOrderMark(
        buffer: Option[Chunk.Queue[Byte]],
        s: Stream[F, Chunk[Byte]]
    ): Pull[F, String, Unit] =
      s.pull.uncons1.flatMap {
        case Some((hd, tl)) =>
          val newBuffer = buffer.getOrElse(Chunk.Queue.empty[Byte]) :+ hd
          if (newBuffer.size >= 3) {
            val rem =
              if (newBuffer.take(3).toChunk == utf8Bom) newBuffer.drop(3)
              else newBuffer
            doPull(Chunk.empty, Stream.emits(rem.chunks) ++ tl)
          } else {
            processByteOrderMark(Some(newBuffer), tl)
          }
        case None =>
          buffer match {
            case Some(b) =>
              doPull(Chunk.empty, Stream.emits(b.chunks))
            case None =>
              Pull.done
          }
      }

    (in: Stream[F, Chunk[Byte]]) => processByteOrderMark(None, in).stream
  }

  /** Encodes a stream of `String` in to a stream of bytes using the given charset. */
  def encode[F[_]](charset: Charset): Pipe[F, String, Byte] =
    _.flatMap(s => Stream.chunk(Chunk.bytes(s.getBytes(charset))))

  /** Encodes a stream of `String` in to a stream of `Chunk[Byte]` using the given charset. */
  def encodeC[F[_]](charset: Charset): Pipe[F, String, Chunk[Byte]] =
    _.map(s => Chunk.bytes(s.getBytes(charset)))

  /** Encodes a stream of `String` in to a stream of bytes using the UTF-8 charset. */
  def utf8Encode[F[_]]: Pipe[F, String, Byte] =
    encode(utf8Charset)

  /** Encodes a stream of `String` in to a stream of `Chunk[Byte]` using the UTF-8 charset. */
  def utf8EncodeC[F[_]]: Pipe[F, String, Chunk[Byte]] =
    encodeC(utf8Charset)

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
          case _ =>
            ()
        }
        i += 1
      }
      val carry = string.substring(start, string.size)
      (out, carry)
    }

    def extractLines(
        buffer: Vector[String],
        chunk: Chunk[String],
        pendingLineFeed: Boolean
    ): (Chunk[String], Vector[String], Boolean) = {
      @tailrec
      def go(
          remainingInput: Vector[String],
          buffer: Vector[String],
          output: Vector[String],
          pendingLineFeed: Boolean
      ): (Chunk[String], Vector[String], Boolean) =
        if (remainingInput.isEmpty) {
          (Chunk.indexedSeq(output), buffer, pendingLineFeed)
        } else {
          val next = remainingInput.head
          if (pendingLineFeed) {
            if (next.headOption == Some('\n')) {
              val out = (buffer.init :+ buffer.last.init).mkString
              go(next.tail +: remainingInput.tail, Vector.empty, output :+ out, false)
            } else {
              go(remainingInput, buffer, output, false)
            }
          } else {
            val (out, carry) = linesFromString(next)
            val pendingLF =
              if (carry.nonEmpty) carry.last == '\r' else pendingLineFeed
            go(
              remainingInput.tail,
              if (out.isEmpty) buffer :+ carry else Vector(carry),
              if (out.isEmpty) output
              else output ++ ((buffer :+ out.head).mkString +: out.tail),
              pendingLF
            )
          }
        }
      go(chunk.toVector, buffer, Vector.empty, pendingLineFeed)
    }

    def go(
        buffer: Vector[String],
        pendingLineFeed: Boolean,
        s: Stream[F, String]
    ): Pull[F, String, Unit] =
      s.pull.uncons.flatMap {
        case Some((chunk, s)) =>
          val (toOutput, newBuffer, newPendingLineFeed) =
            extractLines(buffer, chunk, pendingLineFeed)
          Pull.output(toOutput) >> go(newBuffer, newPendingLineFeed, s)
        case None if buffer.nonEmpty =>
          Pull.output1(buffer.mkString)
        case None => Pull.done
      }

    s => go(Vector.empty, false, s).stream
  }

  /**
    * Converts a stream of base 64 text in to a stream of bytes.
    *
    * If the text is not valid base 64, the pipe fails with an exception. Padding
    * characters at the end of the input stream are optional, but if present, must
    * be valid per the base 64 specification. Whitespace characters are ignored.
    *
    * The default base 64 alphabet is used by this pipe.
    */
  def base64Decode[F[_]: RaiseThrowable]: Pipe[F, String, Byte] =
    base64Decode(Bases.Alphabets.Base64)

  /**
    * Like [[base64Decode]] but takes a base 64 alphabet. For example,
    * `base64Decode(Bases.Alphabets.Base64Url)` will decode URL compatible base 64.
    */
  def base64Decode[F[_]: RaiseThrowable](alphabet: Bases.Base64Alphabet): Pipe[F, String, Byte] = {
    // Adapted from scodec-bits, licensed under 3-clause BSD
    final case class State(buffer: Int, mod: Int, padding: Int)
    val Pad = alphabet.pad
    def paddingError =
      Left(
        "Malformed padding - final quantum may optionally be padded with one or two padding characters such that the quantum is completed"
      )

    def decode(state: State, str: String): Either[String, (State, Chunk[Byte])] = {
      var buffer = state.buffer
      var mod = state.mod
      var padding = state.padding
      var idx, bidx = 0
      val acc = new Array[Byte]((str.size + 3) / 4 * 3)
      while (idx < str.length) {
        str(idx) match {
          case c if alphabet.ignore(c) => // ignore
          case c =>
            val cidx = {
              if (padding == 0) {
                if (c == Pad) {
                  if (mod == 2 || mod == 3) {
                    padding += 1
                    0
                  } else {
                    return paddingError
                  }
                } else {
                  try alphabet.toIndex(c)
                  catch {
                    case _: IllegalArgumentException =>
                      return Left(s"Invalid base 64 character '$c' at index $idx")
                  }
                }
              } else {
                if (c == Pad) {
                  if (padding == 1 && mod == 3) {
                    padding += 1
                    0
                  } else {
                    return paddingError
                  }
                } else {
                  return Left(
                    s"Unexpected character '$c' at index $idx after padding character; only '=' and whitespace characters allowed after first padding character"
                  )
                }
              }
            }
            mod match {
              case 0 =>
                buffer = (cidx & 0x3f)
                mod += 1
              case 1 | 2 =>
                buffer = (buffer << 6) | (cidx & 0x3f)
                mod += 1
              case 3 =>
                buffer = (buffer << 6) | (cidx & 0x3f)
                mod = 0
                acc(bidx) = (buffer >> 16).toByte
                acc(bidx + 1) = (buffer >> 8).toByte
                acc(bidx + 2) = buffer.toByte
                bidx += 3
            }
        }
        idx += 1
      }
      val paddingInBuffer = if (mod == 0) padding else 0
      val out = Chunk.byteVector(ByteVector.view(acc).take((bidx - paddingInBuffer).toLong))
      val carry = State(buffer, mod, padding)
      Right((carry, out))
    }

    def finish(state: State): Either[String, Chunk[Byte]] =
      if (state.padding != 0 && state.mod != 0) paddingError
      else
        state.mod match {
          case 0 => Right(Chunk.empty)
          case 1 => Left("Final base 64 quantum had only 1 digit - must have at least 2 digits")
          case 2 =>
            Right(Chunk((state.buffer >> 4).toByte))
          case 3 =>
            val buffer = state.buffer
            Right(
              Chunk(
                (buffer >> 10).toByte,
                (buffer >> 2).toByte
              )
            )
        }

    def go(state: State, s: Stream[F, String]): Pull[F, Byte, Unit] =
      s.pull.uncons1.flatMap {
        case Some((hd, tl)) =>
          decode(state, hd) match {
            case Right((newState, out)) =>
              Pull.output(out) >> go(newState, tl)
            case Left(err) => Pull.raiseError(new IllegalArgumentException(err))
          }
        case None =>
          finish(state) match {
            case Right(out) => Pull.output(out)
            case Left(err)  => Pull.raiseError(new IllegalArgumentException(err))
          }
      }

    in => go(State(0, 0, 0), in).stream
  }

  /**
    * Encodes a byte stream in to a stream of base 64 text.
    * The default base 64 alphabet is used by this pipe.
    */
  def base64Encode[F[_]]: Pipe[F, Byte, String] = base64Encode(Bases.Alphabets.Base64)

  /**
    * Like [[base64Encode]] but takes a base 64 alphabet. For example,
    * `base64Encode(Bases.Alphabets.Base64Url)` will encode URL compatible base 64.
    */
  def base64Encode[F[_]](alphabet: Bases.Base64Alphabet): Pipe[F, Byte, String] = {
    // Adapted from scodec-bits, licensed under 3-clause BSD
    def encode(c: ByteVector): (String, ByteVector) = {
      val bytes = c.toArray
      val bldr = CharBuffer.allocate(((bytes.length + 2) / 3) * 4)
      var idx = 0
      val mod = bytes.length % 3
      while (idx < bytes.length - mod) {
        var buffer = ((bytes(idx) & 0xff) << 16) | ((bytes(idx + 1) & 0xff) << 8) | (bytes(
          idx + 2
        ) & 0xff)
        val fourth = buffer & 0x3f
        buffer = buffer >> 6
        val third = buffer & 0x3f
        buffer = buffer >> 6
        val second = buffer & 0x3f
        buffer = buffer >> 6
        val first = buffer
        bldr
          .append(alphabet.toChar(first))
          .append(alphabet.toChar(second))
          .append(alphabet.toChar(third))
          .append(alphabet.toChar(fourth))
        idx = idx + 3
      }
      (bldr: Buffer).flip
      val out = bldr.toString
      if (mod == 0) {
        (out, ByteVector.empty)
      } else if (mod == 1) {
        (out, ByteVector(bytes(idx)))
      } else {
        (out, ByteVector(bytes(idx), bytes(idx + 1)))
      }
    }

    def go(carry: ByteVector, s: Stream[F, Byte]): Pull[F, String, Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          val (out, newCarry) = encode(carry ++ hd.toByteVector)
          Pull.output1(out) >> go(newCarry, tl)
        case None =>
          carry.size match {
            case 0 => Pull.done
            case 1 =>
              var buffer = (carry(0) & 0xff) << 4
              val second = buffer & 0x3f
              buffer = buffer >> 6
              val first = buffer
              val out = new String(
                Array(alphabet.toChar(first), alphabet.toChar(second), alphabet.pad, alphabet.pad)
              )
              Pull.output1(out)
            case 2 =>
              var buffer = ((carry(0) & 0xff) << 10) | ((carry(1) & 0xff) << 2)
              val third = buffer & 0x3f
              buffer = buffer >> 6
              val second = buffer & 0x3f
              buffer = buffer >> 6
              val first = buffer
              val out = new String(
                Array(
                  alphabet.toChar(first),
                  alphabet.toChar(second),
                  alphabet.toChar(third),
                  alphabet.pad
                )
              )
              Pull.output1(out)
            case other => sys.error(s"carry must be size 0, 1, or 2 but was $other")
          }
      }

    in => go(ByteVector.empty, in).stream
  }
}
