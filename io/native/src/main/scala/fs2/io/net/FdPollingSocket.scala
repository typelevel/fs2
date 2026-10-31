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
import cats.effect.std.Mutex
import cats.syntax.all._
import com.comcast.ip4s.GenSocketAddress
import fs2.io.internal.NativeUtil._
import fs2.io.internal.SocketHelpers

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import FdPollingSocket._

private final class FdPollingSocket[F[_]: LiftIO] private (
    fd: Int,
    handle: FileDescriptorPollHandle,
    readMutex: Mutex[F],
    val isOpen: F[Boolean],
    val address: GenSocketAddress,
    val peerAddress: GenSocketAddress
)(implicit F: Async[F])
    extends Socket[F] {

  def localAddress = F.pure(address.asIpUnsafe)
  def remoteAddress = F.pure(peerAddress.asIpUnsafe)

  def endOfInput: F[Unit] = shutdownF(0)
  def endOfOutput: F[Unit] = shutdownF(1)
  private[this] def shutdownF(how: Int): F[Unit] = F.delay {
    guardMask_(shutdown(fd, how))(_ == ENOTCONN)
  }

  /** Reads up to `max` bytes in to the current thread's scratch buffer, without waiting for the
    * file descriptor to become readable. Returns the buffer and the result of `read(2)`.
    *
    * The caller must copy the bytes out before it suspends, so that the buffer is never held
    * across a wait and the socket doesn't hold on to a read buffer between reads.
    */
  private def readScratch(max: Int): (Array[Byte], Int) = {
    val buf = scratchBuffer(max)
    val len = math.min(max, MaxScratchSize)
    (buf, guard(unistd.read(fd, buf.atUnsafe(0), len.toUSize)))
  }

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] = readMutex.lock.surround {
    handle
      .pollReadRec(()) { _ =>
        IO {
          val (buf, readed) = readScratch(maxBytes)
          if (readed > 0)
            Right(Some(Chunk.array(java.util.Arrays.copyOf(buf, readed))))
          else if (readed == 0)
            Right(None)
          else
            Left(())
        }
      }
      .to
  }

  def readN(numBytes: Int): F[Chunk[Byte]] = readMutex.lock.surround {
    // Until the first bytes arrive, reads go to the scratch buffer, so that a socket waiting for
    // data doesn't hold on to an array. After that, `dest` is allocated with size `numBytes` and
    // the rest is read straight in to it.
    def go(dest: Array[Byte], pos: Int): IO[Either[(Array[Byte], Int), Chunk[Byte]]] =
      IO {
        if (pos == 0) {
          val (buf, readed) = readScratch(numBytes)
          if (readed > 0) {
            val target = new Array[Byte](numBytes)
            System.arraycopy(buf, 0, target, 0, readed)
            (target, readed)
          } else (dest, readed)
        } else (dest, guard(unistd.read(fd, dest.atUnsafe(pos), (numBytes - pos).toUSize)))
      }.flatMap { case (next, readed) =>
        if (readed > 0) {
          val newPos = pos + readed
          if (newPos < numBytes) go(next, newPos)
          else IO.pure(Right(Chunk.array(next)))
        } else if (readed == 0)
          // end-of-stream; don't retain the unused part of `next`
          IO.pure(Right(Chunk.array(java.util.Arrays.copyOf(next, pos))))
        else
          IO.pure(Left((next, pos)))
      }

    handle.pollReadRec((Array.emptyByteArray, 0)) { case (dest, pos) => go(dest, pos) }.to
  }

  def reads: Stream[F, Byte] = Stream.repeatEval(read(DefaultReadSize)).unNoneTerminate.unchunks

  def write(bytes: Chunk[Byte]): F[Unit] = {
    val Chunk.ArraySlice(buf, offset, length) = bytes.toArraySlice

    def go(pos: Int): IO[Either[Int, Unit]] =
      IO {
        if (LinktimeInfo.isLinux)
          guardSSize(
            send(fd, buf.atUnsafe(offset + pos), (length - pos).toUSize, MSG_NOSIGNAL)
          ).toInt
        else
          guard(unistd.write(fd, buf.atUnsafe(offset + pos), (length - pos).toUSize))
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

  def getOption[A](key: SocketOption.Key[A]) =
    SocketHelpers.getOption[F, A](fd, key)

  def setOption[A](key: SocketOption.Key[A], value: A) =
    SocketHelpers.setOption(fd, key, value)

  def supportedOptions = SocketHelpers.supportedOptions
}

private object FdPollingSocket {
  private final val DefaultReadSize = 8192

  /** Upper bound on the size of a scratch buffer. Each thread keeps its buffer for as long as it
    * lives, so a single large read must not leave a large buffer behind.
    */
  private final val MaxScratchSize = 64 * 1024

  private[this] val scratchBuffers = new ThreadLocal[Array[Byte]]

  /** Returns the current thread's scratch buffer, with room for at least `size` bytes, capped at
    * `MaxScratchSize`. A single read may therefore return fewer than `size` bytes, which callers
    * already allow for.
    *
    * Scala Native's garbage collectors don't move objects, so `read(2)` can write straight in to
    * the array, as `write` already relies on for sending.
    */
  private def scratchBuffer(size: Int): Array[Byte] = {
    val limit = math.min(size, MaxScratchSize)
    var buffer = scratchBuffers.get()
    if (buffer == null || buffer.length < limit) {
      buffer = new Array[Byte](math.max(limit, DefaultReadSize))
      scratchBuffers.set(buffer)
    }
    buffer
  }

  def apply[F[_]: LiftIO](
      fd: Int,
      handle: FileDescriptorPollHandle,
      address: GenSocketAddress,
      peerAddress: GenSocketAddress
  )(implicit F: Async[F]): Resource[F, Socket[F]] = for {
    readMutex <- Resource.eval(Mutex[F])
    isOpen <- Resource.make(F.ref(true))(_.set(false))
  } yield new FdPollingSocket(fd, handle, readMutex, isOpen.get, address, peerAddress)
}
