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

import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Resource}
import com.comcast.ip4s.{GenSocketAddress, IpAddress, SocketAddress}
import fs2.io.internal.{facade, SuspendedStream}
import java.util.concurrent.atomic.AtomicLong

private[net] trait SocketCompanionPlatform {

  private[net] def forAsync[F[_]](
      sock: facade.net.Socket,
      address: GenSocketAddress,
      peerAddress: GenSocketAddress
  )(implicit F: Async[F]): Resource[F, Socket[F]] =
    suspendReadableAndRead(
      destroyIfNotEnded = false,
      destroyIfCanceled = false
    )(sock.asInstanceOf[Readable])
      .flatMap { case (_, stream) =>
        SuspendedStream(stream).map(new AsyncSocket(sock, _, address, peerAddress))
      }
      .onFinalize {
        F.delay {
          if (!sock.destroyed)
            sock.destroy()
        }
      }

  private[net] class AsyncSocket[F[_]](
      sock: facade.net.Socket,
      readStream: SuspendedStream[F, Byte],
      val address: GenSocketAddress,
      val peerAddress: GenSocketAddress
  )(implicit F: Async[F])
      extends Socket[F] { outer =>

    private val totalBytesRead: AtomicLong = new AtomicLong(0L)
    private val totalBytesWritten: AtomicLong = new AtomicLong(0L)
    private val incompleteWriteCount: AtomicLong = new AtomicLong(0L)

    def metrics: SocketMetrics = new SocketMetrics.UnsealedSocketMetrics {
      def totalBytesRead(): Long = outer.totalBytesRead.get
      def totalBytesWritten(): Long = outer.totalBytesWritten.get
      def incompleteWriteCount(): Long = outer.incompleteWriteCount.get
    }

    private def read(
        f: Stream[F, Byte] => Pull[F, Chunk[Byte], Option[(Chunk[Byte], Stream[F, Byte])]]
    ): F[Option[Chunk[Byte]]] =
      readStream
        .getAndUpdate(Kleisli(f).flatMapF {
          case Some((chunk, tail)) =>
            totalBytesRead.addAndGet(chunk.size.toLong)
            Pull.output1(chunk).as(tail)
          case None => Pull.pure(Stream.empty)
        }.run)
        .compile
        .last

    override def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
      read(_.pull.unconsLimit(maxBytes))

    override def readN(numBytes: Int): F[Chunk[Byte]] =
      OptionT(read(_.pull.unconsN(numBytes))).getOrElse(Chunk.empty)

    override def reads: Stream[F, Byte] = readStream.stream

    override def endOfInput: F[Unit] = F.unit

    override def endOfOutput: F[Unit] = F.delay {
      sock.end()
      ()
    }

    override def isOpen: F[Boolean] = F.delay(sock.readyState == "open")

    override def localAddress: F[SocketAddress[IpAddress]] =
      F.delay(address.asIpUnsafe)

    override def remoteAddress: F[SocketAddress[IpAddress]] =
      F.delay(peerAddress.asIpUnsafe)

    override def supportedOptions: F[Set[SocketOption.Key[?]]] =
      F.pure(
        Set(
          SocketOption.Encoding,
          SocketOption.KeepAlive,
          SocketOption.NoDelay,
          SocketOption.Timeout,
          SocketOption.UnixSocketDeleteIfExists,
          SocketOption.UnixSocketDeleteOnClose
        )
      )

    override def getOption[A](key: SocketOption.Key[A]): F[Option[A]] =
      key.get(sock)

    override def setOption[A](key: SocketOption.Key[A], value: A): F[Unit] =
      key.set(sock, value)

    override def write(bytes: Chunk[Byte]): F[Unit] =
      Stream.chunk(bytes).through(writes).compile.drain

    override def writes: Pipe[F, Byte, Nothing] =
      writeWritableInstrumented(
        F.pure(sock),
        endAfterUse = false,
        c => { totalBytesWritten.addAndGet(c.size.toLong); () }
      )
  }

}
