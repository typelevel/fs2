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

import java.net.InetSocketAddress

import cats.syntax.all._
import cats.effect.{Async, Resource}
import com.comcast.ip4s.{Dns, Host, NetworkInterface, SocketAddress}

import fs2.internal.ThreadFactories
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
import com.comcast.ip4s.*
import java.net.{NetworkInterface => JNetworkInterface}
import CollectionCompat.*

private[net] object AsyncIpDatagramSocketsProvider {

  private[net] def forAsync[F[_]: Async]: IpDatagramSocketsProvider[F] =
    new IpDatagramSocketsProvider[F] {

      private implicit val dnsInstance: Dns[F] = Dns.forAsync[F]

      private lazy val globalAdsg =
        AsynchronousDatagramSocketGroup.unsafe(ThreadFactories.named("fs2-global-udp", true))

      override def bindDatagramSocket(
          address: SocketAddress[Host],
          options: List[SocketOption]
      ): Resource[F, DatagramSocket[F]] =
        Resource.eval(address.resolve[F]).flatMap { addr =>
          val mkChannel = Async[F].delay {
            val pf =
              addr.host.fold(_ => StandardProtocolFamily.INET, _ => StandardProtocolFamily.INET6)
            val channel = DatagramChannel.open(pf)
            options.foreach(o => channel.setOption[o.Value](o.key, o.value))
            channel.bind(
              new InetSocketAddress(
                if (addr.host.isWildcard) null else addr.host.toInetAddress,
                addr.port.value
              )
            )
            channel
          }
          Resource
            .make(mkChannel)(c => Async[F].delay(c.close()))
            .flatMap(mkDatagramSocket(_, globalAdsg))
        }

      private def mkDatagramSocket(
          channel: DatagramChannel,
          adsg: AsynchronousDatagramSocketGroup
      ): Resource[F, DatagramSocket[F]] =
        Resource(Async[F].delay {
          val ctx0 = adsg.register(channel)

          val socket = new DatagramSocket[F] {
            private val ctx = ctx0

            override val address: GenSocketAddress =
              SocketAddress.fromInetSocketAddress(
                channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress]
              )

            override def localAddress: F[SocketAddress[IpAddress]] =
              Async[F].pure(address.asIpUnsafe)

            override def connect(address: GenSocketAddress) =
              Async[F].blocking(channel.connect(address.asIpUnsafe.toInetSocketAddress)).void

            override def disconnect =
              Async[F].blocking(channel.disconnect()).void

            override def readGen: F[GenDatagram] = read.map(_.toGenDatagram)

            override def read: F[Datagram] =
              Async[F].async[Datagram] { cb =>
                Async[F].delay {
                  val cancel = adsg.read(ctx, result => cb(result))
                  Some(Async[F].delay(cancel()))
                }
              }

            override def reads: Stream[F, Datagram] =
              Stream.repeatEval(read)

            override def write(bytes: Chunk[Byte]) =
              write(bytes, None)

            override def write(bytes: Chunk[Byte], address: GenSocketAddress) =
              write(bytes, Some(address.asIpUnsafe.toInetSocketAddress))

            private def write(bytes: Chunk[Byte], address: Option[InetSocketAddress]) =
              Async[F].async[Unit] { cb =>
                Async[F].delay {
                  val cancel = adsg.write(ctx, bytes, address, t => cb(t.toLeft(())))
                  Some(Async[F].delay(cancel()))
                }
              }

            override def write(datagram: Datagram): F[Unit] =
              write(datagram.bytes, datagram.remote)

            override def writes: Pipe[F, Datagram, Nothing] =
              _.foreach(write)

            override def join(
                join: MulticastJoin[IpAddress],
                interface: NetworkInterface
            ): F[GroupMembership] =
              Async[F].delay {
                val jinterface = JNetworkInterface.getByName(interface.name)
                val membership = join.fold(
                  j => channel.join(j.group.address.toInetAddress, jinterface),
                  j =>
                    channel.join(j.group.address.toInetAddress, jinterface, j.source.toInetAddress)
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

            override def join(
                j: MulticastJoin[IpAddress],
                interface: JNetworkInterface
            ): F[GroupMembership] =
              join(j, NetworkInterface.fromJava(interface))

            override def supportedOptions: F[Set[SocketOption.Key[?]]] =
              Async[F].delay {
                channel.supportedOptions.asScala.toSet
              }

            override def getOption[A](key: SocketOption.Key[A]): F[Option[A]] =
              Async[F].delay {
                try
                  Some(channel.getOption(key))
                catch {
                  case _: UnsupportedOperationException => None
                }
              }

            override def setOption[A](key: SocketOption.Key[A], value: A): F[Unit] =
              Async[F].delay {
                channel.setOption(key, value)
                ()
              }

            override def toString =
              s"DatagramSocket($address)"
          }

          (socket, Async[F].delay(adsg.close(ctx0)))
        })

    }
}
