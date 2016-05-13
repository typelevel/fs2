package fs2.io

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import fs2._


package object tcp {


  /**
    * Process that connects to remote server (TCP) and runs the stream `ouput`.
    *
    * @param to                   Address of remote server
    * @param reuseAddress         whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
    * @param sendBufferSize       size of send buffer  (@see [[java.net.StandardSocketOptions.SO_SNDBUF]])
    * @param receiveBufferSize    size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
    * @param keepAlive            whether keep-alive on tcp is used (@see [[java.net.StandardSocketOptions.SO_KEEPALIVE]])
    * @param noDelay              whether tcp no-delay flag is set  (@see [[java.net.StandardSocketOptions.TCP_NODELAY]])
    */
  def client[F[_]: Async](
    to: InetSocketAddress
    , reuseAddress: Boolean = true
    , sendBufferSize: Int = 256 * 1024
    , receiveBufferSize: Int = 256 * 1024
    , keepAlive: Boolean = false
    , noDelay: Boolean = false
  )( implicit AG: AsynchronousChannelGroup): Stream[F,Socket[F]] =
  Socket.client(to,reuseAddress,sendBufferSize,receiveBufferSize,keepAlive,noDelay)



  /**
    * Process that binds to supplied address and handles incoming TCP connections
    * using the specified handler.
    *
    * The outer stream returned scopes the lifetime of the server socket.
    * When the returned process terminates, all open connections will terminate as well.
    *
    * The inner streams represents individual connections, handled by `handler`. If
    * any inner stream fails, this will _NOT_ cause the server connection to fail/close/terminate.
    *
    * @param bind               address to which this process has to be bound
    * @param maxQueued          Number of queued requests before they will become rejected by server
    *                           Supply <= 0 if unbounded
    * @param reuseAddress       whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
    * @param receiveBufferSize  size of receive buffer (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
    */
  def server[F[_]:Async](
    bind: InetSocketAddress
    , maxQueued: Int = 0
    , reuseAddress: Boolean = true
    , receiveBufferSize: Int = 256 * 1024)(
    implicit AG: AsynchronousChannelGroup
  ): Stream[F, Stream[F, Socket[F]]] =
  Socket.server(bind,maxQueued,reuseAddress,receiveBufferSize)



}
