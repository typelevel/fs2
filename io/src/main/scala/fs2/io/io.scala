package fs2

import java.io.{InputStream, OutputStream}

import cats.effect.{Async, ConcurrentEffect, Timer}

import _root_.io.chrisdavenport.linebacker.Linebacker

/** Provides various ways to work with streams that perform IO. */
package object io {
  import JavaInputOutputStream._

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * Blocks on read operations from the input stream.
    */
  def readInputStream[F[_]](fis: F[InputStream], chunkSize: Int, closeAfterUse: Boolean = true)(
      implicit F: Async[F],
      linebacker: Linebacker[F],
      timer: Timer[F]): Stream[F, Byte] =
    readInputStreamGeneric(fis,
                           F.delay(new Array[Byte](chunkSize)),
                           readBytesFromInputStream[F],
                           closeAfterUse)

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * Like `readInputStream` but each read operation is performed on the supplied execution
    * context. Reads are blocking so the execution context should be configured appropriately.
    */
  def readInputStreamAsync[F[_]](fis: F[InputStream],
                                 chunkSize: Int,
                                 closeAfterUse: Boolean = true)(implicit F: Async[F],
                                                                linebacker: Linebacker[F],
                                                                timer: Timer[F]): Stream[F, Byte] =
    readInputStreamGeneric(
      fis,
      F.delay(new Array[Byte](chunkSize)),
      (is, buf) => Linebacker[F].blockTimer(readBytesFromInputStream(is, buf)),
      closeAfterUse
    )

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * Recycles an underlying input buffer for performance. It is safe to call
    * this as long as whatever consumes this `Stream` does not store the `Chunk`
    * returned or pipe it to a combinator that does (e.g., `buffer`). Use
    * `readInputStream` for a safe version.
    *
    * Blocks on read operations from the input stream.
    */
  def unsafeReadInputStream[F[_]](fis: F[InputStream],
                                  chunkSize: Int,
                                  closeAfterUse: Boolean = true)(implicit F: Async[F],
                                                                 linebacker: Linebacker[F],
                                                                 timer: Timer[F]): Stream[F, Byte] =
    readInputStreamGeneric(fis,
                           F.pure(new Array[Byte](chunkSize)),
                           readBytesFromInputStream[F],
                           closeAfterUse)

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * Each read operation is performed on the supplied execution context. Reads are
    * blocking so the execution context should be configured appropriately.
    *
    * Recycles an underlying input buffer for performance. It is safe to call
    * this as long as whatever consumes this `Stream` does not store the `Chunk`
    * returned or pipe it to a combinator that does (e.g. `buffer`). Use
    * `readInputStream` for a safe version.
    */
  def unsafeReadInputStreamAsync[F[_]](fis: F[InputStream],
                                       chunkSize: Int,
                                       closeAfterUse: Boolean = true)(
      implicit F: Async[F],
      linebacker: Linebacker[F],
      timer: Timer[F]): Stream[F, Byte] =
    readInputStreamGeneric(
      fis,
      F.pure(new Array[Byte](chunkSize)),
      (is, buf) => Linebacker[F].blockTimer(readBytesFromInputStream(is, buf)),
      closeAfterUse
    )

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * Blocks on write operations to the input stream.
    */
  def writeOutputStream[F[_]: Async: Linebacker: Timer](
      fos: F[OutputStream],
      closeAfterUse: Boolean = true): Sink[F, Byte] =
    writeOutputStreamGeneric(fos, closeAfterUse, writeBytesToOutputStream[F])

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * Each write operation is performed on the supplied execution context. Writes are
    * blocking so the execution context should be configured appropriately.
    */
  def writeOutputStreamAsync[F[_]](fos: F[OutputStream], closeAfterUse: Boolean = true)(
      implicit F: Async[F],
      linebacker: Linebacker[F],
      timer: Timer[F]): Sink[F, Byte] =
    writeOutputStreamGeneric(
      fos,
      closeAfterUse,
      (os, buf) => Linebacker[F].blockTimer(writeBytesToOutputStream(os, buf)))

  //
  // STDIN/STDOUT Helpers

  /** Stream of bytes read from standard input. */
  def stdin[F[_]](bufSize: Int)(implicit F: Async[F],
                                linebacker: Linebacker[F],
                                timer: Timer[F]): Stream[F, Byte] =
    readInputStream(F.delay(System.in), bufSize, false)

  /** Stream of bytes read asynchronously from standard input. */
  def stdinAsync[F[_]](bufSize: Int)(implicit F: Async[F],
                                     linebacker: Linebacker[F],
                                     timer: Timer[F]): Stream[F, Byte] =
    readInputStreamAsync(F.delay(System.in), bufSize, false)

  /** Sink of bytes that writes emitted values to standard output. */
  def stdout[F[_]](implicit F: Async[F],
                   linebacker: Linebacker[F],
                   timer: Timer[F]): Sink[F, Byte] =
    writeOutputStream(F.delay(System.out), false)

  /** Sink of bytes that writes emitted values to standard output asynchronously. */
  def stdoutAsync[F[_]](implicit F: Async[F],
                        linebacker: Linebacker[F],
                        timer: Timer[F]): Sink[F, Byte] =
    writeOutputStreamAsync(F.delay(System.out), false)

  /**
    * Pipe that converts a stream of bytes to a stream that will emits a single `java.io.InputStream`,
    * that is closed whenever the resulting stream terminates.
    *
    * If the `close` of resulting input stream is invoked manually, then this will await until the
    * original stream completely terminates.
    *
    * Because all `InputStream` methods block (including `close`), the resulting `InputStream`
    * should be consumed on a different thread pool than the one that is backing the `Timer`.
    *
    * Note that the implementation is not thread safe -- only one thread is allowed at any time
    * to operate on the resulting `java.io.InputStream`.
    */
  def toInputStream[F[_]](implicit F: ConcurrentEffect[F],
                          timer: Timer[F]): Pipe[F, Byte, InputStream] =
    JavaInputOutputStream.toInputStream

}
