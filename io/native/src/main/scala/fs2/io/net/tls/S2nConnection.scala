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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.io.internal.ResizableBuffer
import scodec.bits.ByteVector

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.scalanative.libc
import scala.scalanative.posix
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import s2n._
import s2nutil._

private[tls] trait S2nConnection[F[_]] {

  def handshake: F[Unit]

  def read(n: Int): F[Option[Chunk[Byte]]]

  def write(bytes: Chunk[Byte]): F[Unit]

  def shutdown: F[Unit]

  def applicationProtocol: F[String]

  def session: F[SSLSession]

}

private[tls] object S2nConnection {
  def apply[F[_]](
      socket: Socket[F],
      clientMode: Boolean,
      config: S2nConfig,
      parameters: TLSParameters
  )(implicit F: Async[F]): Resource[F, S2nConnection[F]] =
    for {
      gcRoot <- mkGcRoot

      conn <- Resource.make(
        F.delay(guard(s2n_connection_new(if (clientMode) S2N_CLIENT.toUInt else S2N_SERVER.toUInt)))
      )(conn => F.delay(guard_(s2n_connection_free(conn))))

      _ <- F.delay(guard_(s2n_connection_set_config(conn, config.ptr))).toResource
      _ <- parameters.configure(conn)
      _ <- F.delay {
        guard_(s2n_connection_set_blinding(conn, S2N_SELF_SERVICE_BLINDING.toUInt))
      }.toResource

      privateKeyTasks <- F.delay(new AtomicReference[F[Unit]](F.unit)).toResource
      privateKeyCleanupTasks <- F.delay(new AtomicReference[F[Unit]](F.unit)).toResource
      _ <- Resource.eval {
        F.delay {
          val ctx = ConnectionContext(privateKeyTasks, privateKeyCleanupTasks, F)
          guard_(s2n_connection_set_ctx(conn, toPtr(ctx)))
          gcRoot.add(ctx)
        }
      }

      readBuffer <- ResizableBuffer[F](8192)

      recvBuffer <- Resource.eval {
        F.delay(new AtomicReference[Option[ByteVector]](Some(ByteVector.empty)))
      }
      readTasks <- F.delay(new AtomicReference[F[Unit]](F.unit)).toResource
      _ <- Resource.eval {
        F.delay {
          val ctx = RecvCallbackContext(recvBuffer, readTasks, socket, F)
          guard_(s2n_connection_set_recv_ctx(conn, toPtr(ctx)))
          guard_(s2n_connection_set_recv_cb(conn, recvCallback[F](_, _, _)))
          gcRoot.add(ctx)
        }
      }

      sendAvailable <- F.delay(new AtomicBoolean(true)).toResource
      writeTasks <- F.delay(new AtomicReference[F[Unit]](F.unit)).toResource
      _ <- Resource.eval {
        F.delay {
          val ctx = SendCallbackContext(sendAvailable, writeTasks, socket, F)
          guard_(s2n_connection_set_send_ctx(conn, toPtr(ctx)))
          guard_(s2n_connection_set_send_cb(conn, sendCallback[F](_, _, _)))
          gcRoot.add(ctx)
        }
      }

    } yield new S2nConnection[F] {

      def handshake =
        F.delay {
          readTasks.set(F.unit)
          writeTasks.set(F.unit)
          privateKeyTasks.set(F.unit)
          privateKeyCleanupTasks.set(F.unit)
          val blocked = stackalloc[s2n_blocked_status]()
          guard_(s2n_negotiate(conn, blocked))
          !blocked
        }.guaranteeCase { oc =>
          blindingSleep.whenA(oc.isError)
        }.productL {
          val reads = F.delay(readTasks.get).flatten
          val writes = F.delay(writeTasks.get).flatten
          val pkeyOps = F.delay(privateKeyTasks.get).flatten
          reads.both(writes).both(pkeyOps)
        }.guarantee {
          F.delay(privateKeyCleanupTasks.get).flatten
        }.iterateUntil(_.toInt == S2N_NOT_BLOCKED) *>
          F.delay(guard_(s2n_connection_free_handshake(conn)))

      def read(n: Int) = readBuffer.get(n).use { buf =>
        def go(i: Int): F[Option[Chunk[Byte]]] =
          F.delay {
            readTasks.set(F.unit)
            val blocked = stackalloc[s2n_blocked_status]()
            val readed = guard(s2n_recv(conn, buf + i.toLong, (n - i).toCSSize, blocked))
            (!blocked, Math.max(readed, 0))
          }.guaranteeCase { oc =>
            blindingSleep.whenA(oc.isError)
          }.productL(F.delay(readTasks.get).flatten)
            .flatMap { case (blocked, readed) =>
              val total = i + readed

              def chunk: F[Option[Chunk[Byte]]] =
                F.pure(Some(Chunk.fromBytePtr(buf, total)))

              if (total >= n)
                chunk
              else if (blocked.toInt == S2N_NOT_BLOCKED) {
                if (total > 0)
                  chunk
                else
                  F.pure(None)
              } else go(total)
            }
        go(0)
      }

      def write(bytes: Chunk[Byte]) = {
        val Chunk.ArraySlice(buf, offset, n) = bytes.toArraySlice

        def go(i: Int): F[Unit] =
          F.delay {
            writeTasks.set(F.unit)
            val blocked = stackalloc[s2n_blocked_status]()
            val wrote = guard(s2n_send(conn, buf.atUnsafe(offset + i), (n - i).toCSSize, blocked))
            (!blocked, Math.max(wrote, 0))
          }.productL(F.delay(writeTasks.get).flatten)
            .flatMap { case (blocked, wrote) =>
              val total = i + wrote
              go(total).unlessA(blocked.toInt == S2N_NOT_BLOCKED && total >= n)
            }

        go(0)
      }

      def shutdown =
        F.delay {
          readTasks.set(F.unit)
          writeTasks.set(F.unit)
          val blocked = stackalloc[s2n_blocked_status]()
          guard_(s2n_shutdown(conn, blocked))
          !blocked
        }.guaranteeCase { oc =>
          blindingSleep.whenA(oc.isError)
        }.productL {
          val reads = F.delay(readTasks.get).flatten
          val writes = F.delay(writeTasks.get).flatten
          reads.both(writes)
        }.iterateUntil(_.toInt == S2N_NOT_BLOCKED)
          .void

      def applicationProtocol =
        F.delay(guard(s2n_get_application_protocol(conn))).map(fromCString(_))

      def session = F.delay {
        val len = guard(s2n_connection_get_session_length(conn))
        val buf = new Array[Byte](len)
        val copied = guard(s2n_connection_get_session(conn, buf.atUnsafe(0), len.toUInt))
        new SSLSession(ByteVector.view(buf, 0, copied))
      }

      private def blindingSleep: F[Unit] =
        F.delay(s2n_connection_get_delay(conn).toLong.nanos)
          .flatMap(delay => F.sleep(delay))
    }

  final case class ConnectionContext[F[_]](
      privateKeyTasks: AtomicReference[F[Unit]],
      privateKeyCleanupTasks: AtomicReference[F[Unit]],
      async: Async[F]
  )

  private final case class RecvCallbackContext[F[_]](
      recvBuffer: AtomicReference[Option[ByteVector]],
      readTasks: AtomicReference[F[Unit]],
      socket: Socket[F],
      async: Async[F]
  )

  private def recvCallback[F[_]](ioContext: Ptr[Byte], buf: Ptr[Byte], len: CUnsignedInt): CInt = {
    val ctx = fromPtr[RecvCallbackContext[F]](ioContext)
    import ctx._
    implicit val F = async

    val read = recvBuffer.getAndUpdate(_.map(_.drop(len.toLong))).map(_.take(len.toLong))

    read match {
      case Some(bytes) if bytes.nonEmpty =>
        bytes.copyToPtr(buf, 0)
        bytes.length.toInt
      case Some(_) =>
        val readTask =
          socket.read(len.toInt).flatMap(b => F.delay(recvBuffer.set(b.map(_.toByteVector)))).void
        readTasks.getAndUpdate(_ *> readTask)
        libc.errno.errno = posix.errno.EWOULDBLOCK
        S2N_FAILURE
      case None => 0
    }
  }

  private final case class SendCallbackContext[F[_]](
      sendAvailable: AtomicBoolean,
      writeTasks: AtomicReference[F[Unit]],
      socket: Socket[F],
      async: Async[F]
  )

  private def sendCallback[F[_]](ioContext: Ptr[Byte], buf: Ptr[Byte], len: CUnsignedInt): CInt = {
    val ctx = fromPtr[SendCallbackContext[F]](ioContext)
    import ctx._
    implicit val F = async

    if (sendAvailable.getAndSet(false)) {
      val writeTask =
        socket.write(Chunk.byteVector(ByteVector.fromPtr(buf, len.toLong))) *>
          F.delay(sendAvailable.set(true))
      writeTasks.getAndUpdate(_ *> writeTask)
      len.toInt
    } else {
      libc.errno.errno = posix.errno.EWOULDBLOCK
      S2N_FAILURE
    }
  }

}
