package fs2.io

import java.net.{NetworkInterface,StandardSocketOptions,SocketAddress,InetSocketAddress}
import java.nio.channels.DatagramChannel

import fs2._

package object udp {

  /**
    * A single packet received from given destination
    * @param remote   Remote party to send/receive packet to/from
    * @param bytes    All bytes received
    */
  case class Packet(remote: SocketAddress, bytes: Chunk.Bytes)

  /**
    * Provides a singleton stream of a UDP Socket that when run will bind to specified adress
    * by `bind`.
    *
    * @param bind
    * @param reuseAddress         whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
    * @param sendBufferSize       size of send buffer  (@see [[java.net.StandardSocketOptions.SO_SNDBUF]])
    * @param receiveBufferSize    size of receive buffer (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
    * @param allowBroadcast
    * @param multicastInterface
    * @param multicastTTL
    * @param multicastLoopback
    */
  def open[F[_]](
    bind: SocketAddress = new InetSocketAddress(0)
    , reuseAddress: Boolean = false
    , sendBufferSize: Option[Int] = None
    , receiveBufferSize: Option[Int] = None
    , allowBroadcast: Boolean = false
    , multicastInterface: Option[NetworkInterface] = None
    , multicastTTL: Option[Int] = None
    , multicastLoopback: Boolean = true
  )(implicit AG: AsynchronousSocketGroup, F: Async[F], FR: Async.Run[F]): Stream[F,Socket[F]] = {
    val mkChannel = F.delay {
      val channel = DatagramChannel.open()
      channel.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      sendBufferSize.foreach { sz => channel.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sz) }
      receiveBufferSize.foreach { sz => channel.setOption[Integer](StandardSocketOptions.SO_RCVBUF, sz) }
      channel.setOption[java.lang.Boolean](StandardSocketOptions.SO_BROADCAST, allowBroadcast)
      multicastInterface.foreach { iface => channel.setOption[NetworkInterface](StandardSocketOptions.IP_MULTICAST_IF, iface) }
      multicastTTL.foreach { ttl => channel.setOption[Integer](StandardSocketOptions.IP_MULTICAST_TTL, ttl) }
      channel.setOption[java.lang.Boolean](StandardSocketOptions.IP_MULTICAST_LOOP, multicastLoopback)
      channel.socket.bind(bind)
      channel
    }
    Stream.bracket(mkChannel)(
      ch => Stream.bracket(Socket.mkSocket(ch, bind.toString))(s => Stream.emit(s), _.close),
      ch => F.delay(ch.close)
    )
  }
}

