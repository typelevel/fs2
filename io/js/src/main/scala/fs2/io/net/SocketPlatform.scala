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

import cats.data.OptionT
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Semaphore
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.internal.jsdeps.node.netMod
import fs2.internal.jsdeps.node.streamMod

import scala.scalajs.js

private[net] trait SocketPlatform[F[_]]

private[net] trait SocketCompanionPlatform {

  private[net] def forAsync[F[_]](
      sock: netMod.Socket
  )(implicit F: Async[F]): Resource[F, Socket[F]] =
    Resource.make {
      (
        Semaphore[F](1),
        F.ref(readReadable(F.delay(sock.asInstanceOf[Readable]), destroyIfNotEnded = false))
      ).mapN(new AsyncSocket[F](sock, _, _))
    } { _ =>
      F.delay {
        if (!sock.destroyed)
          sock.asInstanceOf[streamMod.Readable].destroy()
      }
    }

  private[net] class AsyncSocket[F[_]](
      sock: netMod.Socket,
      readSemaphore: Semaphore[F],
      readStream: Ref[F, Stream[F, Byte]]
  )(implicit F: Async[F])
      extends Socket[F] {

    private def read(
        f: Stream[F, Byte] => Pull[F, INothing, Option[(Chunk[Byte], Stream[F, Byte])]]
    ): F[Option[Chunk[Byte]]] =
      readSemaphore.permit.use { _ =>
        Pull
          .eval(readStream.get)
          .flatMap(f)
          .flatMap {
            case Some((chunk, tail)) =>
              Pull.eval(readStream.set(tail)) >> Pull.output1(chunk)
            case None => Pull.done
          }
          .stream
          .compile
          .last
      }

    override def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
      read(_.pull.unconsLimit(maxBytes))

    override def readN(numBytes: Int): F[Chunk[Byte]] =
      OptionT(read(_.pull.unconsN(numBytes))).getOrElse(Chunk.empty)

    override def reads: Stream[F, Byte] = Stream.resource(readSemaphore.permit).flatMap { _ =>
      Stream
        .eval(readStream.get)
        .flatten
        // Reset the stream to a direct wrapper around the socket
        .onFinalize(
          readStream
            .set(readReadable(F.delay(sock.asInstanceOf[Readable]), destroyIfNotEnded = false))
        )
    }

    override def endOfOutput: F[Unit] = F.delay(sock.end())

    override def isOpen: F[Boolean] =
      F.delay(sock.asInstanceOf[js.Dynamic].readyState.asInstanceOf[String] == "open")

    override def remoteAddress: F[SocketAddress[IpAddress]] =
      for {
        ip <- F.delay(sock.remoteAddress.toOption.flatMap(IpAddress.fromString).get)
        port <- F.delay(sock.remotePort.toOption.map(_.toInt).flatMap(Port.fromInt).get)
      } yield SocketAddress(ip, port)

    override def localAddress: F[SocketAddress[IpAddress]] =
      for {
        ip <- F.delay(IpAddress.fromString(sock.localAddress).get)
        port <- F.delay(Port.fromInt(sock.localPort.toInt).get)
      } yield SocketAddress(ip, port)

    override def write(bytes: Chunk[Byte]): F[Unit] =
      Stream.chunk(bytes).through(writes).compile.drain

    override def writes: Pipe[F, Byte, INothing] =
      writeWritable(sock.asInstanceOf[Writable].pure, endAfterUse = false)
  }

}
