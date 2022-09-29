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
import cats.syntax.all._

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
  )(implicit F: Sync[F]): Stream[F, Byte] =
    readInputStreamGeneric(
      fis,
      F.delay(new Array[Byte](chunkSize)),
      closeAfterUse
    )

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
  )(implicit F: Sync[F]): Stream[F, Byte] =
    readInputStreamGeneric(
      fis,
      F.pure(new Array[Byte](chunkSize)),
      closeAfterUse
    )

  private def readBytesFromInputStream[F[_]](is: InputStream, buf: Array[Byte])(implicit
      F: Sync[F]
  ): F[Option[Chunk[Byte]]] =
    F.blocking(is.read(buf)).map { numBytes =>
      if (numBytes < 0) None
      else if (numBytes == 0) Some(Chunk.empty)
      else if (numBytes < buf.size) Some(Chunk.array(buf, 0, numBytes))
      else Some(Chunk.array(buf))
    }

  private def readInputStreamGeneric[F[_]](
      fis: F[InputStream],
      buf: F[Array[Byte]],
      closeAfterUse: Boolean
  )(implicit F: Sync[F]): Stream[F, Byte] = {
    def useIs(is: InputStream) =
      Stream
        .eval(buf.flatMap(b => readBytesFromInputStream(is, b)))
        .repeat
        .unNoneTerminate
        .flatMap(c => Stream.chunk(c))

    if (closeAfterUse)
      Stream.bracket(fis)(is => Sync[F].blocking(is.close())).flatMap(useIs)
    else
      Stream.eval(fis).flatMap(useIs)
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
  )(implicit F: Sync[F]): Pipe[F, Byte, Nothing] =
    s => {
      def useOs(os: OutputStream): Stream[F, Nothing] =
        s.chunks.foreach(c => F.interruptible(os.write(c.toArray)))

      val os =
        if (closeAfterUse) Stream.bracket(fos)(os => F.blocking(os.close()))
        else Stream.eval(fos)
      os.flatMap(os => useOs(os) ++ Stream.exec(F.blocking(os.flush())))
    }

}
