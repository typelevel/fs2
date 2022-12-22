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

import cats.effect.FileDescriptorPollHandle
import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import fs2.io.internal.NativeUtil._
import fs2.io.internal.ResizableBuffer

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import FdPollingSocket._

private final class FdPollingSocket[F[_]: LiftIO] private (
    fd: Int,
    handle: FileDescriptorPollHandle,
    readBuffer: ResizableBuffer[F],
    val isOpen: F[Boolean],
    val localAddress: F[SocketAddress[IpAddress]],
    val remoteAddress: F[SocketAddress[IpAddress]]
)(implicit F: Async[F])
    extends Socket[F] {

  def endOfInput: F[Unit] = F.delay(guard_(shutdown(fd, 0)))
  def endOfOutput: F[Unit] = F.delay(guard_(shutdown(fd, 1)))

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] = readBuffer.get(maxBytes).use { buf =>
    handle
      .pollReadRec(()) { _ =>
        IO(guard(unistd.read(fd, buf, maxBytes.toULong))).flatMap { readed =>
          if (readed > 0)
            IO(Right(Some(Chunk.fromBytePtr(buf, readed))))
          else if (readed == 0)
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
          send(fd, buf.at(offset + pos), (length - pos).toULong, MSG_NOSIGNAL).toInt
        else
          unistd.write(fd, buf.at(offset + pos), (length - pos).toULong)
      }.flatMap { wrote =>
        if (wrote >= 0) {
          val newPos = pos + wrote
          if (newPos < length)
            go(newPos)
          else
            IO.pure(Either.unit)
        } else
          IO.pure(Left(pos))
      }

    handle.pollWriteRec(0)(go(_)).to
  }

  def writes: Pipe[F, Byte, Nothing] = _.chunks.foreach(write(_))

}

private object FdPollingSocket {
  private final val DefaultReadSize = 8192

  def apply[F[_]: LiftIO](
      fd: Int,
      handle: FileDescriptorPollHandle,
      localAddress: F[SocketAddress[IpAddress]],
      remoteAddress: F[SocketAddress[IpAddress]]
  )(implicit F: Async[F]): Resource[F, Socket[F]] = for {
    buffer <- ResizableBuffer(DefaultReadSize)
    isOpen <- Resource.make(F.ref(true))(_.set(false))
  } yield new FdPollingSocket(fd, handle, buffer, isOpen.get, localAddress, remoteAddress)
}
