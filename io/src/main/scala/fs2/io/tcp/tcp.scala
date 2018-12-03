package fs2
package io

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import cats.effect.{ConcurrentEffect, Resource}

/** Provides support for TCP networking. */
package object tcp {

  @deprecated("Use fs2.io.tcp.Socket.client", "1.0.1")
  def client[F[_]](
      to: InetSocketAddress,
      reuseAddress: Boolean = true,
      sendBufferSize: Int = 256 * 1024,
      receiveBufferSize: Int = 256 * 1024,
      keepAlive: Boolean = false,
      noDelay: Boolean = false
  )(implicit AG: AsynchronousChannelGroup, F: ConcurrentEffect[F]): Resource[F, Socket[F]] =
    Socket.mkClient(to, reuseAddress, sendBufferSize, receiveBufferSize, keepAlive, noDelay)

  @deprecated("Use fs2.io.tcp.Socket.server", "1.0.1")
  def server[F[_]](bind: InetSocketAddress,
                   maxQueued: Int = 0,
                   reuseAddress: Boolean = true,
                   receiveBufferSize: Int = 256 * 1024)(
      implicit AG: AsynchronousChannelGroup,
      F: ConcurrentEffect[F]
  ): Stream[F, Resource[F, Socket[F]]] =
    Socket
      .mkServerWithLocalAddress(bind, maxQueued, reuseAddress, receiveBufferSize)
      .collect { case Right(s) => s }

  @deprecated("Use fs2.io.tcp.Socket.serverWithLocalAddress", "1.0.1")
  def serverWithLocalAddress[F[_]](bind: InetSocketAddress,
                                   maxQueued: Int = 0,
                                   reuseAddress: Boolean = true,
                                   receiveBufferSize: Int = 256 * 1024)(
      implicit AG: AsynchronousChannelGroup,
      F: ConcurrentEffect[F]
  ): Stream[F, Either[InetSocketAddress, Resource[F, Socket[F]]]] =
    Socket.mkServerWithLocalAddress(bind, maxQueued, reuseAddress, receiveBufferSize)
}
