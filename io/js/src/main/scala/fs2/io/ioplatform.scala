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

import cats.effect.SyncIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.internal.jsdeps.std
import fs2.internal.jsdeps.node.bufferMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.internal.jsdeps.node.streamMod
import fs2.io.internal.ByteChunkOps._
import fs2.io.internal.EventEmitterOps._
import fs2.io.internal.ThrowableOps._

import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js.|

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
      // Implementation Note: why suspend in `SyncIO` and then `unsafeRunSync()` inside `F.delay`?
      // In many cases creating a `Readable` starts async side-effects (e.g. negotiating TLS handshake or opening a file handle).
      // Furthermore, these side-effects will invoke the listeners we register to the `Readable`.
      // Therefore, it is critical that the listeners are registered to the `Readable` _before_ these async side-effects occur:
      // in other words, before we next yield (cede) to the event loop. Because an arbitrary effect `F` (particularly `IO`) may cede at any time,
      // our only recourse is to suspend the entire creation/listener registration process within a single atomic `delay`.
      readableResource = for {
        readable <- Resource.makeCase(SyncIO(thunk).map(_.asInstanceOf[streamMod.Readable])) {
          case (readable, Resource.ExitCase.Succeeded) =>
            SyncIO {
              if (!readable.readableEnded & destroyIfNotEnded)
                readable.destroy()
            }
          case (readable, Resource.ExitCase.Errored(ex)) =>
            SyncIO(readable.destroy(ex.toJSError))
          case (readable, Resource.ExitCase.Canceled) =>
            if (destroyIfCanceled)
              SyncIO(readable.destroy())
            else
              SyncIO.unit
        }
        _ <- registerListener0(readable, nodeStrings.readable)(_.on_readable(_, _)) { () =>
          dispatcher.unsafeRunAndForget(queue.offer(Some(())))
        }(SyncIO.syncForSyncIO)
        _ <- registerListener0(readable, nodeStrings.end)(_.on_end(_, _)) { () =>
          dispatcher.unsafeRunAndForget(queue.offer(None))
        }(SyncIO.syncForSyncIO)
        _ <- registerListener0(readable, nodeStrings.close)(_.on_close(_, _)) { () =>
          dispatcher.unsafeRunAndForget(queue.offer(None))
        }(SyncIO.syncForSyncIO)
        _ <- registerListener[std.Error](readable, nodeStrings.error)(_.on_error(_, _)) { e =>
          dispatcher.unsafeRunAndForget(error.complete(js.JavaScriptException(e)))
        }(SyncIO.syncForSyncIO)
      } yield readable
      readable <- Resource
        .make(F.delay {
          readableResource.allocated.unsafeRunSync()
        }) { case (_, close) => close.to[F] }
        .map(_._1)
      stream =
        (Stream
          .fromQueueNoneTerminated(queue)
          .concurrently(Stream.eval(error.get.flatMap(F.raiseError[Unit]))) >>
          Stream
            .evalUnChunk(
              F.delay(
                Option(readable.read().asInstanceOf[bufferMod.global.Buffer])
                  .fold(Chunk.empty[Byte])(_.toChunk)
              )
            )).adaptError { case IOException(ex) => ex }
    } yield (readable.asInstanceOf[R], stream)).adaptError { case IOException(ex) => ex }

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
                  duplex.asInstanceOf[streamMod.Writable].end(() => cb(Right(())))
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
  )(implicit F: Async[F]): Pipe[F, Byte, INothing] =
    in =>
      Stream
        .eval(writable.map(_.asInstanceOf[streamMod.Writable]))
        .flatMap { writable =>
          def go(
              s: Stream[F, Byte]
          ): Pull[F, INothing, Unit] = s.pull.uncons.flatMap {
            case Some((head, tail)) =>
              Pull.eval {
                F.async_[Unit] { cb =>
                  writable.write(
                    head.toUint8Array: js.Any,
                    e => cb(e.toLeft(()).leftMap(js.JavaScriptException))
                  ): @nowarn
                }
              } >> go(tail)
            case None =>
              if (endAfterUse)
                Pull.eval(F.async_[Unit](cb => writable.end(() => cb(Right(())))))
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
          new streamMod.Duplex(
            streamMod
              .DuplexOptions()
              .setAutoDestroy(false)
              .setRead { (duplex, _) =>
                val readable = duplex.asInstanceOf[streamMod.Readable]
                dispatcher.unsafeRunAndForget(
                  readQueue.take.attempt.flatMap {
                    case Left(ex) =>
                      F.delay(readable.destroy(ex.toJSError))
                    case Right(chunk) =>
                      F.delay(readable.push(chunk.map(_.toUint8Array).orNull)).void
                  }
                )
              }
              .setWrite { (_, chunk, _, cb) =>
                dispatcher.unsafeRunAndForget(
                  writeQueue
                    .offer(Some(Chunk.uint8Array(chunk.asInstanceOf[Uint8Array])))
                    .attempt
                    .flatMap(e =>
                      F.delay(
                        cb(
                          e.left.toOption
                            .fold[std.Error | Null](null)(_.toJSError)
                        )
                      )
                    )
                )
              }
              .setFinal { (_, cb) =>
                dispatcher.unsafeRunAndForget(
                  writeQueue
                    .offer(None)
                    .attempt
                    .flatMap(e =>
                      F.delay(
                        cb(
                          e.left.toOption
                            .fold[std.Error | Null](null)(_.toJSError)
                        )
                      )
                    )
                )
              }
              .setDestroy { (_, err, cb) =>
                dispatcher.unsafeRunAndForget {
                  error
                    .complete(
                      Option(err)
                        .fold[Exception](new StreamDestroyedException)(js.JavaScriptException(_))
                    )
                    .attempt
                    .flatMap(e =>
                      F.delay(
                        cb(
                          e.left.toOption
                            .fold[std.Error | Null](null)(_.toJSError)
                        )
                      )
                    )

                }
              }
          )
        }
      } { duplex =>
        F.delay {
          val readable = duplex.asInstanceOf[streamMod.Readable]
          val writable = duplex.asInstanceOf[streamMod.Writable]
          if (!readable.readableEnded | !writable.writableEnded)
            readable.destroy()
        }
      }
      drainIn = in.enqueueNoneTerminatedChunks(readQueue).drain
      out = Stream
        .fromQueueNoneTerminatedChunk(writeQueue)
        .concurrently(Stream.eval(error.get.flatMap(F.raiseError[Unit])))
    } yield (
      duplex.asInstanceOf[Duplex],
      drainIn.merge(out).adaptError { case IOException(ex) => ex }
    )
}
