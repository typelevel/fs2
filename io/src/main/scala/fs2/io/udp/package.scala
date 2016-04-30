package fs2.io

import java.net.InetSocketAddress

import fs2._

/**
  * Created by pach on 30/04/16.
  */
package object udp {

  /**
    * A single packet received from given destination
    * @param remote   Remote party to send/receive packet to/from
    * @param bytes    All bytes received
    */
  case class Packet(remote: InetSocketAddress, bytes: Chunk.Bytes)

  /**
    * Provides a stream of UDP Socket that when run will bind to specified adress by `bind`.
    *
    * Note that implementation will allocate dedicated thread that reads data received.
    * Once data are read, then the execution continues with the Strategy defined by `F`.
    *
    *
    *
    * @param queueMax             Implementation will tr to read at most `queueMax` messages before read from socket
    *                             then, it will stop read any more data, which may cause data being dropped.
    * @param reuseAddress         whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
    * @param receiveBufferSize    size of receive buffer (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
    * @param sendBufferSize       size of send buffer  (@see [[java.net.StandardSocketOptions.SO_SNDBUF]])
    */
  def listen[F[_],A](
    bind: InetSocketAddress
    , queueMax:Int = Int.MaxValue
    , receiveBufferSize: Int = 1024 * 32
    , sendBufferSize: Int = 1024 * 32
    , reuseAddress: Boolean = true
  )(implicit  F:Async[F]): Stream[F,Socket[F]] =
    Socket.listen(bind,queueMax,receiveBufferSize,sendBufferSize,reuseAddress)


}
