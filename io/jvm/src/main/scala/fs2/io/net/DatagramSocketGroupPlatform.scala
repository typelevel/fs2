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

import java.net.{InetSocketAddress, NetworkInterface}
import java.nio.channels.DatagramChannel

import cats.effect.kernel.{Async, Resource}
import cats.syntax.all._

import com.comcast.ip4s._

private[net] trait DatagramSocketGroupCompanionPlatform {
  type ProtocolFamily = java.net.ProtocolFamily

  def unsafe[F[_]: Async](adsg: AsynchronousDatagramSocketGroup): DatagramSocketGroup[F] =
    new AsyncDatagramSocketGroup(adsg)

  private final class AsyncDatagramSocketGroup[F[_]: Async](adsg: AsynchronousDatagramSocketGroup)
      extends DatagramSocketGroup[F] {
    def openDatagramSocket(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption],
        protocolFamily: Option[ProtocolFamily]
    ): Resource[F, DatagramSocket[F]] =
      Resource.eval(address.traverse(_.resolve[F])).flatMap { addr =>
        val mkChannel = Async[F].delay {
          val channel = protocolFamily
            .map(pf => DatagramChannel.open(pf))
            .getOrElse(DatagramChannel.open())
          options.foreach(o => channel.setOption[o.Value](o.key, o.value))
          channel.bind(
            new InetSocketAddress(addr.map(_.toInetAddress).orNull, port.map(_.value).getOrElse(0))
          )
          channel
        }
        Resource.make(mkChannel)(c => Async[F].delay(c.close())).flatMap(mkSocket)
      }

    private def mkSocket(
        channel: DatagramChannel
    ): Resource[F, DatagramSocket[F]] =
      Resource(Async[F].delay {
        val ctx0 = adsg.register(channel)

        val socket = new DatagramSocket[F] {
          private val ctx = ctx0

          def localAddress: F[SocketAddress[IpAddress]] =
            Async[F].delay {
              val addr =
                Option(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
                  .getOrElse(throw new ClosedChannelException)
              SocketAddress.fromInetSocketAddress(addr)
            }

          def read: F[Datagram] =
            Async[F].async[Datagram] { cb =>
              Async[F].delay {
                val cancel = adsg.read(ctx, result => cb(result))
                Some(Async[F].delay((cancel())))
              }
            }

          def reads: Stream[F, Datagram] =
            Stream.repeatEval(read)

          def write(datagram: Datagram): F[Unit] =
            Async[F].async[Unit] { cb =>
              Async[F].delay {
                val cancel = adsg.write(ctx, datagram, t => cb(t.toLeft(())))
                Some(Async[F].delay(cancel()))
              }
            }

          def writes: Pipe[F, Datagram, INothing] =
            _.foreach(write)

          def close: F[Unit] = Async[F].delay(adsg.close(ctx))

          def join(
              join: MulticastJoin[IpAddress],
              interface: NetworkInterface
          ): F[GroupMembership] =
            Async[F].delay {
              val membership = join.fold(
                j => channel.join(j.group.address.toInetAddress, interface),
                j => channel.join(j.group.address.toInetAddress, interface, j.source.toInetAddress)
              )
              new GroupMembership {
                def drop = Async[F].delay(membership.drop)
                def block(source: IpAddress) =
                  Async[F].delay { membership.block(source.toInetAddress); () }
                def unblock(source: IpAddress) =
                  Async[F].delay { membership.unblock(source.toInetAddress); () }
                override def toString = "GroupMembership"
              }
            }

          override def toString =
            s"Socket(${Option(
              channel.socket.getLocalSocketAddress
            ).getOrElse("<unbound>")})"
        }

        (socket, Async[F].delay(adsg.close(ctx0)))
      })

  }
}
