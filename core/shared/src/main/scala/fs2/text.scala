/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.ApplicativeThrow
import java.nio.{Buffer, ByteBuffer, CharBuffer}
import java.nio.charset.{
  CharacterCodingException,
  Charset,
  CharsetDecoder,
  CharsetEncoder,
  CodingErrorAction,
  MalformedInputException,
  StandardCharsets,
  UnmappableCharacterException
}
import scala.collection.mutable.{ArrayBuffer, Builder}
import scodec.bits.{Bases, ByteVector}

import scala.annotation.tailrec

/** Provides utilities for working with streams of text (e.g., encoding byte streams to strings). */
object text {

  /** Byte order mark (BOM) values for different Unicode charsets.
    */
  object bom {

    /** BOM for UTF-8.
      */
    val utf8: ByteVector = ByteVector(0xef, 0xbb, 0xbf)

    /** BOM for UTF-16BE (big endian).
      */
    val utf16Big: ByteVector = ByteVector(0xfe, 0xff)

    /** BOM for UTF-16LE (little endian).
      */
    val utf16Little: ByteVector = ByteVector(0xff, 0xfe)
  }

  object utf8 {
    private val utf8Charset = Charset.forName("UTF-8")

    /** Converts UTF-8 encoded byte stream to a stream of `String`.
      *
      * Note that the output stream is ''not'' a singleton stream but rather a stream
      * of strings where each string is the result of UTF8 decoding a chunk of the
      * underlying byte stream.
      */
    def decode[F[_]]: Pipe[F, Byte, String] =
      _.chunks.through(decodeC)

    /** Converts UTF-8 encoded `Chunk[Byte]` inputs to `String`. */
    def decodeC[F[_]]: Pipe[F, Chunk[Byte], String] = {
      /*
       * Returns the number of continuation bytes if `b` is an ASCII byte or a
       * leading byte of a multi-byte sequence, and -1 otherwise.
       */
      def continuationBytes(b: Byte): Int =
        if ((b & 0x80) == 0x00) 0 // ASCII byte
        else if ((b & 0xe0) == 0xc0) 1 // leading byte of a 2 byte seq
        else if ((b & 0xf0) == 0xe0) 2 // leading byte of a 3 byte seq
        else if ((b & 0xf8) == 0xf0) 3 // leading byte of a 4 byte seq
        else -1 // continuation byte or garbage

      /*
       * Returns the length of an incomplete multi-byte sequence at the end of
       * `bs`. If `bs` ends with an ASCII byte or a complete multi-byte sequence,
       * 0 is returned.
       */
      def lastIncompleteBytes(bs: Array[Byte]): Int = {
        /*
         * This is logically the same as this
         * code, but written in a low level way
         * to avoid any allocations and just do array
         * access
         *
         *
         *
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

         */

        val minIdx = 0.max(bs.length - 3)
        var idx = bs.length - 1
        var counter = 0
        var res = 0
        while (minIdx <= idx) {
          val c = continuationBytes(bs(idx))
          if (c >= 0) {
            if (c != counter)
              res = counter + 1
            // exit the loop
            return res
          }
          idx = idx - 1
          counter = counter + 1
        }
        res
      }

      def processSingleChunk(
          bldr: Builder[String, List[String]],
          buffer: Chunk[Byte],
          nextBytes: Chunk[Byte]
      ): Chunk[Byte] = {
        // if processing ASCII or largely ASCII buffer is often empty
        val allBytes =
          if (buffer.isEmpty) nextBytes.toArray
          else Array.concat(buffer.toArray, nextBytes.toArray)

        val splitAt = allBytes.length - lastIncompleteBytes(allBytes)

        if (splitAt == allBytes.length) {
          // in the common case of ASCII chars
          // we are in this branch so the next buffer will
          // be empty
          bldr += new String(allBytes, utf8Charset)
          Chunk.empty
        } else if (splitAt == 0)
          Chunk.array(allBytes)
        else {
          bldr += new String(allBytes.take(splitAt), utf8Charset)
          Chunk.array(allBytes.drop(splitAt))
        }
      }

      def doPull(buf: Chunk[Byte], s: Stream[F, Chunk[Byte]]): Pull[F, String, Unit] =
        s.pull.uncons.flatMap {
          case Some((byteChunks, tail)) =>
            // use local and private mutability here
            var idx = 0
            val size = byteChunks.size
            val bldr = List.newBuilder[String]
            var buf1 = buf
            while (idx < size) {
              val nextBytes = byteChunks(idx)
              buf1 = processSingleChunk(bldr, buf1, nextBytes)
              idx = idx + 1
            }
            Pull.output(Chunk.from(bldr.result())) >> doPull(buf1, tail)
          case None if buf.nonEmpty =>
            Pull.output1(new String(buf.toArray, utf8Charset))
          case None =>
            Pull.done
        }

      def processByteOrderMark(
          buffer: Chunk.Queue[Byte] /* or null which we use as an Optional type to avoid boxing */,
          s: Stream[F, Chunk[Byte]]
      ): Pull[F, String, Unit] =
        s.pull.uncons1.flatMap {
          case Some((hd, tl)) =>
            val newBuffer0 =
              if (buffer ne null) buffer
              else Chunk.Queue.empty[Byte]

            val newBuffer: Chunk.Queue[Byte] = newBuffer0 :+ hd
            if (newBuffer.size >= 3) {
              val rem =
                if (newBuffer.startsWith(Chunk.byteVector(bom.utf8))) newBuffer.drop(3)
                else newBuffer
              doPull(Chunk.empty, Stream.emits(rem.chunks) ++ tl)
            } else if (newBuffer.startsWith(Chunk.byteVector(bom.utf8.take(newBuffer.size.toLong))))
              processByteOrderMark(newBuffer, tl)
            else doPull(Chunk.empty, Stream.emits(newBuffer.chunks) ++ tl)
          case None =>
            if (buffer ne null)
              doPull(Chunk.empty, Stream.emits(buffer.chunks))
            else Pull.done
        }

      (in: Stream[F, Chunk[Byte]]) => processByteOrderMark(null, in).stream
    }

