package fs2
package io
package udp

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import java.net.{InetAddress, InetSocketAddress, NetworkInterface}
import java.nio.channels.{ClosedChannelException, DatagramChannel}

import cats.effect.{Effect, IO}

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
    * If `timeout` is specified, then resulting sink will fail with `java.nio.channels.InterruptedByTimeoutException`
    * if a write was not completed in given timeout.
    */
  def writes(timeout: Option[FiniteDuration] = None): Sink[F, Packet]

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

private[udp] object Socket {

  private[fs2] def mkSocket[F[_]](channel: DatagramChannel)(implicit AG: AsynchronousSocketGroup,
                                                            F: Effect[F],
                                                            ec: ExecutionContext): F[Socket[F]] =
    F.delay {
      new Socket[F] {
        private val ctx = AG.register(channel)

        private def invoke(f: => Unit): Unit =
          async.unsafeRunAsync(F.delay(f))(_ => IO.pure(()))

        def localAddress: F[InetSocketAddress] =
          F.delay(
            Option(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
              .getOrElse(throw new ClosedChannelException))

        def read(timeout: Option[FiniteDuration]): F[Packet] =
          F.async(cb => AG.read(ctx, timeout, result => invoke(cb(result))))

        def reads(timeout: Option[FiniteDuration]): Stream[F, Packet] =
          Stream.repeatEval(read(timeout))

        def write(packet: Packet, timeout: Option[FiniteDuration]): F[Unit] =
          F.async(cb => AG.write(ctx, packet, timeout, t => invoke(cb(t.toLeft(())))))

        def writes(timeout: Option[FiniteDuration]): Sink[F, Packet] =
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
