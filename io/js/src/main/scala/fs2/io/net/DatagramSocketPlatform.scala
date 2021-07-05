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

import cats.effect.kernel.Async
import com.comcast.ip4s.{IpAddress, SocketAddress}
import com.comcast.ip4s.{IpAddress, MulticastJoin}
import fs2.io.internal.ByteChunkOps._

import scala.scalajs.js
import typings.node.dgramMod
import cats.syntax.all._
import cats.effect.syntax.all._
import com.comcast.ip4s.Port
import com.comcast.ip4s.AnySourceMulticastJoin
import com.comcast.ip4s.SourceSpecificMulticastJoin
import cats.effect.std.Queue
import cats.effect.std.Dispatcher
import cats.effect.kernel.Resource
import typings.node.nodeStrings

private[net] trait DatagramSocketPlatform[F[_]] {
  private[net] trait GroupMembershipPlatform
}

private[net] trait DatagramSocketCompanionPlatform {
  type NetworkInterface = String

  private[net] def forAsync[F[_]](
      sock: dgramMod.Socket
  )(implicit F: Async[F]): Resource[F, DatagramSocket[F]] =
    Dispatcher[F].flatMap { dispatcher =>
      Resource.make(
        for {
          queue <- Queue.circularBuffer[F, Datagram](1)
          _ <- F.delay {
            sock.on_message(
              nodeStrings.message,
              (buffer, info) =>
                dispatcher.unsafeRunAndForget(
                  queue.offer(
                    Datagram(
                      SocketAddress(
                        IpAddress.fromString(info.address).get,
                        Port.fromInt(info.port.toInt).get
                      ),
                      buffer.toChunk
                    )
                  )
                )
            )
          }
        } yield new AsyncDatagramSocket(sock, queue)
      )(_ => F.delay(sock.close()))
    }

  private final class AsyncDatagramSocket[F[_]](sock: dgramMod.Socket, queue: Queue[F, Datagram])(
      implicit F: Async[F]
  ) extends DatagramSocket[F] {

    override def read: F[Datagram] = queue.take

    override def reads: Stream[F, Datagram] = Stream.fromQueueUnterminated(queue)

    override def write(datagram: Datagram): F[Unit] = F.async_ { cb =>
      sock.send(
        datagram.bytes.toUint8Array,
        datagram.remote.port.value.toDouble,
        datagram.remote.host.toString,
        (err, _) =>
          Option(err.asInstanceOf[js.Error]).fold(cb(Right(())))(err =>
            cb(Left(js.JavaScriptException(err)))
          )
      )
    }

    override def writes: Pipe[F, Datagram, INothing] = _.foreach(write)

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
