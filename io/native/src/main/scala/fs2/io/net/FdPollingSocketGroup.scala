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

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.io.internal.NativeUtil._
import fs2.io.internal.SocketHelpers
import fs2.io.internal.syssocket._

import scala.scalanative.libc.errno._
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.sys.socket.{bind => _, connect => _, accept => _, _}
import scala.scalanative.posix.unistd._

private final class FdPollingSocketGroup[F[_]: Dns: LiftIO](implicit F: Async[F])
    extends SocketGroup[F] {

  def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[F, Socket[F]] = for {
    poller <- Resource.eval(fileDescriptorPoller[F])
    address <- Resource.eval(to.resolve)
    ipv4 = address.host.isInstanceOf[Ipv4Address]
    fd <- SocketHelpers.openNonBlocking(if (ipv4) AF_INET else AF_INET6, SOCK_STREAM)
    _ <- Resource.eval(options.traverse(so => SocketHelpers.setOption(fd, so.key, so.value)))
    handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
    _ <- Resource.eval {
      handle
        .pollWriteRec(false) { connected =>
          if (connected) SocketHelpers.checkSocketError[IO](fd).as(Either.unit)
          else
            IO {
              SocketHelpers.toSockaddr(address) { (addr, len) =>
                if (connect(fd, addr, len) < 0) {
                  val e = errno
                  if (e == EINPROGRESS)
                    Left(true) // we will be connected when we unblock
                  else
                    throw errnoToThrowable(e)
                } else
                  Either.unit
              }
            }
        }
        .to
    }
    socket <- FdPollingSocket[F](
      fd,
      handle,
      SocketHelpers.getLocalAddress(fd, ipv4),
      F.pure(address)
    )
  } yield socket

  def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Stream[F, Socket[F]] =
    Stream.resource(serverResource(address, port, options)).flatMap(_._2)

  def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] = for {
    poller <- Resource.eval(fileDescriptorPoller[F])
    address <- Resource.eval(address.fold(IpAddress.loopback)(_.resolve))
    ipv4 = address.isInstanceOf[Ipv4Address]
    fd <- SocketHelpers.openNonBlocking(if (ipv4) AF_INET else AF_INET6, SOCK_STREAM)
    handle <- poller.registerFileDescriptor(fd, true, false).mapK(LiftIO.liftK)
    _ <- Resource.eval {
      F.delay {
        val socketAddress = SocketAddress(address, port.getOrElse(port"0"))
        SocketHelpers.toSockaddr(socketAddress) { (addr, len) =>
          guard_(bind(fd, addr, len))
        }
      } *> F.delay(guard_(listen(fd, 0)))
    }

    sockets = Stream
      .resource {
        val accepted = for {
          addrFd <- Resource.makeFull[F, (SocketAddress[IpAddress], Int)] { poll =>
            poll {
              handle
                .pollReadRec(()) { _ =>
                  IO {
                    SocketHelpers.allocateSockaddr { (addr, len) =>
                      val clientFd =
                        if (LinktimeInfo.isLinux)
                          guard(accept4(fd, addr, len, SOCK_NONBLOCK))
                        else
                          guard(accept(fd, addr, len))

                      if (clientFd >= 0) {
                        val address = SocketHelpers.toSocketAddress(addr, ipv4)
                        Right((address, clientFd))
                      } else
                        Left(())
                    }
                  }
                }
                .to
            }
          }(addrFd => F.delay(guard_(close(addrFd._2))))
          (address, fd) = addrFd
          _ <-
            if (!LinktimeInfo.isLinux)
              Resource.eval(setNonBlocking(fd))
            else Resource.unit[F]
          handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
          socket <- FdPollingSocket[F](
            fd,
            handle,
            SocketHelpers.getLocalAddress(fd, ipv4),
            F.pure(address)
          )
        } yield socket

        accepted.attempt.map(_.toOption)
      }
      .repeat
      .unNone

    serverAddress <- Resource.eval(SocketHelpers.getLocalAddress(fd, ipv4))
  } yield (serverAddress, sockets)

}
