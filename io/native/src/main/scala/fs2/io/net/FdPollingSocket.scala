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

import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.effect.unsafe.FileDescriptorPoller
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import fs2.io.internal.NativeUtil._
import fs2.io.internal.ResizableBuffer

import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.unistd
import scala.scalanative.unsigned._
import java.util.concurrent.atomic.AtomicReference

import FdPollingSocket._

private final class FdPollingSocket[F[_]](
    fd: Int,
    readBuffer: ResizableBuffer[F],
    readMutex: Mutex[F],
    writeMutex: Mutex[F],
    val localAddress: F[SocketAddress[IpAddress]],
    val remoteAddress: F[SocketAddress[IpAddress]]
)(implicit F: Async[F])
    extends Socket[F]
    with FileDescriptorPoller.Callback {

  @volatile private[this] var open = true

  def isOpen: F[Boolean] = F.delay(open)

  def close: F[Unit] = F.delay {
    open = false
    guard_(unistd.close(fd))
  }

  def endOfInput: F[Unit] = F.delay(guard_(shutdown(fd, 0)))
  def endOfOutput: F[Unit] = F.delay(guard_(shutdown(fd, 1)))

  private[this] val readCallback = new AtomicReference[Either[Throwable, Unit] => Unit]
  private[this] val writeCallback = new AtomicReference[Either[Throwable, Unit] => Unit]

  def notifyFileDescriptorEvents(readReady: Boolean, writeReady: Boolean): Unit = {
    if (readReady) {
      val cb = readCallback.getAndSet(ReadySentinel)
      if (cb ne null) cb(Either.unit)
    }
    if (writeReady) {
      val cb = writeCallback.getAndSet(ReadySentinel)
      if (cb ne null) cb(Either.unit)
    }
  }

  def awaitReadReady: F[Unit] = F.async { cb =>
    F.delay {
      if (readCallback.compareAndSet(null, cb))
        Some(
          F.delay {
            readCallback.compareAndSet(cb, null)
            ()
          }
        )
      else {
        cb(Either.unit)
        None
      }
    }
  }

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] = readMutex.lock.surround {
    readBuffer.get(maxBytes).flatMap { buf =>
      def go: F[Option[Chunk[Byte]]] =
        F.delay(guard(unistd.read(fd, buf, maxBytes.toULong))).flatMap { readed =>
          if (readed > 0)
            F.delay(Some(Chunk.fromBytePtr(buf, readed)))
          else if (readed == 0)
            F.pure(None)
          else
            awaitReadReady *> go
        }

      go
    }
  }

  def readN(numBytes: Int): F[Chunk[Byte]] = readMutex.lock.surround {
    readBuffer.get(numBytes).flatMap { buf =>
      def go(pos: Int): F[Chunk[Byte]] =
        F.delay(guard(unistd.read(fd, buf + pos.toLong, (numBytes - pos).toULong)))
          .flatMap { readed =>
            if (readed > 0) {
              val newPos = pos + readed
              if (newPos < numBytes) go(newPos)
              else F.delay(Chunk.fromBytePtr(buf, newPos))
            } else if (readed == 0)
              F.delay(Chunk.fromBytePtr(buf, pos))
            else
              awaitReadReady *> go(pos)
          }

      go(0)
    }
  }

  def reads: Stream[F, Byte] = Stream.repeatEval(read(DefaultReadSize)).unNoneTerminate.unchunks

  def write(bytes: Chunk[Byte]): F[Unit] = ???
  def writes: Pipe[F, Byte, Nothing] = ???

}

private object FdPollingSocket {

  private final val DefaultReadSize = 8192

  private val ReadySentinel: Either[Throwable, Unit] => Unit = _ => ()

}
