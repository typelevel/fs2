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

import cats.implicits._
import cats.effect.{Concurrent, ContextShift, Resource}

/**
  * Provides the ability to read/write from a UDP socket in the effect `F`.
  *
  * To construct a `Socket`, use the methods in the [[fs2.io.udp]] package object.
  */
sealed trait Socket[F[_]] {

  /**
    * Reads a single packet from this udp socket.
    *
    * If `timeout` is specified, then resulting `F` will fail with `java.nio.channels.InterruptedByTimeoutException`
    * if read was not satisfied in given timeout.
    */
  def read(timeout: Option[FiniteDuration] = None): F[Packet]

  /**
    * Reads packets received from this udp socket.
    *
    * Note that multiple `reads` may execute at same time, causing each evaluation to receive fair
    * amount of messages.
    *
    * If `timeout` is specified, then resulting stream will fail with `java.nio.channels.InterruptedByTimeoutException`
    * if a read was not satisfied in given timeout.
    *
    * @return stream of packets
    */
  def reads(timeout: Option[FiniteDuration] = None): Stream[F, Packet]

  /**
    * Write a single packet to this udp socket.
    *
    * If `timeout` is specified, then resulting `F` will fail with `java.nio.channels.InterruptedByTimeoutException`
    * if write was not completed in given timeout.
    *
    * @param packet  Packet to write
    */
  def write(packet: Packet, timeout: Option[FiniteDuration] = None): F[Unit]

  /**
    * Writes supplied packets to this udp socket.
    *
    * If `timeout` is specified, then resulting pipe will fail with `java.nio.channels.InterruptedByTimeoutException`
    * if a write was not completed in given timeout.
    */
  def writes(timeout: Option[FiniteDuration] = None): Pipe[F, Packet, Unit]

  /** Returns the local address of this udp socket. */
  def localAddress: F[InetSocketAddress]

  /** Closes this socket. */
  def close: F[Unit]

  /**
    * Joins a multicast group on a specific network interface.
    *
    * @param group address of group to join
    * @param interface network interface upon which to listen for datagrams
    */
  def join(group: InetAddress, interface: NetworkInterface): F[AnySourceGroupMembership]

  /**
    * Joins a source specific multicast group on a specific network interface.
    *
    * @param group address of group to join
    * @param interface network interface upon which to listen for datagrams
    * @param source limits received packets to those sent by the source
    */
  def join(group: InetAddress, interface: NetworkInterface, source: InetAddress): F[GroupMembership]

  /** Result of joining a multicast group on a UDP socket. */
  sealed trait GroupMembership {

    /** Leaves the multicast group, resulting in no further packets from this group being read. */
    def drop: F[Unit]
  }

  /** Result of joining an any-source multicast group on a UDP socket. */
  sealed trait AnySourceGroupMembership extends GroupMembership {

    /** Blocks packets from the specified source address. */
    def block(source: InetAddress): F[Unit]

    /** Unblocks packets from the specified source address. */
    def unblock(source: InetAddress): F[Unit]
  }
}

object Socket {

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
  def apply[F[_]](
      address: InetSocketAddress = new InetSocketAddress(0),
      reuseAddress: Boolean = false,
      sendBufferSize: Option[Int] = None,
      receiveBufferSize: Option[Int] = None,
      allowBroadcast: Boolean = true,
      protocolFamily: Option[ProtocolFamily] = None,
      multicastInterface: Option[NetworkInterface] = None,
      multicastTTL: Option[Int] = None,
      multicastLoopback: Boolean = true
  )(implicit AG: AsynchronousSocketGroup,
    F: Concurrent[F],
    CS: ContextShift[F]): Resource[F, Socket[F]] =
    mk(address,
       reuseAddress,
       sendBufferSize,
       receiveBufferSize,
       allowBroadcast,
       protocolFamily,
       multicastInterface,
       multicastTTL,
       multicastLoopback)

  private[udp] def mk[F[_]](
      address: InetSocketAddress = new InetSocketAddress(0),
      reuseAddress: Boolean = false,
      sendBufferSize: Option[Int] = None,
      receiveBufferSize: Option[Int] = None,
      allowBroadcast: Boolean = true,
      protocolFamily: Option[ProtocolFamily] = None,
      multicastInterface: Option[NetworkInterface] = None,
      multicastTTL: Option[Int] = None,
      multicastLoopback: Boolean = true
  )(implicit AG: AsynchronousSocketGroup,
    F: Concurrent[F],
    Y: AsyncYield[F]): Resource[F, Socket[F]] = {
    val mkChannel = F.delay {
      val channel = protocolFamily
        .map { pf =>
          DatagramChannel.open(pf)
        }
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

  private[udp] def mkSocket[F[_]](channel: DatagramChannel)(implicit AG: AsynchronousSocketGroup,
                                                            F: Concurrent[F],
                                                            Y: AsyncYield[F]): F[Socket[F]] =
    F.delay {
      new Socket[F] {
        private val ctx = AG.register(channel)

        def localAddress: F[InetSocketAddress] =
          F.delay(
            Option(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
              .getOrElse(throw new ClosedChannelException))

        def read(timeout: Option[FiniteDuration]): F[Packet] =
          Y.asyncYield[Packet](cb => AG.read(ctx, timeout, result => cb(result)))

        def reads(timeout: Option[FiniteDuration]): Stream[F, Packet] =
          Stream.repeatEval(read(timeout))

        def write(packet: Packet, timeout: Option[FiniteDuration]): F[Unit] =
          Y.asyncYield[Unit](cb => AG.write(ctx, packet, timeout, t => cb(t.toLeft(()))))

        def writes(timeout: Option[FiniteDuration]): Pipe[F, Packet, Unit] =
          _.flatMap(p => Stream.eval(write(p, timeout)))

        def close: F[Unit] = F.delay { AG.close(ctx) }

        def join(group: InetAddress, interface: NetworkInterface): F[AnySourceGroupMembership] =
          F.delay {
            val membership = channel.join(group, interface)
            new AnySourceGroupMembership {
              def drop = F.delay { membership.drop }
              def block(source: InetAddress) = F.delay {
                membership.block(source); ()
              }
              def unblock(source: InetAddress) = F.delay {
                membership.unblock(source); ()
              }
              override def toString = "AnySourceGroupMembership"
            }
          }

        def join(group: InetAddress,
                 interface: NetworkInterface,
                 source: InetAddress): F[GroupMembership] = F.delay {
          val membership = channel.join(group, interface, source)
          new GroupMembership {
            def drop = F.delay { membership.drop }
            override def toString = "GroupMembership"
          }
        }

        override def toString =
          s"Socket(${Option(channel.socket.getLocalSocketAddress
            .asInstanceOf[InetSocketAddress]).getOrElse("<unbound>")})"
      }
    }
}
