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

import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.io.internal.SuspendedStream
import fs2.io.internal.facade

private[net] trait SocketCompanionPlatform {

  private[net] def forAsync[F[_]](
      sock: facade.net.Socket
  )(implicit F: Async[F]): Resource[F, Socket[F]] =
    suspendReadableAndRead(
      destroyIfNotEnded = false,
      destroyIfCanceled = false
    )(sock.asInstanceOf[Readable])
      .flatMap { case (_, stream) =>
        SuspendedStream(stream).map(new AsyncSocket(sock, _))
      }
      .onFinalize {
        F.delay {
          if (!sock.destroyed)
            sock.destroy()
        }
      }

  private[net] class AsyncSocket[F[_]](
      sock: facade.net.Socket,
      readStream: SuspendedStream[F, Byte]
  )(implicit F: Async[F])
      extends Socket[F] {

    private def read(
        f: Stream[F, Byte] => Pull[F, Chunk[Byte], Option[(Chunk[Byte], Stream[F, Byte])]]
    ): F[Option[Chunk[Byte]]] =
      readStream
        .getAndUpdate(Kleisli(f).flatMapF {
          case Some((chunk, tail)) => Pull.output1(chunk).as(tail)
          case None                => Pull.pure(Stream.empty)
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

    override def remoteAddress: F[SocketAddress[IpAddress]] =
      for {
        ip <- F.delay(sock.remoteAddress.toOption.flatMap(IpAddress.fromString).get)
        port <- F.delay(sock.remotePort.toOption.map(_.toInt).flatMap(Port.fromInt).get)
      } yield SocketAddress(ip, port)

    override def localAddress: F[SocketAddress[IpAddress]] =
      for {
        ip <- F.delay(sock.localAddress.toOption.flatMap(IpAddress.fromString).get)
        port <- F.delay(sock.localPort.toOption.map(_.toInt).flatMap(Port.fromInt).get)
      } yield SocketAddress(ip, port)

    override def write(bytes: Chunk[Byte]): F[Unit] =
      Stream.chunk(bytes).through(writes).compile.drain

    override def writes: Pipe[F, Byte, Nothing] =
      writeWritable(sock.asInstanceOf[Writable].pure, endAfterUse = false)
  }

}
