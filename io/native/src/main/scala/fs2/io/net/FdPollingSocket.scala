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

import cats.effect.{Async, FileDescriptorPollHandle, IO, LiftIO, Resource}
import cats.syntax.all._
import com.comcast.ip4s.GenSocketAddress
import fs2.io.internal.NativeUtil._
import fs2.io.internal.{ResizableBuffer, SocketHelpers}

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.util.concurrent.atomic.AtomicLong

import FdPollingSocket._

private final class FdPollingSocket[F[_]: LiftIO] private (
    fd: Int,
    handle: FileDescriptorPollHandle,
    readBuffer: ResizableBuffer[F],
    val isOpen: F[Boolean],
    val address: GenSocketAddress,
    val peerAddress: GenSocketAddress
)(implicit F: Async[F])
    extends Socket[F] { outer =>

  private val totalBytesRead: AtomicLong = new AtomicLong(0L)
  private val totalBytesWritten: AtomicLong = new AtomicLong(0L)
  private val incompleteWriteCount: AtomicLong = new AtomicLong(0L)

  def metrics: SocketMetrics = new SocketMetrics.UnsealedSocketMetrics {
    def totalBytesRead() = outer.totalBytesRead.get
    def totalBytesWritten() = outer.totalBytesWritten.get
    def incompleteWriteCount() = outer.incompleteWriteCount.get
  }

  def localAddress = F.pure(address.asIpUnsafe)
  def remoteAddress = F.pure(peerAddress.asIpUnsafe)

  def endOfInput: F[Unit] = shutdownF(0)
  def endOfOutput: F[Unit] = shutdownF(1)
  private[this] def shutdownF(how: Int): F[Unit] = F.delay {
    guardMask_(shutdown(fd, how))(_ == ENOTCONN)
  }

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] = readBuffer.get(maxBytes).use { buf =>
    handle
      .pollReadRec(()) { _ =>
        IO(guard(unistd.read(fd, buf, maxBytes.toULong))).flatMap { readed =>
          if (readed > 0) {
            totalBytesRead.addAndGet(readed.toLong)
            IO(Right(Some(Chunk.fromBytePtr(buf, readed))))
          } else if (readed == 0)
            IO.pure(Right(None))
          else
            IO.pure(Left(()))
        }
      }
      .to
  }

  def readN(numBytes: Int): F[Chunk[Byte]] =
    readBuffer.get(numBytes).use { buf =>
      def go(pos: Int): IO[Either[Int, Chunk[Byte]]] =
        IO(guard(unistd.read(fd, buf + pos.toLong, (numBytes - pos).toULong))).flatMap { readed =>
          if (readed > 0) {
            totalBytesRead.addAndGet(readed.toLong)
            val newPos = pos + readed
            if (newPos < numBytes) go(newPos)
            else IO(Right(Chunk.fromBytePtr(buf, newPos)))
          } else if (readed == 0)
            IO(Right(Chunk.fromBytePtr(buf, pos)))
          else
            IO.pure(Left(pos))
        }

      handle.pollReadRec(0)(go(_)).to
    }

  def reads: Stream[F, Byte] = Stream.repeatEval(read(DefaultReadSize)).unNoneTerminate.unchunks

  def write(bytes: Chunk[Byte]): F[Unit] = {
    val Chunk.ArraySlice(buf, offset, length) = bytes.toArraySlice

    def go(pos: Int): IO[Either[Int, Unit]] =
      IO {
        if (LinktimeInfo.isLinux)
          guardSSize(
            send(fd, buf.atUnsafe(offset + pos), (length - pos).toULong, MSG_NOSIGNAL)
          ).toInt
        else
          guard(unistd.write(fd, buf.atUnsafe(offset + pos), (length - pos).toULong))
      }.flatMap { wrote =>
        if (wrote >= 0) {
          totalBytesWritten.addAndGet(wrote.toLong)
          val newPos = pos + wrote
          if (newPos < length) {
            incompleteWriteCount.incrementAndGet()
            go(newPos)
          } else
            IO.pure(Either.unit)
        } else
          IO.pure(Left(pos))
      }

    handle.pollWriteRec(0)(go(_)).to
  }

  def writes: Pipe[F, Byte, Nothing] = _.chunks.foreach(write(_))

  def getOption[A](key: SocketOption.Key[A]) =
    SocketHelpers.getOption[F, A](fd, key)

  def setOption[A](key: SocketOption.Key[A], value: A) =
    SocketHelpers.setOption(fd, key, value)

  def supportedOptions = SocketHelpers.supportedOptions
}

private object FdPollingSocket {
  private final val DefaultReadSize = 8192

  def apply[F[_]: LiftIO](
      fd: Int,
      handle: FileDescriptorPollHandle,
      address: GenSocketAddress,
      peerAddress: GenSocketAddress
  )(implicit F: Async[F]): Resource[F, Socket[F]] = for {
    buffer <- ResizableBuffer(DefaultReadSize)
    isOpen <- Resource.make(F.ref(true))(_.set(false))
  } yield new FdPollingSocket(fd, handle, buffer, isOpen.get, address, peerAddress)
}
