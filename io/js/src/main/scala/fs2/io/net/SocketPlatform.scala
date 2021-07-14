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
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import fs2.concurrent.SignallingRef
import fs2.io.internal.ByteChunkOps._
import fs2.io.internal.EventEmitterOps._
import fs2.internal.jsdeps.node.bufferMod.global.Buffer
import fs2.internal.jsdeps.node.netMod
import fs2.internal.jsdeps.node.nodeStrings

import scala.annotation.nowarn
import scala.scalajs.js
import cats.effect.std.Hotswap
import cats.effect.kernel.Ref
import cats.effect.kernel.Deferred
import com.comcast.ip4s.Port

private[net] trait SocketPlatform[F[_]] {
  private[net] def underlying: F[netMod.Socket]
  // This lets us swap in a TLS Socket
  private[net] def swap(socket: netMod.Socket): F[Unit]
}

private[net] trait SocketCompanionPlatform {

  private[net] def forAsync[F[_]](
      sock: netMod.Socket
  )(implicit F: Async[F]): Resource[F, Socket[F]] =
    for {
      dispatcher <- Dispatcher[F]
      underlying <- F.ref(sock).toResource
      buffer <- SignallingRef.of(Chunk.empty[Byte]).toResource
      read <- Semaphore[F](1).toResource
      ended <- F.deferred[Either[Throwable, Unit]].toResource
      listeners <- Hotswap(Resource.unit[F]).map(_._1) // Dummy, we'll use swap method below
      socket <- Resource.make(
        F.delay(new AsyncSocket[F](underlying, listeners, buffer, read, ended, dispatcher))
      ) { _ =>
        F.delay {
          if (!sock.destroyed)
            sock.asInstanceOf[js.Dynamic].destroy(): @nowarn
        }
      }
      _ <- socket.swap(sock).toResource
    } yield socket

  private final class AsyncSocket[F[_]](
      sock: Ref[F, netMod.Socket],
      listeners: Hotswap[F, Unit],
      buffer: SignallingRef[F, Chunk[Byte]],
      readSemaphore: Semaphore[F],
      ended: Deferred[F, Either[Throwable, Unit]],
      dispatcher: Dispatcher[F]
  )(implicit F: Async[F])
      extends Socket[F] {

    private[net] def underlying: F[netMod.Socket] = sock.get

    private[net] def swap(nextSock: netMod.Socket): F[Unit] = readSemaphore.permit.use { _ =>
      listeners.swap {
        registerListener[Buffer](nextSock, nodeStrings.data)(_.on_data(_, _)) { data =>
          dispatcher.unsafeRunAndForget(
            buffer.update { buffer =>
              buffer ++ data.toChunk
            }
          )
        } >> registerListener0(nextSock, nodeStrings.end)(_.on_end(_, _)) { () =>
          dispatcher.unsafeRunAndForget(ended.complete(Right(())))
        } >> registerListener[js.Error](nextSock, nodeStrings.error)(_.on_error(_, _)) { error =>
          dispatcher.unsafeRunAndForget(
            ended.complete(Left(js.JavaScriptException(error)))
          )
        }
      } >> sock.set(nextSock)
    }

    private def read(minBytes: Int, maxBytes: Int): F[Chunk[Byte]] =
      readSemaphore.permit.use { _ =>
        (Stream.eval(buffer.get) ++
          (Stream.bracket(sock.get.flatTap(s => F.delay(s.resume())))(sock =>
            F.delay(sock.pause())
          ) >> buffer.discrete))
          .filter(_.size >= minBytes)
          .merge(Stream.eval(ended.get.rethrow))
          .head
          .compile
          .drain *> buffer.modify(_.splitAt(maxBytes).swap)
      }

    override def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
      OptionT.liftF(read(1, maxBytes)).filter(_.nonEmpty).value

    override def readN(numBytes: Int): F[Chunk[Byte]] =
      read(numBytes, numBytes)

    override def reads: Stream[F, Byte] =
      Stream.repeatEval(read(1, Int.MaxValue)).takeWhile(_.nonEmpty).flatMap(Stream.chunk)

    override def endOfOutput: F[Unit] = sock.get.flatMap(s => F.delay(s.end()))

    override def isOpen: F[Boolean] = sock.get.flatMap { sock =>
      F.delay(sock.asInstanceOf[js.Dynamic].readyState.asInstanceOf[String] == "open")
    }

    override def remoteAddress: F[SocketAddress[IpAddress]] =
      for {
        sock <- sock.get
        remoteIp <- F.delay(sock.remoteAddress.toOption.flatMap(IpAddress.fromString).get)
        remotePort <- F.delay(sock.remotePort.toOption.map(_.toInt).flatMap(Port.fromInt).get)
      } yield SocketAddress(remoteIp, remotePort)

    override def localAddress: F[SocketAddress[IpAddress]] =
      for {
        sock <- sock.get
        remoteIp <- F.delay(IpAddress.fromString(sock.localAddress).get)
        remotePort <- F.delay(Port.fromInt(sock.localPort.toInt).get)
      } yield SocketAddress(remoteIp, remotePort)

    override def write(bytes: Chunk[Byte]): F[Unit] = sock.get.flatMap { sock =>
      F.async_[Unit] { cb =>
        sock.write(
          bytes.toUint8Array,
          e => cb(e.toLeft(()).leftMap(js.JavaScriptException(_)))
        ): @nowarn
      }.void
    }

    override def writes: Pipe[F, Byte, INothing] =
      _.chunks.foreach(write)
  }
}
