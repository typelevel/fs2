package fs2

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync}

import cats._
import cats.implicits._
import java.io.{InputStream, OutputStream}
import java.nio.charset.Charset

import scala.concurrent.{ExecutionContext, blocking}

/**
  * Provides various ways to work with streams that perform IO.
  *
  * These methods accept a blocking `ExecutionContext`, as the underlying
  * implementations perform blocking IO. The recommendation is to use an
  * unbounded thread pool with application level bounds.
  *
  * @see [[https://typelevel.org/cats-effect/concurrency/basics.html#blocking-threads]]
  */
package object io {
  private val utf8Charset = Charset.forName("UTF-8")

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    */
  def readInputStream[F[_]](
      fis: F[InputStream],
      chunkSize: Int,
      blockingExecutionContext: ExecutionContext,
      closeAfterUse: Boolean = true)(implicit F: Sync[F], cs: ContextShift[F]): Stream[F, Byte] =
    readInputStreamGeneric(
      fis,
      F.delay(new Array[Byte](chunkSize)),
      blockingExecutionContext,
      closeAfterUse
    )

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
  def unsafeReadInputStream[F[_]](
      fis: F[InputStream],
      chunkSize: Int,
      blockingExecutionContext: ExecutionContext,
      closeAfterUse: Boolean = true)(implicit F: Sync[F], cs: ContextShift[F]): Stream[F, Byte] =
    readInputStreamGeneric(
      fis,
      F.pure(new Array[Byte](chunkSize)),
      blockingExecutionContext,
      closeAfterUse
    )

  private def readBytesFromInputStream[F[_]](is: InputStream,
                                             buf: Array[Byte],
                                             blockingExecutionContext: ExecutionContext)(
      implicit F: Sync[F],
      cs: ContextShift[F]): F[Option[Chunk[Byte]]] =
    cs.evalOn(blockingExecutionContext)(F.delay(blocking(is.read(buf)))).map { numBytes =>
      if (numBytes < 0) None
      else if (numBytes == 0) Some(Chunk.empty)
      else if (numBytes < buf.size) Some(Chunk.bytes(buf.slice(0, numBytes)))
      else Some(Chunk.bytes(buf))
    }

  private def readInputStreamGeneric[F[_]](
      fis: F[InputStream],
      buf: F[Array[Byte]],
      blockingExecutionContext: ExecutionContext,
      closeAfterUse: Boolean)(implicit F: Sync[F], cs: ContextShift[F]): Stream[F, Byte] = {
    def useIs(is: InputStream) =
      Stream
        .eval(buf.flatMap(b => readBytesFromInputStream(is, b, blockingExecutionContext)))
        .repeat
        .unNoneTerminate
        .flatMap(c => Stream.chunk(c))

    if (closeAfterUse)
      Stream.bracket(fis)(is => F.delay(is.close())).flatMap(useIs)
    else
      Stream.eval(fis).flatMap(useIs)
  }

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * Each write operation is performed on the supplied execution context. Writes are
    * blocking so the execution context should be configured appropriately.
    */
  def writeOutputStream[F[_]](fos: F[OutputStream],
                              blockingExecutionContext: ExecutionContext,
                              closeAfterUse: Boolean = true)(
      implicit F: Sync[F],
      cs: ContextShift[F]): Pipe[F, Byte, Unit] =
    s => {
      def useOs(os: OutputStream): Stream[F, Unit] =
        s.chunks.evalMap(c => writeBytesToOutputStream(os, c, blockingExecutionContext))

      def close(os: OutputStream): F[Unit] =
        cs.evalOn(blockingExecutionContext)(F.delay(os.close()))

      val os = if (closeAfterUse) Stream.bracket(fos)(close) else Stream.eval(fos)
      os.flatMap(os =>
        useOs(os).scope ++ Stream.eval(cs.evalOn(blockingExecutionContext)(F.delay(os.flush()))))
    }

  private def writeBytesToOutputStream[F[_]](os: OutputStream,
                                             bytes: Chunk[Byte],
                                             blockingExecutionContext: ExecutionContext)(
      implicit F: Sync[F],
      cs: ContextShift[F]): F[Unit] =
    cs.evalOn(blockingExecutionContext)(F.delay(blocking(os.write(bytes.toArray))))

  //
  // STDIN/STDOUT Helpers

  /** Stream of bytes read asynchronously from standard input. */
  def stdin[F[_]](bufSize: Int, blockingExecutionContext: ExecutionContext)(
      implicit F: Sync[F],
      cs: ContextShift[F]): Stream[F, Byte] =
    readInputStream(F.delay(System.in), bufSize, blockingExecutionContext, false)

  /** Pipe of bytes that writes emitted values to standard output asynchronously. */
  def stdout[F[_]](blockingExecutionContext: ExecutionContext)(
      implicit F: Sync[F],
      cs: ContextShift[F]): Pipe[F, Byte, Unit] =
    writeOutputStream(F.delay(System.out), blockingExecutionContext, false)

  /**
    * Writes this stream to standard output asynchronously, converting each element to
    * a sequence of bytes via `Show` and the given `Charset`.
    *
    * Each write operation is performed on the supplied execution context. Writes are
    * blocking so the execution context should be configured appropriately.
    */
  def stdoutLines[F[_], O](blockingExecutionContext: ExecutionContext,
                           charset: Charset = utf8Charset)(implicit F: Sync[F],
                                                           cs: ContextShift[F],
                                                           show: Show[O]): Pipe[F, O, Unit] =
    _.map(_.show).through(text.encode(charset)).through(stdout(blockingExecutionContext))

  /** Stream of `String` read asynchronously from standard input decoded in UTF-8. */
  def stdinUtf8[F[_]](bufSize: Int, blockingExecutionContext: ExecutionContext)(
      implicit F: Sync[F],
      cs: ContextShift[F]): Stream[F, String] =
    stdin(bufSize, blockingExecutionContext).through(text.utf8Decode)

  /**
    * Pipe that converts a stream of bytes to a stream that will emits a single `java.io.InputStream`,
    * that is closed whenever the resulting stream terminates.
    *
    * If the `close` of resulting input stream is invoked manually, then this will await until the
    * original stream completely terminates.
    *
    * Because all `InputStream` methods block (including `close`), the resulting `InputStream`
    * should be consumed on a different thread pool than the one that is backing the `ConcurrentEffect`.
    *
    * Note that the implementation is not thread safe -- only one thread is allowed at any time
    * to operate on the resulting `java.io.InputStream`.
    */
  def toInputStream[F[_]](implicit F: ConcurrentEffect[F]): Pipe[F, Byte, InputStream] =
    source => Stream.resource(toInputStreamResource(source))

  /**
    * Like [[toInputStream]] but returns a `Resource` rather than a single element stream.
    */
  def toInputStreamResource[F[_]](source: Stream[F, Byte])(
      implicit F: ConcurrentEffect[F]): Resource[F, InputStream] =
    JavaInputOutputStream.toInputStream(source)
}
