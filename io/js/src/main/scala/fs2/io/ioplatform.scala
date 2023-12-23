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
import fs2.concurrent.Channel
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
  )(thunk: => R)(implicit F: Async[F]): Resource[F, (R, Stream[F, Byte])] = {

    final class Listener {
      private[this] var readable = false
      private[this] var error: Either[Throwable, Boolean] = null
      private[this] var ended = false
      private[this] var callback: Either[Throwable, Boolean] => Unit = null

      def handleReadable(): Unit =
        if (callback eq null) {
          readable = true
        } else {
          val cb = callback
          callback = null
          cb(Right(true))
        }

      def handleEnd(): Unit = {
        ended = true
        if (!readable && (callback ne null)) {
          callback(Right(false))
        }
      }

      def handleError(e: js.Error): Unit = {
        error = Left(js.JavaScriptException(e))
        if (callback ne null) {
          callback(error)
        }
      }

      private[this] def next: F[Boolean] = F.async { cb =>
        F.delay {
          if (error ne null) {
            cb(error)
            None
          } else if (readable) {
            readable = false
            cb(Right(true))
            None
          } else if (ended) {
            cb(Right(false))
            None
          } else {
            callback = cb
            Some(F.delay { callback = null })
          }
        }
      }

      def readableEvents: Stream[F, Unit] = {
        def go: Pull[F, Unit, Unit] =
          Pull.eval(next).flatMap { continue =>
            if (continue)
              Pull.outUnit >> go
            else
              Pull.done
          }

        go.streamNoScope
      }

    }

    Resource
      .eval(F.delay(new Listener))
      .flatMap { listener =>
        Resource
          .makeCase {
            F.delay {
              val readable = thunk
              readable.on("readable", () => listener.handleReadable())
              readable.once("error", listener.handleError(_))
              readable.once("end", () => listener.handleEnd())
              readable
            }
          } {
            case (readable, Resource.ExitCase.Succeeded) =>
              F.delay {
                if (!readable.readableEnded & destroyIfNotEnded)
                  readable.destroy()
              }
            case (readable, Resource.ExitCase.Errored(_)) =>
              // tempting, but don't propagate the error!
              // that would trigger a unhandled Node.js error that circumvents FS2/CE error channels
              F.delay(readable.destroy())
            case (readable, Resource.ExitCase.Canceled) =>
              if (destroyIfCanceled)
                F.delay(readable.destroy())
              else
                F.unit
          }
          .fproduct { readable =>
            listener.readableEvents.adaptError { case IOException(ex) => ex } >>
              Stream.evalUnChunk(
                F.delay(Option(readable.read()).fold(Chunk.empty[Byte])(Chunk.uint8Array(_)))
              )
          }
      }
      .adaptError { case IOException(ex) => ex }
  }

  /** `Pipe` that converts a stream of bytes to a stream that will emit a single `Readable`,
    * that ends whenever the resulting stream terminates.
    */
  def toReadable[F[_]: Async]: Pipe[F, Byte, Readable] =
    in => Stream.resource(toReadableResource(in))

  /** Like [[toReadable]] but returns a `Resource` rather than a single element stream.
    */
  def toReadableResource[F[_]](s: Stream[F, Byte])(implicit F: Async[F]): Resource[F, Readable] =
    mkDuplex(s)
      .flatMap { case (duplex, out) =>
        out
          .concurrently(
            Stream.eval(
              F.async_[Unit](cb =>
                duplex.end { e =>
                  cb(e.filterNot(_ == null).toLeft(()).leftMap(js.JavaScriptException))
                }
              )
            )
          )
          .compile
          .drain
          .background
          .as(duplex)
      }
      .adaptError { case IOException(ex) => ex }

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
          val writes = in.chunks.foreach { chunk =>
            F.async[Unit] { cb =>
              F.delay {
                writable.write(
                  chunk.toUint8Array,
                  e => cb(e.filterNot(_ == null).toLeft(()).leftMap(js.JavaScriptException))
                )
                Some(F.delay(writable.destroy()))
              }
            }
          }

          val end =
            if (endAfterUse)
              Stream.exec {
                F.async[Unit] { cb =>
                  F.delay(
                    writable.end(e =>
                      cb(e.filterNot(_ == null).toLeft(()).leftMap(js.JavaScriptException))
                    )
                  ).as(Some(F.unit))
                }
              }
            else Stream.empty

          (writes ++ end).onFinalizeCase[F] {
            case Resource.ExitCase.Succeeded =>
              F.unit
            case Resource.ExitCase.Errored(_) | Resource.ExitCase.Canceled =>
              // tempting, but don't propagate the error!
              // that would trigger a unhandled Node.js error that circumvents FS2/CE error channels
              F.delay(writable.destroy())
          }
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
      readDispatcher <- Dispatcher.sequential[F]
      writeDispatcher <- Dispatcher.sequential[F]
      errorDispatcher <- Dispatcher.sequential[F]
      readQueue <- Queue.bounded[F, Option[Chunk[Byte]]](1).toResource
      writeChannel <- Channel.synchronous[F, Chunk[Byte]].toResource
      interrupt <- F.deferred[Either[Throwable, Unit]].toResource
      duplex <- Resource.make {
        F.delay {
          new facade.stream.Duplex(
            new facade.stream.DuplexOptions {

              var autoDestroy = false

              var read = { readable =>
                readDispatcher.unsafeRunAndForget(
                  readQueue.take.flatMap { chunk =>
                    F.delay(readable.push(chunk.map(_.toUint8Array).orNull)).void
                  }
                )
              }

              var write = { (_, chunk, _, cb) =>
                writeDispatcher.unsafeRunAndForget(
                  writeChannel.send(Chunk.uint8Array(chunk)) *> F.delay(cb(null))
                )
              }

              var `final` = { (_, cb) =>
                writeDispatcher.unsafeRunAndForget(
                  writeChannel.close *> F.delay(cb(null))
                )
              }

              var destroy = { (_, err, cb) =>
                errorDispatcher.unsafeRunAndForget {
                  interrupt
                    .complete(
                      Option(err).map(js.JavaScriptException(_)).toLeft(())
                    ) *> F.delay(cb(null))
                }
              }
            }
          )
        }
      } { duplex =>
        F.delay {
          if (!duplex.readableEnded | !duplex.writableEnded)
            duplex.destroy()
        }
      }
      drainIn = in.enqueueNoneTerminatedChunks(readQueue).drain
      out = writeChannel.stream.unchunks
    } yield (
      duplex,
      drainIn.merge(out).interruptWhen(interrupt).adaptError { case IOException(ex) => ex }
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
