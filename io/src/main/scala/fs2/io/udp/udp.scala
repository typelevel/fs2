package fs2
package io

import scala.concurrent.ExecutionContext

import java.net.{InetSocketAddress, NetworkInterface, ProtocolFamily, StandardSocketOptions}
import java.nio.channels.DatagramChannel

import cats.effect.Effect
import cats.implicits._

/** Provides support for UDP networking. */
package object udp {

  /**
    * Provides a singleton stream of a UDP Socket that, when run, will bind to the specified adress.
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
  )(implicit AG: AsynchronousSocketGroup,
    F: Effect[F],
    ec: ExecutionContext): Stream[F, Socket[F]] = {
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
    Stream.bracket(mkChannel.flatMap(ch => Socket.mkSocket(ch)))(s => Stream.emit(s), _.close)
  }
}
