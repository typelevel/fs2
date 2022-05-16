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
package io.net.unixsocket

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.io.net.Socket
import fs2.io.internal.facade

import scala.scalajs.js

private[unixsocket] trait UnixSocketsCompanionPlatform {

  implicit def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] =
    new UnixSockets[F] {

      override def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
        Resource
          .eval(for {
            socket <- F.delay(
              new facade.Socket(new facade.SocketOptions { allowHalfOpen = true })
            )
            _ <- F.async_[Unit] { cb =>
              socket.connect(address.path, () => cb(Right(())))
            }
          } yield socket)
          .flatMap(Socket.forAsync[F])

      override def server(
          address: UnixSocketAddress,
          deleteIfExists: Boolean,
          deleteOnClose: Boolean
      ): fs2.Stream[F, Socket[F]] =
        for {
          dispatcher <- Stream.resource(Dispatcher[F])
          queue <- Stream.eval(Queue.unbounded[F, facade.Socket])
          errored <- Stream.eval(F.deferred[js.JavaScriptException])
          server <- Stream.bracket(
            F.delay {
              facade.net.createServer(
                new facade.ServerOptions {
                  pauseOnConnect = true
                  allowHalfOpen = true
                },
                sock => dispatcher.unsafeRunAndForget(queue.offer(sock))
              )
            }
          )(server =>
            F.async_[Unit] { cb =>
              if (server.listening) {
                server.close(e => cb(e.toLeft(()).leftMap(js.JavaScriptException)))
                ()
              } else
                cb(Right(()))
            }
          )
          _ <- Stream
            .resource(
              server.registerListener[F, js.Error]("error", dispatcher) { e =>
                errored.complete(js.JavaScriptException(e)).void
              }
            )
            .concurrently(Stream.eval(errored.get.flatMap(F.raiseError[Unit])))
          _ <- Stream.bracket(
            if (deleteIfExists) Files[F].deleteIfExists(Path(address.path)).void else F.unit
          )(_ => if (deleteOnClose) Files[F].deleteIfExists(Path(address.path)).void else F.unit)
          _ <- Stream.eval(
            F.async_[Unit] { cb =>
              server.listen(address.path, () => cb(Right(())))
            }
          )
          socket <- Stream
            .fromQueueUnterminated(queue)
            .flatMap(sock => Stream.resource(Socket.forAsync(sock)))
        } yield socket

    }

}
