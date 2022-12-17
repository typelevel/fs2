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
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import fs2.io.internal.NativeUtil._
import fs2.io.internal.ResizableBuffer

import scala.scalanative.posix.unistd
import java.util.concurrent.atomic.AtomicReference

import FdPollingSocket._

private final class FdPollingSocket[F[_]](
    fd: Int,
    readBuffer: ResizableBuffer[F],
    readMutex: Mutex[F],
    writeMutex: Mutex[F]
)(implicit F: Async[F])
    extends Socket[F]
    with FileDescriptorPoller.Callback {

  @volatile private[this] var open = true

  def isOpen: F[Boolean] = F.delay(open)

  def close: F[Unit] = F.delay {
    open = false
    guard_(unistd.close(fd))
  }

  def localAddress: F[SocketAddress[IpAddress]] = ???
  def remoteAddress: F[SocketAddress[IpAddress]] = ???

  def endOfInput: F[Unit] = ???
  def endOfOutput: F[Unit] = ???

  private[this] val readCallback = new AtomicReference[Either[Throwable, Unit] => Unit]
  private[this] val writeCallback = new AtomicReference[Either[Throwable, Unit] => Unit]

  def notifyFileDescriptorEvents(readReady: Boolean, writeReady: Boolean): Unit = ???

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] = ???
  def readN(numBytes: Int): F[Chunk[Byte]] = ???
  def reads: Stream[F, Byte] = ???

  def write(bytes: Chunk[Byte]): F[Unit] = ???
  def writes: Pipe[F, Byte, Nothing] = ???

}

private object FdPollingSocket {

  private val ReadySentinel: Either[Throwable, Unit] => Unit = _ => ()

}