    /** Encodes a stream of `String` in to a stream of bytes using the UTF-8 charset. */
    def encode[F[_]]: Pipe[F, String, Byte] =
      text.encode(utf8Charset)

    /** Encodes a stream of `String` in to a stream of `Chunk[Byte]` using the UTF-8 charset. */
    def encodeC[F[_]]: Pipe[F, String, Chunk[Byte]] =
      text.encodeC(utf8Charset)
  }

  def decodeCWithCharset[F[_]: RaiseThrowable](charset: Charset): Pipe[F, Chunk[Byte], String] =
    if (charset.name() == StandardCharsets.UTF_8.name())
      utf8.decodeC
    else
      decodeCWithGenericCharset(charset)

  private def decodeCWithGenericCharset[F[_]: RaiseThrowable](
      charset: Charset
  ): Pipe[F, Chunk[Byte], String] = {

    def decodeC(
        decoder: CharsetDecoder,
        acc: Chunk[Byte],
        s: Stream[F, Chunk[Byte]],
        lastOutBuffer: CharBuffer
    ): Pull[F, String, Unit] =
      s.pull.uncons1.flatMap { r =>
        val toDecode = r match {
          case Some((c, _)) => acc ++ c
          case None         => acc
        }

        val isLast = r.isEmpty
        (lastOutBuffer: Buffer).clear()

        val outBufferSize = (decoder.averageCharsPerByte() * toDecode.size).toInt

        val out =
          if (outBufferSize > lastOutBuffer.length())
            CharBuffer.allocate(outBufferSize)
          else lastOutBuffer

        val inBuffer = toDecode.toByteBuffer
        val result = decoder.decode(inBuffer, out, isLast)
        (out: Buffer).flip()

        val nextAcc =
          if (inBuffer.remaining() > 0) Chunk.byteBuffer(inBuffer.slice) else Chunk.empty

        val rest = r match {
          case Some((_, tail)) => tail
          case None            => Stream.empty
        }

        if (result.isError)
          Pull.raiseError(
            if (result.isMalformed) new MalformedInputException(result.length())
            else if (result.isUnmappable) new UnmappableCharacterException(result.length())
            else new CharacterCodingException()
          )
        // output generated from decoder
        else if (out.remaining() > 0)
          Pull.output1(out.toString) >> decodeC(decoder, nextAcc, rest, out)
        // no output, but more input
        else if (!isLast) decodeC(decoder, nextAcc, rest, out)
        // output buffer overrun. try again with a bigger buffer
        else if (nextAcc.nonEmpty)
          decodeC(
            decoder,
            nextAcc,
            rest,
            CharBuffer.allocate(
              outBufferSize + (decoder.maxCharsPerByte() * nextAcc.size).toInt
            )
          )
        else flush(decoder, lastOutBuffer) // no more input, flush for final output
      }

    def flush(
        decoder: CharsetDecoder,
        out: CharBuffer
    ): Pull[F, String, Unit] = {
      (out: Buffer).clear()
      decoder.flush(out) match {
        case res if res.isUnderflow =>
          if (out.position() > 0) {
            (out: Buffer).flip()
            Pull.output1(out.toString) >> Pull.done
          } else
            Pull.done
        case res if res.isOverflow =>
          // Can't find any that output more than two chars. This
          // oughtta do it.
          val newSize = (out.capacity + decoder.maxCharsPerByte * 2).toInt
          val bigger = CharBuffer.allocate(newSize)
          flush(decoder, bigger)
        case res =>
          ApplicativeThrow[Pull[F, String, *]].catchNonFatal(res.throwException())
      }
    }

    { s =>
      Stream.suspend(Stream.emit(charset.newDecoder())).flatMap { decoder =>
        decodeC(decoder, Chunk.empty, s, CharBuffer.allocate(0)).stream
      }
    }

  }

