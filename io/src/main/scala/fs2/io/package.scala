package fs2

import fs2.util.{Async, Suspendable}
import fs2.util.syntax._
import java.io.{InputStream, OutputStream}

package object io {
  import JavaInputOutputStream._

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * Blocks the current thread.
    */
  def readInputStream[F[_]: Suspendable](fis: F[InputStream], chunkSize: Int, closeAfterUse: Boolean = true): Stream[F, Byte] =
    readInputStreamGeneric(fis, chunkSize, readBytesFromInputStream[F], closeAfterUse)

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * This will block a thread in the current `Async` instance, so the size of any associated
    * threadpool should be sized appropriately.
    */
  def readInputStreamAsync[F[_]](fis: F[InputStream], chunkSize: Int, closeAfterUse: Boolean = true)(implicit F: Async[F]): Stream[F, Byte] = {
    def readAsync(is: InputStream, buf: Array[Byte]) =
      F.start(readBytesFromInputStream(is, buf)).flatMap(identity)

    readInputStreamGeneric(fis, chunkSize, readAsync, closeAfterUse)
  }

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * Blocks the current thread.
    */
  def writeOutputStream[F[_]: Suspendable](fos: F[OutputStream], closeAfterUse: Boolean = true): Sink[F, Byte] =
    writeOutputStreamGeneric(fos, closeAfterUse, writeBytesToOutputStream[F])

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * This will block a thread in the current `Async` instance, so the size of any associated
    * threadpool should be sized appropriately.
    */
  def writeOutputStreamAsync[F[_]](fos: F[OutputStream], closeAfterUse: Boolean = true)(implicit F: Async[F]): Sink[F, Byte] = {
    def writeAsync(os: OutputStream, buf: Chunk[Byte]) =
      F.start(writeBytesToOutputStream(os, buf)).flatMap(identity)

    writeOutputStreamGeneric(fos, closeAfterUse, writeAsync)
  }

  //
  // STDIN/STDOUT Helpers

  def stdin[F[_]](bufSize: Int)(implicit F: Suspendable[F]): Stream[F, Byte] =
    readInputStream(F.delay(System.in), bufSize, false)

  def stdinAsync[F[_]](bufSize: Int)(implicit F: Async[F]): Stream[F, Byte] =
    readInputStreamAsync(F.delay(System.in), bufSize, false)

  def stdout[F[_]](implicit F: Suspendable[F]): Sink[F, Byte] =
    writeOutputStream(F.delay(System.out), false)

  def stdoutAsync[F[_]](implicit F: Async[F]): Sink[F, Byte] =
    writeOutputStreamAsync(F.delay(System.out), false)
}
