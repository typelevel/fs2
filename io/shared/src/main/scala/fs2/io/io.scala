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

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.effect.syntax.all._
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
    )((is, buf, off) => F.blocking(is.read(buf, off, buf.length - off)))

  private[io] def readInputStreamCancelable[F[_]](
      fis: F[InputStream],
      cancel: F[Unit],
      chunkSize: Int,
      closeAfterUse: Boolean = true
  )(implicit F: Async[F]): Stream[F, Byte] =
    readInputStreamGeneric(
      fis,
      F.delay(new Array[Byte](chunkSize)),
      closeAfterUse
    )((is, buf, off) => F.blocking(is.read(buf, off, buf.length - off)).cancelable(cancel))

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
    )((is, buf, off) => F.blocking(is.read(buf, off, buf.length - off)))

  private def readBytesFromInputStream[F[_]](is: InputStream, buf: Array[Byte], offset: Int)(
      read: (InputStream, Array[Byte], Int) => F[Int]
  )(implicit
      F: Sync[F]
  ): F[Option[(Chunk[Byte], Option[(Array[Byte], Int)])]] =
    read(is, buf, offset).map { numBytes =>
      if (numBytes < 0) None
      else if (offset + numBytes == buf.size) Some(Chunk.array(buf, offset, numBytes) -> None)
      else Some(Chunk.array(buf, offset, numBytes) -> Some(buf -> (offset + numBytes)))
    }

  private[fs2] def readInputStreamGeneric[F[_]](
      fis: F[InputStream],
      buf: F[Array[Byte]],
      closeAfterUse: Boolean
  )(read: (InputStream, Array[Byte], Int) => F[Int])(implicit F: Sync[F]): Stream[F, Byte] = {
    def useIs(is: InputStream) = Stream.unfoldChunkEval(Option.empty[(Array[Byte], Int)]) {
      case None              => buf.flatMap(b => readBytesFromInputStream(is, b, 0)(read))
      case Some((b, offset)) => readBytesFromInputStream(is, b, offset)(read)
    }

    if (closeAfterUse)
      Stream.bracket(fis)(is => Sync[F].blocking(is.close())).flatMap(useIs)
    else
      Stream.eval(fis).flatMap(useIs)
  }

  /** Writes all bytes to the specified `OutputStream`. Each chunk is flushed
    * immediately after writing. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    */
  def writeOutputStream[F[_]](
      fos: F[OutputStream],
      closeAfterUse: Boolean = true
  )(implicit F: Sync[F]): Pipe[F, Byte, Nothing] =
    writeOutputStreamGeneric(fos, closeAfterUse) { (os, b, off, len) =>
      F.interruptible {
        os.write(b, off, len)
        os.flush()
      }
    }

  private[io] def writeOutputStreamCancelable[F[_]](
      fos: F[OutputStream],
      cancel: F[Unit],
      closeAfterUse: Boolean = true
  )(implicit F: Async[F]): Pipe[F, Byte, Nothing] =
    writeOutputStreamGeneric(fos, closeAfterUse) { (os, b, off, len) =>
      F.blocking {
        os.write(b, off, len)
        os.flush()
      }.cancelable(cancel)
    }

  private def writeOutputStreamGeneric[F[_]](
      fos: F[OutputStream],
      closeAfterUse: Boolean
  )(
      write: (OutputStream, Array[Byte], Int, Int) => F[Unit]
  )(implicit F: Sync[F]): Pipe[F, Byte, Nothing] =
    s => {
      def useOs(os: OutputStream): Stream[F, Nothing] =
        s.chunks.foreach { c =>
          val Chunk.ArraySlice(b, off, len) = c.toArraySlice[Byte]
          write(os, b, off, len)
        }

      val os =
        if (closeAfterUse) Stream.bracket(fos)(os => F.blocking(os.close()))
        else Stream.eval(fos)
      os.flatMap(os => useOs(os) ++ Stream.exec(F.blocking(os.flush())))
    }

}
