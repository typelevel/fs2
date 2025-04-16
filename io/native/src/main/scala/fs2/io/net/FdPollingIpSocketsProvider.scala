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
import fs2.io.internal.syssocket.{connect => sconnect, bind => sbind, _}

import scala.scalanative.libc.errno._
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.sys.socket.{bind => _, connect => _, accept => _, _}
import scala.scalanative.posix.unistd._

private final class FdPollingIpSocketsProvider[F[_]: Dns: LiftIO](implicit F: Async[F])
    extends IpSocketsProvider[F] {

  def connect(to: SocketAddress[Host], options: List[SocketOption]): Resource[F, Socket[F]] = for {
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
                if (sconnect(fd, addr, len) < 0) {
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
      SocketHelpers.getLocalAddressGen(fd, if (ipv4) AF_INET else AF_INET6),
      SocketHelpers.getRemoteAddressGen(fd, if (ipv4) AF_INET else AF_INET6)
    )
  } yield socket

  def bind(
      address: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[F, ServerSocket[F]] = for {
    poller <- Resource.eval(fileDescriptorPoller[F])
    resolvedHost <- Resource.eval(address.host.resolve)
    ipv4 = resolvedHost.isInstanceOf[Ipv4Address]
    fd <- SocketHelpers.openNonBlocking(if (ipv4) AF_INET else AF_INET6, SOCK_STREAM)
    handle <- poller.registerFileDescriptor(fd, true, false).mapK(LiftIO.liftK)

    _ <- Resource.eval {
      val bindF = F.delay {
        val resolvedAddress = SocketAddress(resolvedHost, address.port)
        SocketHelpers.toSockaddr(resolvedAddress) { (addr, len) =>
          guard_(sbind(fd, addr, len))
        }
      }

      SocketHelpers.setOption(fd, SO_REUSEADDR, 1) *> bindF *> F.delay(guard_(listen(fd, 0)))
    }

    sockets = Stream
      .resource {
        val accepted = for {
          addrFd <- Resource.makeFull[F, (SocketAddress[IpAddress], Int)] { poll =>
            poll {
              handle
                .pollReadRec(()) { _ =>
                  IO {
                    SocketHelpers.allocateSockaddr(if (ipv4) AF_INET else AF_INET6) { (addr, len) =>
                      val clientFd =
                        if (LinktimeInfo.isLinux)
                          guard(accept4(fd, addr, len, SOCK_NONBLOCK))
                        else
                          guard(accept(fd, addr, len))

                      if (clientFd >= 0) {
                        val address = SocketHelpers.toSocketAddress(addr, if (ipv4) AF_INET else AF_INET6).asInstanceOf[SocketAddress[IpAddress]]
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
          _ <- Resource.eval {
            val setNonBlock = if (!LinktimeInfo.isLinux) setNonBlocking(fd) else F.unit
            setNonBlock *> options.traverse(so => SocketHelpers.setOption(fd, so.key, so.value))
          }
          handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
          socket <- FdPollingSocket[F](
            fd,
            handle,
            SocketHelpers.getLocalAddressGen(fd, if (ipv4) AF_INET else AF_INET6),
            SocketHelpers.getRemoteAddressGen(fd, if (ipv4) AF_INET else AF_INET6)
          )
        } yield socket

        accepted.attempt.map(_.toOption)
      }
      .repeat
      .unNone

    info = new SocketInfo[F] {
      def getOption[A](key: SocketOption.Key[A]) = SocketHelpers.getOption(fd, key)
      def setOption[A](key: SocketOption.Key[A], value: A) = SocketHelpers.setOption(fd, key, value)
      def supportedOptions = ???
      def localAddressGen = SocketHelpers.getLocalAddressGen[F](fd, if (ipv4) AF_INET else AF_INET6)
    }

  } yield ServerSocket(info, sockets)

}
