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
package net

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.Host
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.io.internal.facade

import scala.scalajs.js

private[net] trait SocketGroupCompanionPlatform { self: SocketGroup.type =>

  private[net] def forAsync[F[_]: Async]: SocketGroup[F] = new AsyncSocketGroup[F]

  private[net] final class AsyncSocketGroup[F[_]](implicit F: Async[F])
      extends AbstractAsyncSocketGroup[F] {

    private def setSocketOptions(options: List[SocketOption])(socket: facade.Socket): F[Unit] =
      options.traverse_(option => option.key.set(socket, option.value))

    override def client(
        to: SocketAddress[Host],
        options: List[SocketOption]
    ): Resource[F, Socket[F]] =
      (for {
        sock <- F
          .delay(
            new facade.Socket(new facade.SocketOptions { allowHalfOpen = true })
          )
          .flatTap(setSocketOptions(options))
          .toResource
        socket <- Socket.forAsync(sock)
        _ <- F
          .async[Unit] { cb =>
            sock
              .registerOneTimeListener[F, js.Error]("error") { error =>
                cb(Left(js.JavaScriptException(error)))
              } <* F.delay {
              sock.connect(to.port.value, to.host.toString, () => cb(Right(())))
            }
          }
          .toResource
      } yield socket).adaptError { case IOException(ex) => ex }

    override def serverResource(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption]
    ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
      (for {
        dispatcher <- Dispatcher[F]
        queue <- Queue.unbounded[F, Option[facade.Socket]].toResource
        server <- Resource.make(
          F
            .delay(
              facade.net.createServer(
                new facade.ServerOptions {
                  pauseOnConnect = true
                  allowHalfOpen = true
                },
                sock => dispatcher.unsafeRunAndForget(queue.offer(Some(sock)))
              )
            )
        )(server =>
          F.async[Unit] { cb =>
            if (server.listening)
              F.delay(server.close(e => cb(e.toLeft(()).leftMap(js.JavaScriptException)))) >> queue
                .offer(None)
                .as(None)
            else
              F.delay(cb(Right(()))).as(None)
          }
        )
        _ <- F
          .async[Unit] { cb =>
            server.registerOneTimeListener[F, js.Error]("error") { e =>
              cb(Left(js.JavaScriptException(e)))
            } <* F.delay {
              address match {
                case Some(host) =>
                  server.listen(port.fold(0)(_.value), host.toString, () => cb(Right(())))
                case None =>
                  server.listen(port.fold(0)(_.value), () => cb(Right(())))
              }

            }

          }
          .toResource
        ipAddress <- F.delay {
          val info = server.address()
          SocketAddress(IpAddress.fromString(info.address).get, Port.fromInt(info.port).get)
        }.toResource
        sockets = Stream
          .fromQueueNoneTerminated(queue)
          .evalTap(setSocketOptions(options))
          .flatMap(sock => Stream.resource(Socket.forAsync(sock)))
      } yield (ipAddress, sockets)).adaptError { case IOException(ex) => ex }

  }

}
