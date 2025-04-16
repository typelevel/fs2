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

import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.{Dns, Host, IpAddress, Port, SocketAddress, UnixSocketAddress}
import fs2.concurrent.Channel
import fs2.io.internal.facade

import scala.scalajs.js

private[net] trait IpSocketsProviderCompanionPlatform { self: IpSocketsProvider.type =>

  private[net] def forAsync[F[_]: Async]: IpSocketsProvider[F] =
    new AsyncIpSocketsProvider[F]()(implicitly, Dns.forAsync[F])

  private[net] final class AsyncIpSocketsProvider[F[_]](implicit F: Async[F], F2: Dns[F])
      extends IpSocketsProvider[F] {

    private def setSocketOptions(options: List[SocketOption])(socket: facade.net.Socket): F[Unit] =
      options.traverse_(option => option.key.set(socket, option.value))

    override def connect(
        to: SocketAddress[Host],
        options: List[SocketOption]
    ): Resource[F, Socket[F]] =
      (for {
        sock <- Resource
          .make(
            F.delay(
              new facade.net.Socket(new facade.net.SocketOptions { allowHalfOpen = true })
            )
          )(sock =>
            F.delay {
              if (!sock.destroyed)
                sock.destroy()
            }
          )
          .evalTap(setSocketOptions(options))
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

    override def bind(
        address: SocketAddress[Host],
        options: List[SocketOption]
    ): Resource[F, ServerSocket[F]] =
      (for {
        dispatcher <- Dispatcher.sequential[F]
        channel <- Channel.unbounded[F, facade.net.Socket].toResource
        server <- Resource.make(
          F
            .delay(
              facade.net.createServer(
                new facade.net.ServerOptions {
                  pauseOnConnect = true
                  allowHalfOpen = true
                },
                sock => dispatcher.unsafeRunAndForget(channel.send(sock))
              )
            )
        )(server =>
          F.async[Unit] { cb =>
            if (server.listening)
              F.delay(server.close(e => cb(e.toLeft(()).leftMap(js.JavaScriptException)))) *>
                channel.close.as(None)
            else
              F.delay(cb(Right(()))).as(None)
          }
        )
        ip <- Resource.eval(address.host.resolve[F])
        _ <- F
          .async[Unit] { cb =>
            server.registerOneTimeListener[F, js.Error]("error") { e =>
              cb(Left(js.JavaScriptException(e)))
            } <* F.delay {
              if (ip.isWildcard)
                server.listen(address.port.value, () => cb(Right(())))
              else
                server.listen(address.port.value, ip.toString, () => cb(Right(())))
            }
          }
          .toResource
        info = new SocketInfo[F] {
          def localAddressGen = F.delay {
            val address = server.address()
            if (address.port ne null)
              SocketAddress(IpAddress.fromString(address.address).get, Port.fromInt(address.port).get)
            else
              UnixSocketAddress(address.path)
          }

          def getOption[A](key: SocketOption.Key[A]) =
            F.raiseError(new UnsupportedOperationException)
          def setOption[A](key: SocketOption.Key[A], value: A) =
            F.raiseError(new UnsupportedOperationException)
          def supportedOptions =
            F.raiseError(new UnsupportedOperationException)
        }
        sockets = channel.stream
          .evalTap(setSocketOptions(options))
          .flatMap(sock => Stream.resource(Socket.forAsync(sock)))
      } yield ServerSocket(info, sockets)).adaptError { case IOException(ex) => ex }
  }
}
