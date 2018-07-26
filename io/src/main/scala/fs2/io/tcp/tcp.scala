package fs2
package io

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import cats.effect.{ConcurrentEffect, Resource, Timer}

/** Provides support for TCP networking. */
package object tcp {

  /**
    * Stream that connects to the specified server and emits a single socket,
    * allowing reads/writes via operations on the socket. The socket is closed
    * when the outer stream terminates.
    *
    * @param to                   address of remote server
    * @param reuseAddress         whether address may be reused (see `java.net.StandardSocketOptions.SO_REUSEADDR`)
    * @param sendBufferSize       size of send buffer  (see `java.net.StandardSocketOptions.SO_SNDBUF`)
    * @param receiveBufferSize    size of receive buffer  (see `java.net.StandardSocketOptions.SO_RCVBUF`)
    * @param keepAlive            whether keep-alive on tcp is used (see `java.net.StandardSocketOptions.SO_KEEPALIVE`)
    * @param noDelay              whether tcp no-delay flag is set  (see `java.net.StandardSocketOptions.TCP_NODELAY`)
    */
  def client[F[_]](
      to: InetSocketAddress,
      reuseAddress: Boolean = true,
      sendBufferSize: Int = 256 * 1024,
      receiveBufferSize: Int = 256 * 1024,
      keepAlive: Boolean = false,
      noDelay: Boolean = false
  )(implicit AG: AsynchronousChannelGroup,
    F: ConcurrentEffect[F],
    timer: Timer[F]): Resource[F, Socket[F]] =
    Socket.client(to, reuseAddress, sendBufferSize, receiveBufferSize, keepAlive, noDelay)

  /**
    * Stream that binds to the specified address and provides a connection for,
    * represented as a [[Socket]], for each client that connects to the bound address.
    *
    * Returns a stream of stream of sockets.
    *
    * The outer stream scopes the lifetime of the server socket.
    * When the outer stream terminates, all open connections will terminate as well.
    * The outer stream emits an element (an inner stream) for each client connection.
    *
    * Each inner stream represents an individual connection, and as such, is a stream
    * that emits a single socket. Failures that occur in an inner stream do *NOT* cause
    * the outer stream to fail.
    *
    * @param bind               address to accept connections from
    * @param maxQueued          number of queued requests before they will become rejected by server
    *                           (supply <= 0 for unbounded)
    * @param reuseAddress       whether address may be reused (see `java.net.StandardSocketOptions.SO_REUSEADDR`)
    * @param receiveBufferSize  size of receive buffer (see `java.net.StandardSocketOptions.SO_RCVBUF`)
    */
  def server[F[_]](bind: InetSocketAddress,
                   maxQueued: Int = 0,
                   reuseAddress: Boolean = true,
                   receiveBufferSize: Int = 256 * 1024)(
      implicit AG: AsynchronousChannelGroup,
      F: ConcurrentEffect[F],
      timer: Timer[F]
  ): Stream[F, Resource[F, Socket[F]]] =
    serverWithLocalAddress(bind, maxQueued, reuseAddress, receiveBufferSize)
      .collect { case Right(s) => s }

  /**
    * Like [[server]] but provides the `InetSocketAddress` of the bound server socket before providing accepted sockets.
    *
    * The outer stream first emits a left value specifying the bound address followed by right values -- one per client connection.
    */
  def serverWithLocalAddress[F[_]](bind: InetSocketAddress,
                                   maxQueued: Int = 0,
                                   reuseAddress: Boolean = true,
                                   receiveBufferSize: Int = 256 * 1024)(
      implicit AG: AsynchronousChannelGroup,
      F: ConcurrentEffect[F],
      timer: Timer[F]
  ): Stream[F, Either[InetSocketAddress, Resource[F, Socket[F]]]] =
    Socket.server(bind, maxQueued, reuseAddress, receiveBufferSize)
}
