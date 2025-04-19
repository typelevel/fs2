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
import com.comcast.ip4s.{IpAddress, GenSocketAddress, Port, SocketAddress, UnixSocketAddress}
import fs2.concurrent.Channel
import fs2.io.internal.facade

import scala.scalajs.js

private[net] abstract class AsyncSocketsProvider[F[_]](implicit F: Async[F]) {

  private def setSocketOptions(options: List[SocketOption])(socket: facade.net.Socket): F[Unit] =
    options.traverse_(option => option.key.set(socket, option.value))

  protected def connectIpOrUnix(
      to: Either[SocketAddress[IpAddress], UnixSocketAddress],
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
      localAddressGen = F.delay {
        (to match {
          case Left(_) =>
            SocketAddress(
              IpAddress.fromString(sock.localAddress.get).get,
              Port.fromInt(sock.localPort.get).get
            )
          case Right(_) => UnixSocketAddress("")
        }): GenSocketAddress
      }
      remoteAddressGen = F.delay {
        (to match {
          case Left(_) =>
            SocketAddress(
              IpAddress.fromString(sock.remoteAddress.get).get,
              Port.fromInt(sock.remotePort.get).get
            )
          case Right(addr) => addr
        }): GenSocketAddress
      }
      socket <- Socket.forAsync(sock, localAddressGen, remoteAddressGen)
      _ <- F
        .async[Unit] { cb =>
          sock
            .registerOneTimeListener[F, js.Error]("error") { error =>
              cb(Left(js.JavaScriptException(error)))
            } <* F.delay {
            to match {
              case Left(addr) =>
                sock.connect(addr.port.value, addr.host.toString, () => cb(Right(())))
              case Right(addr) =>
                sock.connect(addr.path, () => cb(Right(())))
            }
          }
        }
        .toResource
    } yield socket).adaptError { case IOException(ex) => ex }

  protected def bindIpOrUnix(
      address: Either[SocketAddress[IpAddress], UnixSocketAddress],
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
      _ <- F
        .async[Unit] { cb =>
          server.registerOneTimeListener[F, js.Error]("error") { e =>
            cb(Left(js.JavaScriptException(e)))
          } <* F.delay {
            address match {
              case Left(addr) =>
                if (addr.host.isWildcard)
                  server.listen(addr.port.value, () => cb(Right(())))
                else
                  server.listen(addr.port.value, addr.host.toString, () => cb(Right(())))
              case Right(addr) =>
                server.listen(addr.path, () => cb(Right(())))
            }
          }
        }
        .toResource
      info = new SocketInfo[F] {
        def localAddressGen = F.delay {
          address match {
            case Left(_) =>
              val addr = server.address().get
              SocketAddress(IpAddress.fromString(addr.address).get, Port.fromInt(addr.port).get)
            case Right(addr) => addr
          }
        }
        def localAddress = localAddressGen.map(_.asIpUnsafe)
        private def raiseOptionError[A]: F[A] =
          F.raiseError(
            new UnsupportedOperationException(
              "Node.js server sockets do not support socket options"
            )
          )
        def getOption[A](key: SocketOption.Key[A]) = raiseOptionError
        def setOption[A](key: SocketOption.Key[A], value: A) = raiseOptionError
        def supportedOptions = F.pure(Set.empty)
      }
      sockets = channel.stream
        .evalTap(setSocketOptions(options))
        .flatMap { sock =>
          val localAddressGen = F.delay {
            (address match {
              case Left(_) =>
                SocketAddress(
                  IpAddress.fromString(sock.localAddress.get).get,
                  Port.fromInt(sock.localPort.get).get
                )
              case Right(addr) => addr
            }): GenSocketAddress
          }
          val remoteAddressGen = F.delay {
            (address match {
              case Left(_) =>
                SocketAddress(
                  IpAddress.fromString(sock.remoteAddress.get).get,
                  Port.fromInt(sock.remotePort.get).get
                )
              case Right(_) => UnixSocketAddress("")
            }): GenSocketAddress
          }
          Stream.resource(Socket.forAsync(sock, localAddressGen, remoteAddressGen))
        }
      serverSocket <- Resource.eval(ServerSocket(info, sockets))
    } yield serverSocket).adaptError { case IOException(ex) => ex }
}
