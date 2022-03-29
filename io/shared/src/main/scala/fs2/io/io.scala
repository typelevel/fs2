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

import cats.effect.kernel.Sync

import java.io.{InputStream, OutputStream}

/** Provides various ways to work with streams that perform IO.
  */
package object io extends ioplatform {
  type IOException = java.io.IOException

  /** Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    */
  def readInputStream[F[_]](
      fis: F[InputStream],
      chunkSize: Int,
      closeAfterUse: Boolean = true
  )(implicit F: Sync[F]): Stream[F, Byte] = {
    val newArray = F.delay(new Array[Byte](chunkSize))
    readInputStreamGeneric(fis, newArray, chunkSize, closeAfterUse)
  }

  /** Whenever a read from the Input has too few bytes to fill the buffer,
    * so that at least `minShare` bytes are left after the read ones, then
    * the next read will use the same array of bytes, after the last ones.
    * This does not prevent emitting those as a chunk.
    */
  def readInputStreamShare[F[_]](
      fis: F[InputStream],
      maxChunkSize: Int,
      minShare: Int = 64,
      closeAfterUse: Boolean = true
  )(implicit F: Sync[F]): Stream[F, Byte] =
    if (maxChunkSize >= minShare) {
      val newArray = F.delay(new Array[Byte](maxChunkSize))
      readInputStreamGeneric(fis, newArray, minShare, closeAfterUse)
    } else
      Stream.raiseError[F](new Exception("minShare should be smaller than chunk size"))

  /** Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * Recycles an underlying input buffer for performance. It is safe to call
    * this as long as whatever consumes this `Stream` does not store the `Chunk`
    * returned or pipe it to a combinator that does (e.g. `buffer`). Use
    * `readInputStream` for a safe version.
    */
  def unsafeReadInputStream[F[_]](
      fis: F[InputStream],
      chunkSize: Int,
      closeAfterUse: Boolean = true
  )(implicit F: Sync[F]): Stream[F, Byte] = {
    val newArray = F.pure(new Array[Byte](chunkSize))
    readInputStreamGeneric(fis, newArray, chunkSize, closeAfterUse)
  }

  private def readInputStreamGeneric[F[_]](
      openInputStream: F[InputStream],
      buf: F[Array[Byte]],
      minShare: Int,
      closeAfterUse: Boolean
  )(implicit F: Sync[F]): Stream[F, Byte] = {
    def readLoop(input: InputStream, barr: Array[Byte], offset: Int): Pull[F, Byte, Unit] = {
      val available = barr.length - offset

      Pull.eval(F.blocking(input.read(barr, offset, available))).flatMap { numBytes =>
        if (numBytes < 0) // stream closed or no more bytes
          Pull.done
        else if (numBytes == 0)
          readLoop(input, barr, offset) // no change
        else
          Pull.output(Chunk.array(barr, offset, numBytes)) >> {
            val nextOffset = offset + numBytes
            if (barr.length - nextOffset >= minShare)
              readLoop(input, barr, nextOffset)
            else
              Pull.eval(buf).flatMap(readLoop(input, _, 0))
          }
      }
    }

    val openStream: Stream[F, InputStream] =
      if (closeAfterUse)
        Stream.bracket(openInputStream)(input => F.blocking(input.close()))
      else
        Stream.eval(openInputStream)

    openStream.flatMap(input => Pull.eval(buf).flatMap(readLoop(input, _, 0)).streamNoScope)
  }

  /** Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * Each write operation is performed on the supplied execution context. Writes are
    * blocking so the execution context should be configured appropriately.
    */
  def writeOutputStream[F[_]](
      fos: F[OutputStream],
      closeAfterUse: Boolean = true
  )(implicit F: Sync[F]): Pipe[F, Byte, INothing] =
    s => {
      def useOs(os: OutputStream): Stream[F, INothing] =
        s.chunks.foreach(c => F.blocking(os.write(c.toArray)))

      val os =
        if (closeAfterUse) Stream.bracket(fos)(os => F.blocking(os.close()))
        else Stream.eval(fos)
      os.flatMap(os => useOs(os) ++ Stream.exec(F.blocking(os.flush())))
    }

}
