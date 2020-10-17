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

import cats._
import cats.effect.{Async, Outcome, Resource, Sync}
import cats.effect.implicits._
import cats.effect.kernel.Deferred
import cats.syntax.all._

import java.io.{InputStream, OutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.Charset

/** Provides various ways to work with streams that perform IO.
  */
package object io {
  private val utf8Charset = Charset.forName("UTF-8")

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
      else if (numBytes < buf.size) Some(Chunk.bytes(buf.slice(0, numBytes)))
      else Some(Chunk.bytes(buf))
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
  )(implicit F: Sync[F]): Pipe[F, Byte, INothing] =
    s => {
      def useOs(os: OutputStream): Stream[F, INothing] =
        s.chunks.foreach(c => F.blocking(os.write(c.toArray)))

      val os =
        if (closeAfterUse) Stream.bracket(fos)(os => F.blocking(os.close()))
        else Stream.eval(fos)
      os.flatMap(os => useOs(os) ++ Stream.exec(F.blocking(os.flush())))
    }

  /** Take a function that emits to an [[java.io.OutputStream OutputStream]] effectfully,
    * and return a stream which, when run, will perform that function and emit
    * the bytes recorded in the OutputStream as an fs2.Stream
    *
    * The stream produced by this will terminate if:
    *   - `f` returns
    *   - `f` calls `OutputStream#close`
    *
    * If none of those happens, the stream will run forever.
    */
  def readOutputStream[F[_]: Async](
      chunkSize: Int
  )(
      f: OutputStream => F[Unit]
  ): Stream[F, Byte] = {
    val mkOutput: Resource[F, (OutputStream, InputStream)] =
      Resource.make(Sync[F].delay {
        val os = new PipedOutputStream()
        val is = new PipedInputStream(os)
        (os: OutputStream, is: InputStream)
      })(ois =>
        Sync[F].blocking {
          // Piped(I/O)Stream implementations cant't throw on close, no need to nest the handling here.
          ois._2.close()
          ois._1.close()
        }
      )

    Stream.resource(mkOutput).flatMap { case (os, is) =>
      Stream.eval(Deferred[F, Option[Throwable]]).flatMap { err =>
        // We need to close the output stream regardless of how `f` finishes
        // to ensure an outstanding blocking read on the input stream completes.
        // In such a case, there's a race between completion of the read
        // stream and finalization of the write stream, so we capture the error
        // that occurs when writing and rethrow it.
        val write = f(os).guaranteeCase((outcome: Outcome[F, Throwable, Unit]) =>
          Sync[F].blocking(os.close()) *> err
            .complete(outcome match {
              case Outcome.Errored(t) => Some(t)
              case _                  => None
            })
            .void
        )
        val read = readInputStream(is.pure[F], chunkSize, closeAfterUse = false)
        read.concurrently(Stream.eval(write)) ++ Stream.eval(err.get).flatMap {
          case None    => Stream.empty
          case Some(t) => Stream.raiseError[F](t)
        }
      }
    }
  }

  //
  // STDIN/STDOUT Helpers

  /** Stream of bytes read asynchronously from standard input. */
  def stdin[F[_]: Sync](bufSize: Int): Stream[F, Byte] =
    readInputStream(Sync[F].blocking(System.in), bufSize, false)

  /** Pipe of bytes that writes emitted values to standard output asynchronously. */
  def stdout[F[_]: Sync]: Pipe[F, Byte, INothing] =
    writeOutputStream(Sync[F].blocking(System.out), false)

  /** Writes this stream to standard output asynchronously, converting each element to
    * a sequence of bytes via `Show` and the given `Charset`.
    *
    * Each write operation is performed on the supplied execution context. Writes are
    * blocking so the execution context should be configured appropriately.
    */
  def stdoutLines[F[_]: Sync, O: Show](
      charset: Charset = utf8Charset
  ): Pipe[F, O, INothing] =
    _.map(_.show).through(text.encode(charset)).through(stdout)

  /** Stream of `String` read asynchronously from standard input decoded in UTF-8. */
  def stdinUtf8[F[_]: Sync](bufSize: Int): Stream[F, String] =
    stdin(bufSize).through(text.utf8Decode)

  /** Pipe that converts a stream of bytes to a stream that will emit a single `java.io.InputStream`,
    * that is closed whenever the resulting stream terminates.
    *
    * If the `close` of resulting input stream is invoked manually, then this will await until the
    * original stream completely terminates.
    *
    * Because all `InputStream` methods block (including `close`), the resulting `InputStream`
    * should be consumed on a different thread pool than the one that is backing the effect.
    *
    * Note that the implementation is not thread safe -- only one thread is allowed at any time
    * to operate on the resulting `java.io.InputStream`.
    */
  def toInputStream[F[_]: Async]: Pipe[F, Byte, InputStream] =
    source => Stream.resource(toInputStreamResource(source))

  /** Like [[toInputStream]] but returns a `Resource` rather than a single element stream.
    */
  def toInputStreamResource[F[_]: Async](
      source: Stream[F, Byte]
  ): Resource[F, InputStream] =
    JavaInputOutputStream.toInputStream(source)
}
