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
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package fs2
package io
package net

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.unsafe.UringSystem.Uring
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.io.internal.NativeUtil._
import fs2.io.internal.SocketHelpers
import fs2.io.internal.syssocket.{bind => sbind}

import scala.scalanative.libc.stdlib
import scala.scalanative.posix.errno._
import scala.scalanative.posix.netinet.in.sockaddr_in6
import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private final class UringIpSocketsProvider[F[_]: Dns: LiftIO](implicit F: Async[F])
    extends IpSocketsProvider[F] {

  override def connectIp(
      to: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[F, Socket[F]] = for {
    ring <- Resource.eval(Uring.get.to[F])
    address <- Resource.eval(to.resolve)
    domain = domainFor(address.host)
    fd <- openSocket(ring, domain)
    _ <- Resource.eval(options.traverse_(so => SocketHelpers.setOption(fd, so.key, so.value)))
    _ <- sockaddr(address).evalMap { case (addr, len) =>
      ring
        .call(UringSocketOperations.io_uring_prep_connect(_, fd, addr, len), checkErrors = false)
        .map(UringSocketOperations.checkResult)
        .void
        .to[F]
    }
    addresses <- Resource.eval(socketAddresses(fd, domain))
    socket <- UringSocket[F](ring, fd, addresses._1, addresses._2)
  } yield socket

  override def bindIp(
      address: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[F, ServerSocket[F]] = for {
    ring <- Resource.eval(Uring.get.to[F])
    resolvedHost <- Resource.eval(address.host.resolve)
    domain = domainFor(resolvedHost)
    fd <- openSocket(ring, domain)
    _ <- Resource.eval {
      val bindF = F.delay {
        val resolvedAddress = SocketAddress(resolvedHost, address.port)
        SocketHelpers.toSockaddr(resolvedAddress) { (addr, len) =>
          guard_(sbind(fd, addr, len))
        }
      }

      SocketHelpers.setOption(fd, SO_REUSEADDR, 1) *>
        bindF *>
        F.delay(guard_(listen(fd, 0)))
    }
    serverAddress <- Resource.eval(F.delay(SocketHelpers.getAddress(fd, domain)))

    sockets = Stream
      .resource {
        acceptSocket(ring, fd).flatMap {
          case Some(clientFd) =>
            for {
              _ <- Resource.eval(
                options.traverse_(so => SocketHelpers.setOption(clientFd, so.key, so.value))
              )
              addresses <- Resource.eval(socketAddresses(clientFd, domain))
              socket <- UringSocket[F](ring, clientFd, addresses._1, addresses._2)
            } yield Option(socket)
          case None => Resource.eval(F.cede.as(Option.empty[Socket[F]]))
        }
      }
      .repeat
      .unNone

    info = new SocketInfo[F] {
      def getOption[A](key: SocketOption.Key[A]) = SocketHelpers.getOption(fd, key)
      def setOption[A](key: SocketOption.Key[A], value: A) =
        SocketHelpers.setOption(fd, key, value)
      def supportedOptions = SocketHelpers.supportedOptions
      val address = serverAddress
    }
  } yield ServerSocket(info, sockets)

  private def openSocket(ring: Uring, domain: Int): Resource[F, Int] =
    ring
      .bracket(
        UringSocketOperations.io_uring_prep_socket(_, domain, SOCK_STREAM, 0, 0),
        checkErrors = false
      )(closeSocket(ring, _))
      .mapK(LiftIO.liftK)
      .evalMap(result => F.delay(UringSocketOperations.checkResult(result)))

  private def acceptSocket(ring: Uring, fd: Int): Resource[F, Option[Int]] =
    ring
      .bracket(
        UringSocketOperations.io_uring_prep_accept(_, fd, null, null, 0),
        checkErrors = false
      )(closeSocket(ring, _))
      .mapK(LiftIO.liftK)
      .evalMap { result =>
        F.delay {
          if (retryAccept(result)) None
          else Some(UringSocketOperations.checkResult(result))
        }
      }

  private def retryAccept(result: Int): Boolean = {
    val error = -result
    result < 0 &&
    (error == EINTR || error == EAGAIN || error == EWOULDBLOCK ||
      error == ECONNABORTED || error == EPROTO || error == ENETDOWN ||
      error == ENOPROTOOPT || error == EHOSTUNREACH || error == EOPNOTSUPP ||
      error == ENETUNREACH)
  }

  private def closeSocket(ring: Uring, fd: Int): IO[Unit] =
    ring
      .call(UringSocketOperations.io_uring_prep_close(_, fd), checkErrors = false)
      .map(UringSocketOperations.checkResult)
      .void

  private def socketAddresses(fd: Int, domain: Int) =
    F.delay(
      SocketHelpers.getAddress(fd, domain) -> SocketHelpers.getPeerAddress(fd, domain)
    )

  private def sockaddr(
      address: SocketAddress[IpAddress]
  ): Resource[F, (Ptr[sockaddr], socklen_t)] =
    Resource.make {
      F.delay {
        val addr =
          stdlib.calloc(1.toUSize, sizeof[sockaddr_in6]).asInstanceOf[Ptr[sockaddr]]
        if (addr == null)
          throw new OutOfMemoryError("Could not allocate sockaddr")

        val len = stackalloc[socklen_t]()
        SocketHelpers.toSockaddr(address, addr, len)
        (addr, !len)
      }
    } { case (addr, _) =>
      F.delay(stdlib.free(addr.asInstanceOf[Ptr[Byte]]))
    }

  private def domainFor(address: IpAddress): Int =
    if (address.isInstanceOf[Ipv4Address]) AF_INET else AF_INET6
}
