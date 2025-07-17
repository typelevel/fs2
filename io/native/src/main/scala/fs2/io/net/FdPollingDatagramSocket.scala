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

import cats.effect.{Async, FileDescriptorPollHandle, IO, LiftIO, Resource}
import fs2.io.internal.ResizableBuffer
import com.comcast.ip4s.GenSocketAddress
import fs2.io.internal.SocketHelpers
import com.comcast.ip4s._

import scala.scalanative.unsafe._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsigned._
import fs2.io.internal.NativeUtil._

import scala.scalanative.libc.errno._
import fs2.io.internal.syssocket.{connect => sconnect}
import fs2.io.net.FdPollingDatagramSocket._
import java.net.{NetworkInterface => JNetworkInterface}
import scala.scalanative.posix.errno._
import cats.syntax.all._

private final class FdPollingDatagramSocket[F[_]: LiftIO] private (
    val fd: Int,
    handle: FileDescriptorPollHandle,
    readBuffer: ResizableBuffer[F],
    val address: GenSocketAddress
)(implicit F: Async[F])
    extends DatagramSocket[F] {

  def localAddress: F[SocketAddress[IpAddress]] =
    F.pure(address.asIpUnsafe)

  def supportedOptions: F[Set[SocketOption.Key[?]]] = SocketHelpers.DatagramSupportedOptions

  def getOption[A](key: SocketOption.Key[A]) =
    SocketHelpers.getOption[F, A](fd, key)

  def setOption[A](key: SocketOption.Key[A], value: A) =
    SocketHelpers.setOption(fd, key, value)

  def readGen: F[GenDatagram] = read.map(_.toGenDatagram)
  def connect(address: GenSocketAddress) =
    F.delay {
      SocketHelpers.toSockaddr(address.asIpUnsafe) { (addr, len) =>
        val res = sconnect(fd, addr, len)
        if (res < 0) {
          val e = errno
          throw errnoToThrowable(e)
        }
      }
    }

  def disconnect: F[Unit] = F.delay {
    SocketHelpers.disconnectSockaddr { (addr, len) =>
      val res = sconnect(fd, addr, len)
      if (res < 0) {
        val e = errno
        throw errnoToThrowable(e)
      }
    }
  }

  override def read: F[Datagram] = readBuffer.get(DefaultReadSize).use { buf =>
    handle
      .pollReadRec(()) { _ =>
        IO {
          val addrBuf = stackalloc[sockaddr]()
          val addrLen = stackalloc[socklen_t]()
          !addrLen = sizeof[sockaddr].toUInt
          val nBytes = recvfrom(
            fd,
            buf,
            DefaultReadSize.toULong,
            MSG_DONTWAIT,
            addrBuf,
            addrLen
          )

          val addrBufBytes = addrBuf.asInstanceOf[Ptr[Byte]]
          val debugStr = (0 until 16).map(i => f"${addrBufBytes(i) & 0xff}%02x").mkString(" ")
          println(s"sockaddr raw bytes: $debugStr")

          val family = addrBuf._1.toInt
          println(s"family $family")

          if (nBytes < 0) {
            val err = errno
            if (err == EAGAIN || err == EWOULDBLOCK) {
              Left(())
            } else {
              throw new RuntimeException(s"[read] recvfrom fatal error, errno = $err")
            }
          } else {
            val remote = SocketHelpers.toSocketAddress(addrBuf, family)
            val bytes = Chunk.fromBytePtr(buf, nBytes.toInt)
            Right(Datagram(remote.asIpUnsafe, bytes))
          }
        }
      }
      .to
  }

  def reads: Stream[F, Datagram] = Stream.repeatEval(read)

  def write(bytes: Chunk[Byte]): F[Unit] = {
    val Chunk.ArraySlice(buf, offset, length) = bytes.toArraySlice

    def go(pos: Int): IO[Either[Int, Unit]] =
      IO {
        val sent = send(
          fd,
          buf.atUnsafe(offset + pos),
          (length - pos).toULong,
          0
        )
        if (sent < 0) Left(pos)
        else Right(sent)
      }.flatMap {
        case Right(sent) =>
          val newPos = pos + sent
          if (newPos < length) go(newPos.toInt)
          else IO.pure(Right(()))
        case Left(pos) =>
          IO.pure(Left(pos))
      }

    handle.pollWriteRec(0)(go(_)).to
  }

  def write(bytes: Chunk[Byte], address: GenSocketAddress): F[Unit] = {
    val Chunk.ArraySlice(buf, offset, length) = bytes.toArraySlice

    def go(pos: Int): IO[Either[Int, Unit]] =
      IO {
        SocketHelpers.toSockaddr(address.asIpUnsafe) { (addr, len) =>
          val sent = sendto(
            fd,
            buf.atUnsafe(offset + pos),
            (length - pos).toULong,
            0,
            addr,
            len
          )
          if (sent < 0) Left(pos)
          else Right(sent)
        }
      }.flatMap {
        case Right(sent) =>
          val newPos = pos + sent
          if (newPos < length) go(newPos.toInt)
          else IO.pure(Right(()))
        case Left(pos) =>
          IO.pure(Left(pos))
      }

    handle.pollWriteRec(0)(go(_)).to
  }

  def writes: Pipe[F, Datagram, Nothing] =
    _.foreach(write)

  def join(
      join: MulticastJoin[IpAddress],
      interface: NetworkInterface
  ): F[GroupMembership] =
    F.delay {
      val groupAddress = join.fold(
        j => {
          SocketHelpers.join(fd, j.group.address, interface)
          j.group.address
        },
        j => {
          SocketHelpers.join(fd, j.group.address, interface, j.source)
          j.group.address
        }
      )
      new GroupMembership {
        def drop = join.fold(
          j => SocketHelpers.drop(fd, j.group.address, interface),
          j => SocketHelpers.drop(fd, j.group.address, interface, j.source)
        )
        def block(source: IpAddress) = SocketHelpers.block(fd, groupAddress, interface, source)

        def unblock(source: IpAddress) = SocketHelpers.unblock(fd, groupAddress, interface, source)
      }
    }

  def join(j: MulticastJoin[IpAddress], interface: JNetworkInterface): F[GroupMembership] =
    join(j, NetworkInterface.fromJava(interface))
}

private object FdPollingDatagramSocket {
  private final val DefaultReadSize = 8192
  private final val MSG_DONTWAIT = 0x40

  def apply[F[_]: LiftIO](
      fd: Int,
      handle: FileDescriptorPollHandle,
      address: GenSocketAddress
  )(implicit F: Async[F]): Resource[F, DatagramSocket[F]] = for {
    buffer <- ResizableBuffer(DefaultReadSize)
  } yield new FdPollingDatagramSocket(fd, handle, buffer, address)
}
