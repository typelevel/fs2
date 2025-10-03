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
package io.net

import fs2.io.file.SyncFileHandle
import fs2.io.file.FileHandle
import cats.effect.LiftIO
import cats.effect.Selector
import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.syntax.all._
import com.comcast.ip4s.{IpAddress, SocketAddress}

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey.OP_READ
import java.nio.channels.SelectionKey.OP_WRITE
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

private final class SelectingSocket[F[_]: LiftIO] private (
    selector: Selector,
    ch: SocketChannel,
    readMutex: Mutex[F],
    writeMutex: Mutex[F],
    override val address: SocketAddress[IpAddress],
    val peerAddress: SocketAddress[IpAddress]
)(implicit F: Async[F])
    extends Socket.BufferedReads(readMutex)
    with SocketInfo.AsyncSocketInfo[F] { outer =>

  protected def asyncInstance = F
  protected def channel = ch

  private val totalBytesWritten: AtomicLong = new AtomicLong(0L)
  private val incompleteWriteCount: AtomicLong = new AtomicLong(0L)

  def metrics: SocketMetrics = new SocketMetrics.UnsealedSocketMetrics {
    def totalBytesRead(): Long = outer.totalBytesRead.get
    def totalBytesWritten(): Long = outer.totalBytesWritten.get
    def incompleteWriteCount(): Long = outer.incompleteWriteCount.get
  }

  override def localAddress: F[SocketAddress[IpAddress]] =
    asyncInstance.pure(address)

  override def remoteAddress: F[SocketAddress[IpAddress]] =
    asyncInstance.pure(peerAddress)

  protected def readChunk(buf: ByteBuffer): F[Int] =
    F.delay(ch.read(buf)).flatMap { readed =>
      if (readed == 0) selector.select(ch, OP_READ).to *> readChunk(buf)
      else F.pure(readed)
    }

  def write(bytes: Chunk[Byte]): F[Unit] = {
    def go(buf: ByteBuffer): F[Unit] =
      F.delay {
        val written = ch.write(buf)
        totalBytesWritten.addAndGet(written.toLong)
        buf.remaining()
      }.flatMap { remaining =>
        if (remaining > 0) {
          incompleteWriteCount.incrementAndGet()
          selector.select(ch, OP_WRITE).to *> go(buf)
        } else F.unit
      }
    writeMutex.lock.surround {
      F.delay(bytes.toByteBuffer).flatMap(go)
    }
  }

  def isOpen: F[Boolean] = F.delay(ch.isOpen)

  def endOfOutput: F[Unit] =
    F.delay {
      ch.shutdownOutput(); ()
    }

  def endOfInput: F[Unit] =
    F.delay {
      ch.shutdownInput(); ()
    }

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
            if (written > 0) {
              go(currOffset + written, remaining - written)
            } else {
              selector.select(ch, OP_WRITE).to *> go(currOffset, remaining)
            }
          }
        }

      Stream.exec(writeMutex.lock.surround(go(offset, count)))

    case _ =>
      super.sendFile(file, offset, count, chunkSize)
  }
}

private object SelectingSocket {
  def apply[F[_]: LiftIO](
      selector: Selector,
      ch: SocketChannel,
      address: SocketAddress[IpAddress],
      remoteAddress: SocketAddress[IpAddress]
  )(implicit F: Async[F]): F[Socket[F]] =
    (Mutex[F], Mutex[F]).flatMapN { (readMutex, writeMutex) =>
      F.delay {
        new SelectingSocket[F](
          selector,
          ch,
          readMutex,
          writeMutex,
          address,
          remoteAddress
        )
      }
    }
}
