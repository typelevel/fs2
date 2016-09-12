package fs2

import fs2.util.{Async, Suspendable}
import fs2.util.syntax._
import java.io.{InputStream, OutputStream}

/** Provides various ways to work with streams that perform IO. */
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

  /** Stream of bytes read from standard input. */
  def stdin[F[_]](bufSize: Int)(implicit F: Suspendable[F]): Stream[F, Byte] =
    readInputStream(F.delay(System.in), bufSize, false)

  /** Stream of bytes read asynchronously from standard input. */
  def stdinAsync[F[_]](bufSize: Int)(implicit F: Async[F]): Stream[F, Byte] =
    readInputStreamAsync(F.delay(System.in), bufSize, false)

  /** Sink of bytes that writes emitted values to standard output. */
  def stdout[F[_]](implicit F: Suspendable[F]): Sink[F, Byte] =
    writeOutputStream(F.delay(System.out), false)

  /** Sink of bytes that writes emitted values to standard output asynchronously. */
  def stdoutAsync[F[_]](implicit F: Async[F]): Sink[F, Byte] =
    writeOutputStreamAsync(F.delay(System.out), false)

  /**
    * Pipe that converts a stream of bytes to a stream that will emits a single `java.io.InputStream`,
    * that is closed whenever the resulting stream terminates.
    *
    * If the `close` of resulting input stream is invoked manually, then this will await until the
    * original stream completely terminates.
    *
    * Because all `InputStream` methods block (including `close`), the resulting `InputStream`
    * should be consumed on a different thread pool than the one that is backing `Async[F]`.
    *
    * Note that the implementation is not thread safe -- only one thread is allowed at any time
    * to operate on the resulting `java.io.InputStream`.
    */
  def toInputStream[F[_]](implicit F: Async[F]): Pipe[F,Byte,InputStream] =
    JavaInputOutputStream.toInputStream
}
