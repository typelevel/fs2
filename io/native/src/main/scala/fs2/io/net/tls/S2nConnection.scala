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
package tls

import cats.effect.SyncIO
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import scodec.bits.ByteVector

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.scalanative.libc
import scala.scalanative.posix
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import s2n._
import s2nutil._

private[tls] trait S2nConnection[F[_]] {

  def handshake: F[Unit]

  def read(n: Int): F[Chunk[Byte]]

  def write(bytes: Chunk[Byte]): F[Unit]

}

private[tls] object S2nConnection {
  def apply[F[_]](
      socket: Socket[F],
      clientMode: Boolean,
      config: S2nConfig,
      parameters: TLSParameters
  )(implicit F: Async[F]): Resource[F, S2nConnection[F]] =
    for {
      gcRoot <- Resource.make(
        F.delay(Collections.newSetFromMap[Any](new IdentityHashMap))
      )(gcr => F.delay(gcr.clear()))

      dispatcher <- Dispatcher.sequential[F]

      conn <- Resource.make(
        F.delay(guard(s2n_connection_new(if (clientMode) S2N_CLIENT.toUInt else S2N_SERVER.toUInt)))
      )(conn => F.delay(guard(s2n_connection_free(conn))))

      _ <- F.delay(s2n_connection_set_config(conn, config.ptr)).toResource

      recvLatch <- F.deferred[Either[Throwable, Unit]].flatMap(F.ref(_)).toResource
      recvBuffer <- Ref.in[Resource[F, *], SyncIO, Option[ByteVector]](Some(ByteVector.empty))
      recvQueue <- Queue.synchronous[F, Unit].toResource
      _ <- Resource.eval {
        F.delay {
          val ctx = RecvCallbackContext(recvBuffer, recvQueue, dispatcher)
          guard(s2n_connection_set_recv_ctx(conn, toPtr(ctx)))
          guard(s2n_connection_set_recv_cb(conn, recvCallback[F](_, _, _)))
          gcRoot.add(ctx)
        }
      }
      _ <- socket.reads.chunks
        .evalTap(_ => recvQueue.take)
        .noneTerminate
        .attempt
        .foreach {
          case Left(ex) =>
            recvLatch.get.flatMap(_.complete(Left(ex))).void
          case Right(chunk) =>
            recvBuffer.set(chunk.map(_.toByteVector)).to[F] *>
              F.deferred[Either[Throwable, Unit]]
                .flatMap(recvLatch.getAndSet(_))
                .flatMap(_.complete(Either.unit))
                .void
        }
        .compile
        .drain
        .background

      sendAvailable <- F.delay(new AtomicBoolean).toResource
      sendLatch <- F.deferred[Either[Throwable, Unit]].flatMap(F.ref(_)).toResource
      _ <- Resource.eval {
        F.delay {
          val ctx = SendCallbackContext(sendAvailable, sendLatch, socket, dispatcher, F)
          guard(s2n_connection_set_send_ctx(conn, toPtr(ctx)))
          guard(s2n_connection_set_send_cb(conn, sendCallback[F](_, _, _)))
          gcRoot.add(ctx)
        }
      }

    } yield new S2nConnection[F] {

      def handshake = ???

      def read(n: Int) = ???

      def write(bytes: Chunk[Byte]) = ???

    }

  private final case class RecvCallbackContext[F[_]](
      recvBuffer: Ref[SyncIO, Option[ByteVector]],
      recvQueue: Queue[F, Unit],
      dispatcher: Dispatcher[F]
  )

  private def recvCallback[F[_]](ioContext: Ptr[Byte], buf: Ptr[Byte], len: CUnsignedInt): CInt = {
    val ctx = fromPtr[RecvCallbackContext[F]](ioContext)
    import ctx._

    val read = recvBuffer.modify {
      case Some(bytes) =>
        val (left, right) = bytes.splitAt(len.toLong)
        (Some(right), Some(left))
      case None => (None, None)
    }

    read.unsafeRunSync() match {
      case Some(bytes) if bytes.nonEmpty =>
        bytes.copyToPtr(buf, 0)
        bytes.length.toInt
      case Some(_) =>
        dispatcher.unsafeRunAndForget(recvQueue.offer(()))
        libc.errno.errno = posix.errno.EWOULDBLOCK
        S2N_FAILURE
      case None => 0
    }
  }

  private final case class SendCallbackContext[F[_]](
      sendAvailable: AtomicBoolean,
      sendLatch: Ref[F, Deferred[F, Either[Throwable, Unit]]],
      socket: Socket[F],
      dispatcher: Dispatcher[F],
      async: Async[F]
  )

  private def sendCallback[F[_]](ioContext: Ptr[Byte], buf: Ptr[Byte], len: CUnsignedInt): CInt = {
    val ctx = fromPtr[SendCallbackContext[F]](ioContext)
    import ctx._
    implicit val F = async

    if (sendAvailable.getAndSet(false)) {
      dispatcher.unsafeRunAndForget(
        socket
          .write(Chunk.byteVector(ByteVector.fromPtr(buf, len.toLong)))
          .redeemWith(
            ex => sendLatch.get.flatMap(_.complete(Left(ex))),
            _ =>
              F.deferred[Either[Throwable, Unit]]
                .flatMap(sendLatch.getAndSet(_))
                .flatMap(oldLatch =>
                  F.delay(sendAvailable.set(true)) *> oldLatch.complete(Either.unit)
                )
          )
      )
      len.toInt
    } else {
      libc.errno.errno = posix.errno.EWOULDBLOCK
      S2N_FAILURE
    }
  }

}
