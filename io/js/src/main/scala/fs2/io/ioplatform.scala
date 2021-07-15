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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.internal.jsdeps.node.bufferMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.internal.jsdeps.node.streamMod
import fs2.io.internal.ByteChunkOps._
import fs2.io.internal.EventEmitterOps._

import scala.scalajs.js

private[fs2] trait ioplatform {

  def fromReadable[F[_]](readable: F[streamMod.Readable])(implicit F: Async[F]): Stream[F, Byte] =
    Stream
      .resource(for {
        readable <- Resource.makeCase(readable) {
          case (readable, Resource.ExitCase.Succeeded) => F.delay(readable.destroy())
          case (readable, Resource.ExitCase.Errored(ex)) =>
            F.delay(readable.destroy(js.Error(ex.getMessage())))
          case (readable, Resource.ExitCase.Canceled) => F.delay(readable.destroy())
        }
        dispatcher <- Dispatcher[F]
        queue <- Queue.synchronous[F, Unit].toResource
        ended <- F.deferred[Either[Throwable, Unit]].toResource
        _ <- registerListener0(readable, nodeStrings.readable)(_.on_readable(_, _)) { () =>
          dispatcher.unsafeRunAndForget(queue.offer(()))
        }
        _ <- registerListener0(readable, nodeStrings.end)(_.on_end(_, _)) { () =>
          dispatcher.unsafeRunAndForget(ended.complete(Right(())))
        }
        _ <- registerListener[js.Error](readable, nodeStrings.error)(_.on_error(_, _)) { e =>
          dispatcher.unsafeRunAndForget(ended.complete(Left(js.JavaScriptException(e))))
        }
      } yield (readable, queue, ended))
      .flatMap { case (readable, queue, ended) =>
        Stream.fromQueueUnterminated(queue).interruptWhen(ended) >>
          Stream.evalUnChunk(
            F.delay(
              Option(readable.read().asInstanceOf[bufferMod.global.Buffer])
                .fold(Chunk.empty[Byte])(_.toChunk)
            )
          )
      }

  def fromWritable[F[_]](
      writable: F[streamMod.Writable]
  )(implicit F: Async[F]): Pipe[F, Byte, INothing] =
    in =>
      Stream.eval(writable).flatMap { writable =>
        def go(
            s: Stream[F, Byte]
        ): Pull[F, INothing, Unit] = s.pull.uncons.flatMap {
          case Some((head, tail)) =>
            Pull.eval {
              F.async_[Unit] { cb =>
                writable.write(
                  head.toUint8Array: js.Any,
                  e => cb(e.toLeft(()).leftMap(js.JavaScriptException))
                )
              }
            } >> go(tail)
          case None => Pull.eval(F.delay(writable.end())) >> Pull.done
        }

        go(in).stream.handleErrorWith { ex =>
          Stream.eval(F.delay(writable.destroy(js.Error(ex.getMessage))))
        }.drain
      }

}