  def decodeWithCharset[F[_]: RaiseThrowable](charset: Charset): Pipe[F, Byte, String] =
    _.chunks.through(decodeCWithCharset(charset))

  /** Converts UTF-8 encoded byte stream to a stream of `String`. */
  @deprecated("Use text.utf8.decode", "3.1.0")
  def utf8Decode[F[_]]: Pipe[F, Byte, String] =
    utf8.decode

  /** Converts UTF-8 encoded `Chunk[Byte]` inputs to `String`. */
  @deprecated("Use text.utf8.decodeC", "3.1.0")
  def utf8DecodeC[F[_]]: Pipe[F, Chunk[Byte], String] =
    utf8.decodeC

  /** Encodes a stream of `String` in to a stream of bytes using the given charset. */
  def encode[F[_]](charset: Charset): Pipe[F, String, Byte] = { s =>
    Stream
      .suspend(Stream.emit(charset.newEncoder()))
      .flatMap { // dispatch over different implementations for performance reasons
        case encoder
            if charset == StandardCharsets.UTF_8 ||
              encoder.averageBytesPerChar() == encoder.maxBytesPerChar() =>
          // 1. we know UTF-8 doesn't produce BOMs in encoding
          // 2. maxBytes accounts for BOMs, average doesn't, so if they're equal, the charset encodes no BOM.
          // In these cases, we can delegate to getBytes without having to fear BOMs being added in the wrong places.
          // As the JDK optimizes this very well, this is the fastest implementation.
          s.mapChunks(c => c.flatMap(s => Chunk.array(s.getBytes(charset))))
        case _ if charset == StandardCharsets.UTF_16 =>
          // encode strings individually to profit from Java optimizations, strip superfluous BOMs from output
          encodeUsingBOMSlicing(s, charset, bom.utf16Big, doDrop = false).stream.unchunks
        case encoder =>
          // fallback to slower implementation using CharsetEncoder, known to be correct for all charsets
          encodeUsingCharsetEncoder[F](encoder)(s).unchunks
      }
  }

