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
package unixsocket

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.io.internal.NativeUtil._
import fs2.io.internal.SocketHelpers
import fs2.io.internal.syssocket._
import fs2.io.internal.sysun._
import fs2.io.internal.sysunOps._

import scala.scalanative.libc.errno._
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.sys.socket.{bind => _, connect => _, accept => _, _}
import scala.scalanative.posix.unistd._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private final class FdPollingUnixSockets[F[_]: Files: LiftIO](implicit F: Async[F])
    extends UnixSockets[F] {

  def client(address: UnixSocketAddress): Resource[F, Socket[F]] = for {
    poller <- Resource.eval(fileDescriptorPoller[F])
    fd <- SocketHelpers.openNonBlocking(AF_UNIX, SOCK_STREAM)
    handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
    _ <- Resource.eval {
      toSockaddrUn(address.path).use { addr =>
        handle
          .pollWriteRec(false) { connected =>
            if (connected) IO.pure(Either.unit)
            else
              IO {
                if (connect(fd, addr, sizeof[sockaddr_un].toUInt) < 0) {
                  val e = errno
                  if (e == EINPROGRESS)
                    Left(true) // we will be connected when we unblock
                  else if (e == ECONNREFUSED)
                    throw new ConnectException(fromCString(strerror(errno)))
                  else
                    throw new IOException(fromCString(strerror(errno)))
                } else
                  Either.unit
              }
          }
          .to
      }
    }
    socket <- FdPollingSocket[F](fd, handle, raiseIpAddressError, raiseIpAddressError)
  } yield socket

  def server(
      address: UnixSocketAddress,
      deleteIfExists: Boolean,
      deleteOnClose: Boolean
  ): Stream[F, Socket[F]] = for {
    poller <- Stream.eval(fileDescriptorPoller[F])

    _ <- Stream.bracket(Files[F].deleteIfExists(Path(address.path)).whenA(deleteIfExists)) { _ =>
      Files[F].deleteIfExists(Path(address.path)).whenA(deleteOnClose)
    }

    fd <- Stream.resource(SocketHelpers.openNonBlocking(AF_UNIX, SOCK_STREAM))
    handle <- Stream.resource(poller.registerFileDescriptor(fd, true, false).mapK(LiftIO.liftK))

    _ <- Stream.eval {
      toSockaddrUn(address.path).use { addr =>
        F.delay(guard_(bind(fd, addr, sizeof[sockaddr_un].toUInt)))
      } *> F.delay(guard_(listen(fd, 0)))
    }

    socket <- Stream
      .resource {
        val accepted = for {
          fd <- Resource.makeFull[F, Int] { poll =>
            poll {
              handle
                .pollReadRec(()) { _ =>
                  IO {
                    val clientFd =
                      if (LinktimeInfo.isLinux)
                        guard(accept(fd, null, null))
                      else
                        guard(accept4(fd, null, null, SOCK_NONBLOCK))

                    if (clientFd >= 0)
                      Right(clientFd)
                    else
                      Left(())
                  }
                }
                .to
            }
          }(fd => F.delay(guard_(close(fd))))
          _ <-
            if (!LinktimeInfo.isLinux)
              Resource.eval(setNonBlocking(fd))
            else Resource.unit[F]
          handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
          socket <- FdPollingSocket[F](fd, handle, raiseIpAddressError, raiseIpAddressError)
        } yield socket

        accepted.attempt
          .map(_.toOption)
      }
      .repeat
      .unNone

  } yield socket

  private def toSockaddrUn(path: String): Resource[F, Ptr[sockaddr]] =
    Resource.make(F.delay(Zone.open()))(z => F.delay(z.close())).evalMap[Ptr[sockaddr]] {
      implicit z =>
        val pathBytes = path.getBytes
        if (pathBytes.length > 107)
          F.raiseError(new IllegalArgumentException(s"Path too long: $path"))
        else
          F.delay {
            val addr = alloc[sockaddr_un]()
            addr.sun_family = AF_UNIX.toUShort
            memcpy(addr.sun_path.at(0), pathBytes.at(0), pathBytes.length.toULong)
            addr.asInstanceOf[Ptr[sockaddr]]
          }
    }

  private def raiseIpAddressError[A]: F[A] =
    F.raiseError(new UnsupportedOperationException("UnixSockets do not use IP addressing"))

}
