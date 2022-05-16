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
package io

import cats.Show
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.io.internal.MicrotaskExecutor
import fs2.io.internal.ThrowableOps._
import fs2.io.internal.facade

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.annotation.nowarn
import scala.scalajs.js

private[fs2] trait ioplatform {

  @deprecated("Use suspendReadableAndRead instead", "3.1.4")
  def readReadable[F[_]](
      readable: F[Readable],
      destroyIfNotEnded: Boolean = true,
      destroyIfCanceled: Boolean = true
  )(implicit
      F: Async[F]
  ): Stream[F, Byte] = Stream
    .eval(readable)
    .flatMap(r => Stream.resource(suspendReadableAndRead(destroyIfNotEnded, destroyIfCanceled)(r)))
    .flatMap(_._2)

  /** Suspends the creation of a `Readable` and a `Stream` that reads all bytes from that `Readable`.
    */
  def suspendReadableAndRead[F[_], R <: Readable](
      destroyIfNotEnded: Boolean = true,
      destroyIfCanceled: Boolean = true
  )(thunk: => R)(implicit F: Async[F]): Resource[F, (R, Stream[F, Byte])] =
    (for {
      dispatcher <- Dispatcher[F]
      queue <- Queue.synchronous[F, Option[Unit]].toResource
      error <- F.deferred[Throwable].toResource
      readableResource = for {
        readable <- Resource.makeCase(F.delay(thunk)) {
          case (readable, Resource.ExitCase.Succeeded) =>
            F.delay {
              if (!readable.readableEnded & destroyIfNotEnded)
                readable.destroy()
            }
          case (readable, Resource.ExitCase.Errored(ex)) =>
            F.delay(readable.destroy(ex.toJSError))
          case (readable, Resource.ExitCase.Canceled) =>
            if (destroyIfCanceled)
              F.delay(readable.destroy())
            else
              F.unit
        }
        _ <- readable.registerListener[F, Any]("readable", dispatcher)(_ => queue.offer(Some(())))
        _ <- readable.registerListener[F, Any]("end", dispatcher)(_ => queue.offer(None))
        _ <- readable.registerListener[F, Any]("close", dispatcher)(_ => queue.offer(None))
        _ <- readable.registerListener[F, js.Error]("error", dispatcher) { e =>
          error.complete(js.JavaScriptException(e)).void
        }
      } yield readable
      // Implementation note: why run on the MicrotaskExecutor?
      // In many cases creating a `Readable` starts async side-effects (e.g. negotiating TLS handshake or opening a file handle).
      // Furthermore, these side-effects will invoke the listeners we register to the `Readable`.
      // Therefore, it is critical that the listeners are registered to the `Readable` _before_ these async side-effects occur:
      // in other words, before we next yield (cede) to the event loop. Because an arbitrary effect `F` (particularly `IO`) may cede at any time,
      // our only recourse is to run the entire creation/listener registration process on the microtask executor.
      readable <- readableResource.evalOn(MicrotaskExecutor)
      stream =
        (Stream
          .fromQueueNoneTerminated(queue)
          .concurrently(Stream.eval(error.get.flatMap(F.raiseError[Unit]))) >>
          Stream
            .evalUnChunk(
              F.delay(
                Option(readable.read())
                  .fold(Chunk.empty[Byte])(Chunk.uint8Array)
              )
            )).adaptError { case IOException(ex) => ex }
    } yield (readable, stream)).adaptError { case IOException(ex) => ex }

  /** `Pipe` that converts a stream of bytes to a stream that will emit a single `Readable`,
    * that ends whenever the resulting stream terminates.
    */
  def toReadable[F[_]](implicit F: Async[F]): Pipe[F, Byte, Readable] =
    in =>
      Stream
        .resource(mkDuplex(in))
        .flatMap { case (duplex, out) =>
          Stream
            .emit(duplex)
            .merge(out.drain)
            .concurrently(
              Stream.eval(
                F.async_[Unit](cb =>
                  duplex.end(e => cb(e.toLeft(()).leftMap(js.JavaScriptException)))
                )
              )
            )
        }
        .adaptError { case IOException(ex) => ex }

  /** Like [[toReadable]] but returns a `Resource` rather than a single element stream.
    */
  def toReadableResource[F[_]: Async](s: Stream[F, Byte]): Resource[F, Readable] =
    s.through(toReadable).compile.resource.lastOrError

  /** Writes all bytes to the specified `Writable`.
    */
  def writeWritable[F[_]](
      writable: F[Writable],
      endAfterUse: Boolean = true
  )(implicit F: Async[F]): Pipe[F, Byte, Nothing] =
    in =>
      Stream
        .eval(writable)
        .flatMap { writable =>
          def go(
              s: Stream[F, Byte]
          ): Pull[F, Nothing, Unit] = s.pull.uncons.flatMap {
            case Some((head, tail)) =>
              Pull.eval {
                F.async_[Unit] { cb =>
                  writable.write(
                    head.toUint8Array,
                    e => cb(e.toLeft(()).leftMap(js.JavaScriptException))
                  )
                  ()
                }
              } >> go(tail)
            case None =>
              if (endAfterUse)
                Pull.eval(
                  F.async_[Unit] { cb =>
                    writable.end(e => cb(e.toLeft(()).leftMap(js.JavaScriptException)))
                  }
                )
              else
                Pull.done
          }

          go(in).stream.handleErrorWith { ex =>
            Stream.eval(F.delay(writable.destroy(ex.toJSError)))
          }.drain
        }
        .adaptError { case IOException(ex) => ex }

  /** Take a function that emits to a `Writable` effectfully,
    * and return a stream which, when run, will perform that function and emit
    * the bytes recorded in the `Writable` as an fs2.Stream
    */
  def readWritable[F[_]: Async](f: Writable => F[Unit]): Stream[F, Byte] =
    Stream.empty.through(toDuplexAndRead(f))

  /** Take a function that reads and writes from a `Duplex` effectfully,
    * and return a pipe which, when run, will perform that function,
    * write emitted bytes to the duplex, and read emitted bytes from the duplex
    */
  def toDuplexAndRead[F[_]: Async](f: Duplex => F[Unit]): Pipe[F, Byte, Byte] =
    in =>
      Stream.resource(mkDuplex(in)).flatMap { case (duplex, out) =>
        Stream.eval(f(duplex)).drain.merge(out)
      }

  private[io] def mkDuplex[F[_]](
      in: Stream[F, Byte]
  )(implicit F: Async[F]): Resource[F, (Duplex, Stream[F, Byte])] =
    for {
      dispatcher <- Dispatcher[F]
      readQueue <- Queue.bounded[F, Option[Chunk[Byte]]](1).toResource
      writeQueue <- Queue.synchronous[F, Option[Chunk[Byte]]].toResource
      error <- F.deferred[Throwable].toResource
      duplex <- Resource.make {
        F.delay {
          new facade.Duplex(
            new facade.DuplexOptions {

              var autoDestroy = false

              var read = { readable =>
                dispatcher.unsafeRunAndForget(
                  readQueue.take.flatMap { chunk =>
                    F.delay(readable.push(chunk.map(_.toUint8Array).orNull)).void
                  }
                )
              }

              var write = { (_, chunk, _, cb) =>
                dispatcher.unsafeRunAndForget(
                  writeQueue.offer(Some(Chunk.uint8Array(chunk))) *> F.delay(cb(null))
                )
              }

              var `final` = { (_, cb) =>
                dispatcher.unsafeRunAndForget(
                  writeQueue.offer(None) *> F.delay(cb(null))
                )
              }

              var destroy = { (_, err, cb) =>
                dispatcher.unsafeRunAndForget {
                  error
                    .complete(
                      Option(err)
                        .fold[Exception](new StreamDestroyedException)(js.JavaScriptException(_))
                    ) *> F.delay(cb(null))
                }
              }
            }
          )
        }
      } { duplex =>
        F.delay {
          if (!duplex.readableEnded | !duplex.writableEnded)
            duplex.destroy(null)
        }
      }
      drainIn = in.enqueueNoneTerminatedChunks(readQueue).drain
      out = Stream
        .fromQueueNoneTerminatedChunk(writeQueue)
        .concurrently(Stream.eval(error.get.flatMap(F.raiseError[Unit])))
    } yield (
      duplex,
      drainIn.merge(out).adaptError { case IOException(ex) => ex }
    )

  /** Stream of bytes read asynchronously from standard input. */
  def stdin[F[_]: Async]: Stream[F, Byte] = stdinAsync

  private def stdinAsync[F[_]: Async]: Stream[F, Byte] =
    Stream
      .resource(suspendReadableAndRead(false, false)(facade.process.stdin))
      .flatMap(_._2)

  /** Stream of bytes read asynchronously from standard input.
    * Takes a dummy `Int` parameter for source-compatibility with JVM.
    */
  @nowarn("msg=never used")
  def stdin[F[_]: Async](ignored: Int): Stream[F, Byte] = stdin

  /** Pipe of bytes that writes emitted values to standard output asynchronously. */
  def stdout[F[_]: Async]: Pipe[F, Byte, Nothing] = stdoutAsync

  private def stdoutAsync[F[_]: Async]: Pipe[F, Byte, Nothing] =
    writeWritable(facade.process.stdout.pure, false)

  /** Pipe of bytes that writes emitted values to standard error asynchronously. */
  def stderr[F[_]: Async]: Pipe[F, Byte, Nothing] =
    writeWritable(facade.process.stderr.pure, false)

  /** Writes this stream to standard output asynchronously, converting each element to
    * a sequence of bytes via `Show` and the given `Charset`.
    */
  def stdoutLines[F[_]: Async, O: Show](
      charset: Charset = StandardCharsets.UTF_8
  ): Pipe[F, O, Nothing] =
    _.map(_.show).through(text.encode(charset)).through(stdoutAsync)

  /** Stream of `String` read asynchronously from standard input decoded in UTF-8. */
  def stdinUtf8[F[_]: Async]: Stream[F, String] =
    stdinAsync.through(text.utf8.decode)

  /** Stream of `String` read asynchronously from standard input decoded in UTF-8.
    * Takes a dummy `Int` parameter for source-compatibility with JVM.
    */
  @nowarn("msg=never used")
  def stdinUtf8[F[_]: Async](ignored: Int): Stream[F, String] =
    stdinAsync.through(text.utf8.decode)

  // Copied JVM implementations, for bincompat

  /** Stream of bytes read asynchronously from standard input. */
  private[io] def stdin[F[_]: Sync](bufSize: Int): Stream[F, Byte] = stdinSync(bufSize)

  private def stdinSync[F[_]: Sync](bufSize: Int): Stream[F, Byte] =
    readInputStream(Sync[F].blocking(System.in), bufSize, false)

  /** Pipe of bytes that writes emitted values to standard output asynchronously. */
  private[io] def stdout[F[_]: Sync]: Pipe[F, Byte, Nothing] = stdoutSync

  private def stdoutSync[F[_]: Sync]: Pipe[F, Byte, Nothing] =
    writeOutputStream(Sync[F].blocking(System.out), false)

  /** Pipe of bytes that writes emitted values to standard error asynchronously. */
  private[io] def stderr[F[_]: Sync]: Pipe[F, Byte, Nothing] =
    writeOutputStream(Sync[F].blocking(System.err), false)

  /** Writes this stream to standard output asynchronously, converting each element to
    * a sequence of bytes via `Show` and the given `Charset`.
    */
  private[io] def stdoutLines[F[_]: Sync, O: Show](
      charset: Charset
  ): Pipe[F, O, Nothing] =
    _.map(_.show).through(text.encode(charset)).through(stdoutSync)

  /** Stream of `String` read asynchronously from standard input decoded in UTF-8. */
  private[io] def stdinUtf8[F[_]: Sync](bufSize: Int): Stream[F, String] =
    stdinSync(bufSize).through(text.utf8.decode)

}