  /** Encodes a stream of `String` in to a stream of `Chunk[Byte]` using the given charset. */
  def encodeC[F[_]](charset: Charset): Pipe[F, String, Chunk[Byte]] =
    s =>
      Stream
        .suspend(Stream.emit(charset.newEncoder()))
        .flatMap { // dispatch over different implementations for performance reasons
          case encoder
              if charset == StandardCharsets.UTF_8 ||
                encoder.averageBytesPerChar() == encoder.maxBytesPerChar() =>
            // 1. we know UTF-8 doesn't produce BOMs in encoding
            // 2. maxBytes accounts for BOMs, average doesn't, so if they're equal, the charset encodes no BOM.
            // In these cases, we can delegate to getBytes without having to fear BOMs being added in the wrong places.
            // As the JDK optimizes this very well, this is the fastest implementation.
            s.mapChunks(_.map(s => Chunk.array(s.getBytes(charset))))
          case _ if charset == StandardCharsets.UTF_16 =>
            // encode strings individually to profit from Java optimizations, strip superfluous BOMs from output
            encodeUsingBOMSlicing(s, charset, bom.utf16Big, doDrop = false).stream
          case encoder =>
            // fallback to slower implementation using CharsetEncoder, known to be correct for all charsets
            encodeUsingCharsetEncoder[F](encoder)(s)
        }

  private def encodeUsingBOMSlicing[F[_]](
      s: Stream[F, String],
      charset: Charset,
      bom: ByteVector,
      doDrop: Boolean
  ): Pull[F, Chunk[Byte], Unit] =
    s.pull.uncons1.flatMap {
      case Some((hd, tail)) =>
        val bytes = Chunk.array(hd.getBytes(charset))
        val dropped =
          if (doDrop && bytes.startsWith(Chunk.byteVector(bom))) bytes.drop(bom.length.toInt)
          else bytes
        Pull.output1(dropped) >> encodeUsingBOMSlicing(tail, charset, bom, doDrop = true)
      case None => Pull.done
    }

  private def encodeUsingCharsetEncoder[F[_]](
      encoder: CharsetEncoder
  ): Pipe[F, String, Chunk[Byte]] = {
    def encodeC(
        encoder: CharsetEncoder,
        acc: Chunk[Char],
        s: Stream[F, Chunk[Char]]
    ): Pull[F, Chunk[Byte], Unit] =
      s.pull.uncons1.flatMap { r =>
        val toEncode = r match {
          case Some((c, _)) => acc ++ c
          case None         => acc
        }

        val isLast = r.isEmpty
        val outBufferSize =
          math.max(encoder.maxBytesPerChar(), encoder.averageBytesPerChar() * toEncode.size).toInt

        val out = ByteBuffer.allocate(outBufferSize)

        val inBuffer = toEncode.toCharBuffer
        encoder.encode(inBuffer, out, isLast)
        (out: Buffer).flip()

        val nextAcc =
          if (inBuffer.remaining() > 0) Chunk.charBuffer(inBuffer.slice()) else Chunk.empty

        val rest = r match {
          case Some((_, tail)) => tail
          case None            => Stream.empty
        }

        if (out.remaining() > 0) {
          Pull.output1(Chunk.ByteBuffer.view(out)) >> encodeC(encoder, nextAcc, rest)
        } else if (!isLast) {
          encodeC(encoder, nextAcc, rest)
        } else if (nextAcc.nonEmpty) {
          encodeC(encoder, nextAcc, rest)
        } else flush(encoder, ByteBuffer.allocate(0))
      }

    @tailrec
    def flush(
        encoder: CharsetEncoder,
        out: ByteBuffer
    ): Pull[F, Chunk[Byte], Unit] = {
      (out: Buffer).clear()
      encoder.flush(out) match {
        case res if res.isUnderflow =>
          if (out.position() > 0) {
            (out: Buffer).flip()
            Pull.output1(Chunk.ByteBuffer.view(out)) >> Pull.done
          } else
            Pull.done
        case res if res.isOverflow =>
          val newSize = (out.capacity + encoder.maxBytesPerChar() * 2).toInt
          val bigger = ByteBuffer.allocate(newSize)
          flush(encoder, bigger)
        case res =>
          ApplicativeThrow[Pull[F, Chunk[Byte], *]].catchNonFatal(res.throwException())
      }
    }

    { s =>
      val configuredEncoder = encoder
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
      encodeC(
        configuredEncoder,
        Chunk.empty,
        s.map(s => Chunk.CharBuffer.view(CharBuffer.wrap(s)))
      ).stream
    }
  }

