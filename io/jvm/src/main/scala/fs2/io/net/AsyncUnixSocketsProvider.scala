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
import cats.effect.std.Mutex
import cats.effect.syntax.all._
import cats.syntax.all._

import com.comcast.ip4s.{IpAddress, SocketAddress, UnixSocketAddress}

import fs2.io.file.{Files, FileHandle, SyncFileHandle}

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

private[net] abstract class AsyncUnixSocketsProvider[F[_]: Files](implicit F: Async[F])
    extends UnixSocketsProvider[F] {

  protected def openChannel(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, SocketChannel]

  protected def openServerChannel(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, (SocketInfo[F], Resource[F, SocketChannel])]

  override def connectUnix(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, Socket[F]] =
    openChannel(address, options).evalMap(
      AsyncUnixSocketsProvider.makeSocket[F](_, UnixSocketAddress(""), address)
    )

  override def bindUnix(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, ServerSocket[F]] = {
    val (filteredOptions, delete) = SocketOption.extractUnixSocketDeletes(options, address)

    (delete *> openServerChannel(address, filteredOptions)).map { case (info, accept) =>
      val acceptIncoming =
        Stream
          .resource(accept.attempt)
          .flatMap {
            case Left(_)         => Stream.empty[F]
            case Right(accepted) =>
              Stream.eval(
                AsyncUnixSocketsProvider.makeSocket(accepted, address, UnixSocketAddress(""))
              )
          }
          .repeat
      ServerSocket(info, acceptIncoming)
    }
  }
}

private[net] object AsyncUnixSocketsProvider {

  private def makeSocket[F[_]: Async](
      ch: SocketChannel,
      localAddress: UnixSocketAddress,
      remoteAddress: UnixSocketAddress
  ): F[Socket[F]] =
    (Mutex[F], Mutex[F]).mapN { (readMutex, writeMutex) =>
      new AsyncSocket[F](ch, readMutex, writeMutex, localAddress, remoteAddress)
    }

  private final class AsyncSocket[F[_]](
      ch: SocketChannel,
      readMutex: Mutex[F],
      writeMutex: Mutex[F],
      override val address: UnixSocketAddress,
      val peerAddress: UnixSocketAddress
  )(implicit F: Async[F])
      extends Socket.BufferedReads[F](readMutex)
      with SocketInfo.OptionsSupport[F] { outer =>

    protected def asyncInstance = F
    protected def channel = ch

    private val totalBytesWritten: AtomicLong = new AtomicLong(0L)
    private val incompleteWriteCount: AtomicLong = new AtomicLong(0L)

    def metrics: SocketMetrics = new SocketMetrics.UnsealedSocketMetrics {
      def totalBytesRead(): Long = outer.totalBytesRead.get
      def totalBytesWritten(): Long = outer.totalBytesWritten.get
      def incompleteWriteCount(): Long = outer.incompleteWriteCount.get
    }

    def readChunk(buff: ByteBuffer): F[Int] =
      evalOnVirtualThreadIfAvailable(F.blocking(ch.read(buff)))
        .cancelable(close)

    def write(bytes: Chunk[Byte]): F[Unit] = {
      def go(buff: ByteBuffer): F[Unit] =
        F.blocking(ch.write(buff)).cancelable(close).flatMap { written =>
          totalBytesWritten.addAndGet(written.toLong)
          if (written >= 0 && buff.remaining() > 0) {
            incompleteWriteCount.incrementAndGet()
            go(buff)
          } else F.unit
        }

      writeMutex.lock.surround {
        F.delay(bytes.toByteBuffer).flatMap(buffer => evalOnVirtualThreadIfAvailable(go(buffer)))
      }
    }

    private def raiseIpAddressError[A]: F[A] =
      F.raiseError(new UnsupportedOperationException("Unix sockets do not use IP addressing"))

    override def localAddress: F[SocketAddress[IpAddress]] = raiseIpAddressError

    override def remoteAddress: F[SocketAddress[IpAddress]] = raiseIpAddressError

    def isOpen: F[Boolean] = evalOnVirtualThreadIfAvailable(F.blocking(ch.isOpen()))
    def close: F[Unit] = evalOnVirtualThreadIfAvailable(F.blocking(ch.close()))
    def endOfOutput: F[Unit] =
      evalOnVirtualThreadIfAvailable(
        F.blocking {
          ch.shutdownOutput(); ()
        }
      )
    def endOfInput: F[Unit] =
      evalOnVirtualThreadIfAvailable(
        F.blocking {
          ch.shutdownInput(); ()
        }
      )
    override def sendFile(
        file: FileHandle[F],
        offset: Long,
        count: Long,
        chunkSize: Int
    ): Stream[F, Nothing] = file match {
      case syncFileHandle: SyncFileHandle[F] =>
        val fileChannel = syncFileHandle.chan

        def go(currOffset: Long, remaining: Long): F[Unit] =
          if (remaining <= 0) F.unit
          else {
            F.blocking(fileChannel.transferTo(currOffset, remaining, ch)).flatMap { written =>
              if (written == 0) F.unit
              else {
                go(currOffset + written, remaining - written)
              }
            }
          }

        Stream.exec(writeMutex.lock.surround(go(offset, count)))

      case _ =>
        super.sendFile(file, offset, count, chunkSize)
    }
  }
}
