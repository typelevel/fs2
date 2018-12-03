package fs2
package io

import java.net.{InetSocketAddress, NetworkInterface, ProtocolFamily}

import cats.effect.{ConcurrentEffect, Resource}

/** Provides support for UDP networking. */
package object udp {

  @deprecated("Use fs2.io.udp.Socket(...) instead", "1.0.1")
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
  )(implicit AG: AsynchronousSocketGroup, F: ConcurrentEffect[F]): Resource[F, Socket[F]] =
    Socket.mk(address,
              reuseAddress,
              sendBufferSize,
              receiveBufferSize,
              allowBroadcast,
              protocolFamily,
              multicastInterface,
              multicastTTL,
              multicastLoopback)

}