  /** Encodes a stream of `String` in to a stream of bytes using the UTF-8 charset. */
  @deprecated("Use text.utf8.encode", "3.1.0")
  def utf8Encode[F[_]]: Pipe[F, String, Byte] =
    utf8.encode

  /** Encodes a stream of `String` in to a stream of `Chunk[Byte]` using the UTF-8 charset. */
  @deprecated("Use text.utf8.encodeC", "3.1.0")
  def utf8EncodeC[F[_]]: Pipe[F, String, Chunk[Byte]] =
    utf8.encodeC

  /** Transforms a stream of `String` such that each emitted `String` is a line from the input
    * @param maxLineLength maximum size to accumulate a line to; throw an error if a line is larger
    */
  def linesLimited[F[_]: RaiseThrowable](maxLineLength: Int): Pipe[F, String, String] =
    linesImpl[F](maxLineLength = Some((maxLineLength, implicitly[RaiseThrowable[F]])))

  /** Transforms a stream of `String` such that each emitted `String` is a line from the input. */
  def lines[F[_]]: Pipe[F, String, String] = linesImpl[F](None)

  private def linesImpl[F[_]](
      maxLineLength: Option[(Int, RaiseThrowable[F])]
  ): Pipe[F, String, String] = {
    def fillBuffers(
        stringBuilder: StringBuilder,
        linesBuffer: ArrayBuffer[String],
        string: String,
        ignoreFirstCharNewLine: BoolWrapper
    ): Unit = {
      var i = if (ignoreFirstCharNewLine.value) {
        ignoreFirstCharNewLine.value = false
        if (string.nonEmpty && string(0) == '\n') {
          1
        } else {
          0
        }
      } else {
        0
      }

      val stringSize = string.size
      while (i < stringSize) {
        val idx = indexForNl(string, stringSize, i)
        if (idx < 0) {
          stringBuilder.appendAll(string.slice(i, stringSize))
          i = stringSize
        } else {
          if (stringBuilder.isEmpty) {
            linesBuffer += string.slice(i, idx)
          } else {
            stringBuilder.appendAll(string.slice(i, idx))
            linesBuffer += stringBuilder.result()
            stringBuilder.clear()
          }
          i = idx + 1
          if (string(i - 1) == '\r') {
            if (i < stringSize) {
              if (string(i) == '\n') {
                i += 1
              }
            } else {
              ignoreFirstCharNewLine.value = true
            }
          }
        }
      }
    }

    def go(
        stream: Stream[F, String],
        stringBuilder: StringBuilder,
        ignoreFirstCharNewLine: BoolWrapper,
        first: Boolean
    ): Pull[F, String, Unit] =
      stream.pull.uncons.flatMap {
        case None =>
          if (first) Pull.done
          else {
            val result = stringBuilder.result()
            if (result.nonEmpty && result.last == '\r')
              Pull.output(
                Chunk(
                  result.dropRight(1),
                  ""
                )
              )
            else Pull.output1(result)
          }
        case Some((chunk, stream)) =>
          val linesBuffer = ArrayBuffer.empty[String]
          chunk.foreach { string =>
            fillBuffers(stringBuilder, linesBuffer, string, ignoreFirstCharNewLine)
          }

          maxLineLength match {
            case Some((max, raiseThrowable)) if stringBuilder.length > max =>
              Pull.raiseError[F](
                new LineTooLongException(stringBuilder.length, max)
              )(raiseThrowable)
            case _ =>
              Pull.output(Chunk.from(linesBuffer)) >> go(
                stream,
                stringBuilder,
                ignoreFirstCharNewLine,
                first = false
              )
          }
      }

    s =>
      Stream.suspend(
        go(
          s,
          new StringBuilder(),
          new BoolWrapper(false),
          first = true
        ).stream
      )
  }

