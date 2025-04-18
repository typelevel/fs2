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

import cats.effect.{Async, IO, LiftIO, Resource}
import cats.syntax.all._

import com.comcast.ip4s.UnixSocketAddress

import fs2.io.file.{Files, Path}
import fs2.io.internal.NativeUtil._
import fs2.io.internal.SocketHelpers
import fs2.io.internal.syssocket.{connect => uconnect, bind => ubind, _}
import fs2.io.internal.sysun._
import fs2.io.internal.sysunOps._

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.string._
import scala.scalanative.posix.sys.socket.{bind => _, connect => _, accept => _, _}
import scala.scalanative.posix.unistd._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private final class FdPollingUnixSocketsProvider[F[_]: Files: LiftIO](implicit F: Async[F])
    extends UnixSocketsProvider[F] {

  def connect(address: UnixSocketAddress, options: List[SocketOption]): Resource[F, Socket[F]] =
    for {
      poller <- Resource.eval(fileDescriptorPoller[F])
      fd <- SocketHelpers.openNonBlocking(AF_UNIX, SOCK_STREAM)
      _ <- Resource.eval(options.traverse(so => SocketHelpers.setOption(fd, so.key, so.value)))
      handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
      _ <- Resource.eval {
        handle
          .pollWriteRec(false) { connected =>
            if (connected) SocketHelpers.checkSocketError[IO](fd).as(Either.unit)
            else
              IO {
                toSockaddrUn(address.path) { addr =>
                  if (guard(uconnect(fd, addr, sizeof[sockaddr_un].toUInt)) < 0)
                    Left(true) // we will be connected when unblocked
                  else
                    Either.unit[Boolean]
                }
              }
          }
          .to
      }
      socket <- FdPollingSocket[F](
        fd,
        handle,
        SocketHelpers.getLocalAddressGen(fd, AF_UNIX),
        SocketHelpers.getRemoteAddressGen(fd, AF_UNIX)
      )
    } yield socket

  def bind(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, ServerSocket[F]] = {

    var deleteIfExists: Boolean = false
    var deleteOnClose: Boolean = true

    val filteredOptions = options.filter { opt =>
      opt.key match {
        case SocketOption.UnixServerSocketDeleteIfExists =>
          deleteIfExists = opt.value.asInstanceOf[java.lang.Boolean]
          false
        case SocketOption.UnixServerSocketDeleteOnClose =>
          deleteOnClose = opt.value.asInstanceOf[java.lang.Boolean]
          false
        case _ => true
      }
    }

    val delete = Resource.make {
      Files[F].deleteIfExists(Path(address.path)).whenA(deleteIfExists)
    } { _ =>
      Files[F].deleteIfExists(Path(address.path)).whenA(deleteOnClose)
    }

    for {
      poller <- Resource.eval(fileDescriptorPoller[F])

      _ <- delete

      fd <- SocketHelpers.openNonBlocking(AF_UNIX, SOCK_STREAM)

      handle <- poller.registerFileDescriptor(fd, true, false).mapK(LiftIO.liftK)
      _ <- Resource.eval {
        F.delay {
          toSockaddrUn(address.path)(addr => guard_(ubind(fd, addr, sizeof[sockaddr_un].toUInt)))
        } *> F.delay(guard_(listen(fd, 0)))
      }

      info = new SocketInfo[F] {
        def getOption[A](key: SocketOption.Key[A]) = SocketHelpers.getOption(fd, key)
        def setOption[A](key: SocketOption.Key[A], value: A) =
          SocketHelpers.setOption(fd, key, value)
        def supportedOptions = SocketHelpers.supportedOptions
        def localAddressGen = SocketHelpers.getLocalAddressGen(fd, AF_UNIX)
        def localAddress = SocketInfo.downcastAddress(localAddressGen)
      }

      clients = Stream
        .resource {
          val accepted = for {
            fd <- Resource.makeFull[F, Int] { poll =>
              poll {
                handle
                  .pollReadRec(()) { _ =>
                    IO {
                      val clientFd =
                        if (LinktimeInfo.isLinux)
                          guard(accept4(fd, null, null, SOCK_NONBLOCK))
                        else
                          guard(accept(fd, null, null))

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

            _ <- Resource.eval(
              filteredOptions.traverse(so => SocketHelpers.setOption(fd, so.key, so.value))
            )
            handle <- poller.registerFileDescriptor(fd, true, true).mapK(LiftIO.liftK)
            socket <- FdPollingSocket[F](
              fd,
              handle,
              SocketHelpers.getLocalAddressGen(fd, AF_UNIX),
              SocketHelpers.getRemoteAddressGen(fd, AF_UNIX)
            )
          } yield socket

          accepted.attempt.map(_.toOption)
        }
        .repeat
        .unNone

    } yield ServerSocket(info, clients)
  }

  private def toSockaddrUn[A](path: String)(f: Ptr[sockaddr] => A): A = {
    val pathBytes = path.getBytes
    if (pathBytes.length > 107)
      throw new IllegalArgumentException(s"Path too long: $path")

    val addr = stackalloc[sockaddr_un]()
    addr.sun_family = AF_UNIX.toUShort
    memcpy(addr.sun_path.at(0), pathBytes.atUnsafe(0), pathBytes.length.toULong)

    f(addr.asInstanceOf[Ptr[sockaddr]])
  }
}
