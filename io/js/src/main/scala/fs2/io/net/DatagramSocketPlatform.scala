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

import cats.data.EitherT
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.AnySourceMulticastJoin
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.MulticastJoin
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import com.comcast.ip4s.SourceSpecificMulticastJoin
import fs2.io.internal.facade

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

private[net] trait DatagramSocketPlatform[F[_]] {
  private[net] trait GroupMembershipPlatform
}

private[net] trait DatagramSocketCompanionPlatform {
  type NetworkInterface = String

  private[net] def forAsync[F[_]](
      sock: facade.dgram.Socket
  )(implicit F: Async[F]): Resource[F, DatagramSocket[F]] =
    for {
      dispatcher <- Dispatcher.sequential[F]
      queue <- Queue
        .circularBuffer[F, Datagram](1024)
        .toResource // TODO how to set this? Or, bad design?
      error <- F.deferred[Throwable].toResource
      _ <- sock.registerListener2[F, Uint8Array, facade.dgram.RemoteInfo]("message", dispatcher) {
        (msg, rinfo) =>
          queue.offer(
            Datagram(
              SocketAddress(
                IpAddress.fromString(rinfo.address).get,
                Port.fromInt(rinfo.port).get
              ),
              Chunk.uint8Array(msg)
            )
          )
      }
      _ <- sock.registerListener[F, js.Error]("error", dispatcher) { e =>
        error.complete(js.JavaScriptException(e)).void
      }
      socket <- Resource.make(F.pure(new AsyncDatagramSocket(sock, queue, error)))(_ =>
        F.async_[Unit](cb => sock.close(() => cb(Right(()))))
      )
    } yield socket

  private final class AsyncDatagramSocket[F[_]](
      sock: facade.dgram.Socket,
      queue: Queue[F, Datagram],
      error: Deferred[F, Throwable]
  )(implicit
      F: Async[F]
  ) extends DatagramSocket[F] {

    override def read: F[Datagram] = EitherT(
      queue.take.race(error.get.flatMap(F.raiseError[Datagram]))
    ).merge

    override def reads: Stream[F, Datagram] = Stream
      .fromQueueUnterminated(queue)
      .concurrently(Stream.eval(error.get.flatMap(F.raiseError[Datagram])))

    override def write(datagram: Datagram): F[Unit] = F.async_ { cb =>
      sock.send(
        datagram.bytes.toUint8Array,
        datagram.remote.port.value,
        datagram.remote.host.toString,
        err => Option(err).fold(cb(Right(())))(err => cb(Left(js.JavaScriptException(err))))
      )
    }

    override def writes: Pipe[F, Datagram, Nothing] = _.foreach(write)

    override def localAddress: F[SocketAddress[IpAddress]] =
      F.delay {
        val info = sock.address()
        SocketAddress(IpAddress.fromString(info.address).get, Port.fromInt(info.port.toInt).get)
      }

    override def join(
        join: MulticastJoin[IpAddress],
        interface: NetworkInterface
    ): F[GroupMembership] = F
      .delay {
        join match {
          case AnySourceMulticastJoin(group) =>
            sock.addMembership(group.address.toString, interface)
          case SourceSpecificMulticastJoin(source, group) =>
            sock.addSourceSpecificMembership(source.toString, group.address.toString, interface)
        }
      }
      .as(new GroupMembership {

        override def drop: F[Unit] = F.delay {
          join match {
            case AnySourceMulticastJoin(group) =>
              sock.dropMembership(group.address.toString, interface)
            case SourceSpecificMulticastJoin(source, group) =>
              sock.dropSourceSpecificMembership(source.toString, group.address.toString, interface)
          }
        }
      })

  }
}