  /** Transforms a stream of `String` to a stream of `Char`. */
  def string2char[F[_]]: Pipe[F, String, Char] =
    _.flatMap(s => Stream.chunk(Chunk.charBuffer(CharBuffer.wrap(s))))

  /** Transforms a stream of `Char` to a stream of `String`. */
  def char2string[F[_]]: Pipe[F, Char, String] = _.chunks.map { chunk =>
    val Chunk.ArraySlice(chars, offset, length) = chunk.toArraySlice
    new String(chars, offset, length)
  }

  class LineTooLongException(val length: Int, val max: Int)
      extends RuntimeException(
        s"Max line size is $max but $length chars have been accumulated"
      )

  /** Functions for working with base 64. */
  object base64 {

    /** Converts a stream of base 64 text in to a stream of bytes.
      *
      * If the text is not valid base 64, the pipe fails with an exception. Padding
      * characters at the end of the input stream are optional, but if present, must
      * be valid per the base 64 specification. Whitespace characters are ignored.
      *
      * The default base 64 alphabet is used by this pipe.
      */
    def decode[F[_]: RaiseThrowable]: Pipe[F, String, Byte] =
      decodeWithAlphabet(Bases.Alphabets.Base64)

    /** Like [[decode]] but takes a base 64 alphabet. For example,
      * `decodeWithAlphabet(Bases.Alphabets.Base64Url)` will decode URL compatible base 64.
      */
    def decodeWithAlphabet[F[_]: RaiseThrowable](
        alphabet: Bases.Base64Alphabet
    ): Pipe[F, String, Byte] = {
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
            case c                       =>
              val cidx =
                if (padding == 0)
                  if (c == Pad)
                    if (mod == 2 || mod == 3) {
                      padding += 1
                      0
                    } else
                      return paddingError
                  else
                    try alphabet.toIndex(c)
                    catch {
                      case _: IllegalArgumentException =>
                        return Left(s"Invalid base 64 character '$c' at index $idx")
                    }
                else if (c == Pad)
                  if (padding == 1 && mod == 3) {
                    padding += 1
                    0
                  } else
                    return paddingError
                else
                  return Left(
                    s"Unexpected character '$c' at index $idx after padding character; only '=' and whitespace characters allowed after first padding character"
                  )
              mod match {
                case 0 =>
                  buffer = cidx & 0x3f
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

    /** Encodes a byte stream in to a stream of base 64 text.
      * The default base 64 alphabet is used by this pipe.
      */
    def encode[F[_]]: Pipe[F, Byte, String] =
      encodeWithAlphabet(Bases.Alphabets.Base64)

    /** Like [[encode]] but takes a base 64 alphabet. For example,
      * `encodeWithAlphabet(Bases.Alphabets.Base64Url)` will encode URL compatible base 64.
      */
    def encodeWithAlphabet[F[_]](alphabet: Bases.Base64Alphabet): Pipe[F, Byte, String] = {
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
        (bldr: Buffer).flip()
        val out = bldr.toString
        if (mod == 0)
          (out, ByteVector.empty)
        else if (mod == 1)
          (out, ByteVector(bytes(idx)))
        else
          (out, ByteVector(bytes(idx), bytes(idx + 1)))
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

  /** Functions for working with base 64. */
  object hex {

    /** Converts a stream of hex text in to a stream of bytes.
      *
      * If the text is not valid hex, the pipe fails with an exception.
      * There must be an even number of hex digits (nibbles) or the pipe
      * fails with an exception.  Whitespace characters are ignored.
      *
      * The default alphabet is used by this pipe.
      */
    def decode[F[_]: RaiseThrowable]: Pipe[F, String, Byte] =
      decodeWithAlphabet(Bases.Alphabets.HexLowercase)

    /** Like `decode` but takes a hex alphabet. */
    def decodeWithAlphabet[F[_]: RaiseThrowable](
        alphabet: Bases.HexAlphabet
    ): Pipe[F, String, Byte] = {
      // Adapted from scodec-bits, licensed under 3-clause BSD
      def decode1(str: String, hi0: Int, midByte0: Boolean): (Chunk[Byte], Int, Boolean) = {
        val bldr = ByteBuffer.allocate((str.size + 1) / 2)
        var idx, count = 0
        var hi = hi0
        var midByte = midByte0
        while (idx < str.length) {
          val c = str(idx)
          if (!alphabet.ignore(c))
            try {
              val nibble = alphabet.toIndex(c)
              if (midByte) {
                bldr.put((hi | nibble).toByte)
                midByte = false
              } else {
                hi = (nibble << 4).toByte.toInt
                midByte = true
              }
              count += 1
            } catch {
              case _: IllegalArgumentException =>
                throw new IllegalArgumentException(s"Invalid hexadecimal character '$c'")
            }
          idx += 1
        }
        (bldr: Buffer).flip()
        (Chunk.byteVector(ByteVector(bldr)), hi, midByte)
      }
      def dropPrefix(s: Stream[F, String], acc: String): Pull[F, Byte, Unit] =
        s.pull.uncons1.flatMap {
          case Some((hd, tl)) =>
            if (acc.size + hd.size < 2) dropPrefix(tl, acc + hd)
            else {
              val str = acc + hd
              val withoutPrefix =
                if (str.startsWith("0x") || str.startsWith("0X")) str.substring(2) else str
              go(tl.cons1(withoutPrefix), 0, false)
            }
          case None =>
            Pull.done
        }
      def go(s: Stream[F, String], hi: Int, midByte: Boolean): Pull[F, Byte, Unit] =
        s.pull.uncons1.flatMap {
          case Some((hd, tl)) =>
            val (out, newHi, newMidByte) = decode1(hd, hi, midByte)
            Pull.output(out) >> go(tl, newHi, newMidByte)
          case None =>
            if (midByte) Pull.raiseError(new IllegalArgumentException("Nibble left over"))
            else Pull.done
        }
      s => dropPrefix(s, "").stream
    }

    /** Encodes a byte stream in to a stream of hexadecimal text.
      * The default hex alphabet is used by this pipe.
      */
    def encode[F[_]]: Pipe[F, Byte, String] =
      encodeWithAlphabet(Bases.Alphabets.HexLowercase)

    /** Like `encode` but takes a hex alphabet. */
    def encodeWithAlphabet[F[_]](alphabet: Bases.HexAlphabet): Pipe[F, Byte, String] =
      _.chunks.map(c => c.toByteVector.toHex(alphabet))
  }

  private class BoolWrapper(var value: Boolean)

  @inline private def indexForNl(string: String, stringSize: Int, begin: Int): Int = {
    var i = begin
    while (i < stringSize)
      string.charAt(i) match {
        case '\n' | '\r' => return i
        case _           => i = i + 1
      }
    -1
  }

}
