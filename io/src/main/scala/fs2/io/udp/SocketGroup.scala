package fs2
package io
package udp

import scala.concurrent.duration.FiniteDuration

import java.net.{
  InetAddress,
  InetSocketAddress,
  NetworkInterface,
  ProtocolFamily,
  StandardSocketOptions
}
import java.nio.channels.{ClosedChannelException, DatagramChannel}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.implicits._

final class SocketGroup(
    asg: AsynchronousSocketGroup,
    blocker: Blocker
) {

  /**
    * Provides a UDP Socket that, when run, will bind to the specified address.
    *
    * @param address              address to bind to; defaults to an ephemeral port on all interfaces
    * @param reuseAddress         whether address has to be reused (see `java.net.StandardSocketOptions.SO_REUSEADDR`)
    * @param sendBufferSize       size of send buffer  (see `java.net.StandardSocketOptions.SO_SNDBUF`)
    * @param receiveBufferSize    size of receive buffer (see `java.net.StandardSocketOptions.SO_RCVBUF`)
    * @param allowBroadcast       whether broadcast messages are allowed to be sent; defaults to true
    * @param protocolFamily       protocol family to use when opening the supporting `DatagramChannel`
    * @param multicastInterface   network interface for sending multicast packets
    * @param multicastTTL         time to live of sent multicast packets
    * @param multicastLoopback    whether sent multicast packets should be looped back to this host
    */
  def open[F[_]](
      address: InetSocketAddress = new InetSocketAddress(0),
      reuseAddress: Boolean = false,
      sendBufferSize: Option[Int] = None,
      receiveBufferSize: Option[Int] = None,
      allowBroadcast: Boolean = true,
      protocolFamily: Option[ProtocolFamily] = None,
      multicastInterface: Option[NetworkInterface] = None,
      multicastTTL: Option[Int] = None,
      multicastLoopback: Boolean = true
  )(implicit F: Concurrent[F], CS: ContextShift[F]): Resource[F, Socket[F]] = {
    val mkChannel = blocker.delay {
      val channel = protocolFamily
        .map(pf => DatagramChannel.open(pf))
        .getOrElse(DatagramChannel.open())
      channel.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      sendBufferSize.foreach { sz =>
        channel.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sz)
      }
      receiveBufferSize.foreach { sz =>
        channel.setOption[Integer](StandardSocketOptions.SO_RCVBUF, sz)
      }
      channel.setOption[java.lang.Boolean](StandardSocketOptions.SO_BROADCAST, allowBroadcast)
      multicastInterface.foreach { iface =>
        channel.setOption[NetworkInterface](StandardSocketOptions.IP_MULTICAST_IF, iface)
      }
      multicastTTL.foreach { ttl =>
        channel.setOption[Integer](StandardSocketOptions.IP_MULTICAST_TTL, ttl)
      }
      channel
        .setOption[java.lang.Boolean](StandardSocketOptions.IP_MULTICAST_LOOP, multicastLoopback)
      channel.bind(address)
      channel
    }
    Resource(mkChannel.flatMap(ch => mkSocket(ch).map(s => s -> s.close)))
  }

  private[udp] def mkSocket[F[_]](
      channel: DatagramChannel
  )(implicit F: Concurrent[F], cs: ContextShift[F]): F[Socket[F]] =
    blocker.delay {
      new Socket[F] {
        private val ctx = asg.register(channel)

        def localAddress: F[InetSocketAddress] =
          F.delay(
            Option(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
              .getOrElse(throw new ClosedChannelException)
          )

        def read(timeout: Option[FiniteDuration]): F[Packet] =
          asyncYield[F, Packet](cb => asg.read(ctx, timeout, result => cb(result)))

        def reads(timeout: Option[FiniteDuration]): Stream[F, Packet] =
          Stream.repeatEval(read(timeout))

        def write(packet: Packet, timeout: Option[FiniteDuration]): F[Unit] =
          asyncYield[F, Unit](cb => asg.write(ctx, packet, timeout, t => cb(t.toLeft(()))))

        def writes(timeout: Option[FiniteDuration]): Pipe[F, Packet, Unit] =
          _.flatMap(p => Stream.eval(write(p, timeout)))

        def close: F[Unit] = blocker.delay(asg.close(ctx))

        def join(group: InetAddress, interface: NetworkInterface): F[AnySourceGroupMembership] =
          blocker.delay {
            val membership = channel.join(group, interface)
            new AnySourceGroupMembership {
              def drop = blocker.delay(membership.drop)
              def block(source: InetAddress) =
                F.delay {
                  membership.block(source); ()
                }
              def unblock(source: InetAddress) =
                blocker.delay {
                  membership.unblock(source); ()
                }
              override def toString = "AnySourceGroupMembership"
            }
          }

        def join(
            group: InetAddress,
            interface: NetworkInterface,
            source: InetAddress
        ): F[GroupMembership] =
          F.delay {
            val membership = channel.join(group, interface, source)
            new GroupMembership {
              def drop = blocker.delay(membership.drop)
              override def toString = "GroupMembership"
            }
          }

        override def toString =
          s"Socket(${Option(
            channel.socket.getLocalSocketAddress
              .asInstanceOf[InetSocketAddress]
          ).getOrElse("<unbound>")})"
      }
    }
}

object SocketGroup {
  def apply[F[_]: Sync: ContextShift](blocker: Blocker): Resource[F, SocketGroup] =
    AsynchronousSocketGroup[F](blocker).map(asg => new SocketGroup(asg, blocker))
}
