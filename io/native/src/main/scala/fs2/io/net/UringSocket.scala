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

import cats.effect.unsafe.UringSystem.Uring
import cats.effect.{Async, LiftIO, Resource}
import cats.effect.std.Mutex
import cats.syntax.all._
import com.comcast.ip4s.GenSocketAddress
import fs2.io.internal.NativeUtil.guardMask_
import fs2.io.internal.{ResizableBuffer, SocketHelpers}

import java.io.IOException
import scala.scalanative.posix.errno.ENOTCONN
import scala.scalanative.posix.sys.socket.{MSG_NOSIGNAL, shutdown}
import scala.scalanative.unsafe._

import UringSocketOperations._

private final class UringSocket[F[_]: LiftIO] private (
    ring: Uring,
    fd: Int,
    readBuffer: ResizableBuffer[F],
    writeMutex: Mutex[F],
    val address: GenSocketAddress,
    val peerAddress: GenSocketAddress,
    val isOpen: F[Boolean]
)(implicit F: Async[F])
    extends Socket[F] {

  def localAddress = F.pure(address.asIpUnsafe)
  def remoteAddress = F.pure(peerAddress.asIpUnsafe)

  def endOfInput: F[Unit] = shutdownF(0)
  def endOfOutput: F[Unit] = shutdownF(1)
  private[this] def shutdownF(how: Int): F[Unit] = F.delay {
    guardMask_(shutdown(fd, how))(_ == ENOTCONN)
  }

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
    if (maxBytes < 0)
      F.raiseError(new IllegalArgumentException("maxBytes must be non-negative"))
    else if (maxBytes == 0)
      F.pure(Some(Chunk.empty))
    else
      readBuffer.get(maxBytes).use { buffer =>
        recv(buffer, maxBytes).flatMap { read =>
          if (read == 0) F.pure(None)
          else F.delay(Some(Chunk.fromBytePtr(buffer, read)))
        }
      }

  def readN(numBytes: Int): F[Chunk[Byte]] =
    if (numBytes < 0)
      F.raiseError(new IllegalArgumentException("numBytes must be non-negative"))
    else if (numBytes == 0)
      F.pure(Chunk.empty)
    else
      readBuffer.get(numBytes).use { buffer =>
        def go(position: Int): F[Chunk[Byte]] =
          recv(buffer + position.toLong, numBytes - position).flatMap { read =>
            val nextPosition = position + read
            if (read == 0 || nextPosition == numBytes)
              F.delay(Chunk.fromBytePtr(buffer, nextPosition))
            else
              go(nextPosition)
          }

        go(0)
      }

  def reads: Stream[F, Byte] =
    Stream.repeatEval(read(UringSocket.DefaultReadSize)).unNoneTerminate.unchunks

  def write(bytes: Chunk[Byte]): F[Unit] =
    if (bytes.isEmpty) F.unit
    else
      writeMutex.lock.surround {
        val Chunk.ArraySlice(buffer, offset, length) = bytes.toArraySlice

        def go(position: Int): F[Unit] =
          send(buffer, offset + position, length - position).flatMap { sent =>
            val nextPosition = position + sent
            if (sent == 0)
              F.raiseError(new IOException("io_uring SEND completed without making progress"))
            else if (nextPosition < length) go(nextPosition)
            else F.unit
          }

        go(0)
      }

  def writes: Pipe[F, Byte, Nothing] =
    _.chunks.foreach(write)

  def getOption[A](key: SocketOption.Key[A]) =
    SocketHelpers.getOption[F, A](fd, key)

  def setOption[A](key: SocketOption.Key[A], value: A) =
    SocketHelpers.setOption(fd, key, value)

  def supportedOptions =
    SocketHelpers.supportedOptions

  /////////////////////////////////////////////////////////////////
  // io_uring helpers
  /////////////////////////////////////////////////////////////////

  private def recv(buffer: Ptr[Byte], length: Int): F[Int] =
    ring
      .call(io_uring_prep_recv(_, fd, buffer, length, 0), checkErrors = false)
      .map(checkResult)
      .to

  private def send(buffer: Array[Byte], offset: Int, length: Int): F[Int] =
    ring
      .call(
        io_uring_prep_send(_, fd, buffer.atUnsafe(offset), length, MSG_NOSIGNAL),
        checkErrors = false
      )
      .map(result => checkResult(result) -> buffer)
      .map(_._1)
      .to
}

private object UringSocket {
  private final val DefaultReadSize = 8192

  def apply[F[_]: LiftIO](
      ring: Uring,
      fd: Int,
      address: GenSocketAddress,
      peerAddress: GenSocketAddress
  )(implicit F: Async[F]): Resource[F, Socket[F]] =
    for {
      readBuffer <- ResizableBuffer(DefaultReadSize)
      writeMutex <- Resource.eval(Mutex[F])
      isOpen <- Resource.make(F.ref(true))(_.set(false))
    } yield new UringSocket(ring, fd, readBuffer, writeMutex, address, peerAddress, isOpen.get)
}
