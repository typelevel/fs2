/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package io
package net

import cats.effect.kernel.{Async, Resource}

import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}

import fs2.internal.ThreadFactories
import fs2.io.net.tls.TLSContext

import java.net.ProtocolFamily
import java.nio.channels.AsynchronousChannelGroup

/** Provides the ability to work with TCP, UDP, and TLS.
  *
  * @example {{{
  * import fs2.Stream
  * import fs2.io.net.{Datagram, Network}
  *
  * def send[F[_]: Network](datagram: Datagram): F[Unit] =
  *   Network[F].openDatagramSocket().use { socket =>
  *     socket.write(packet)
  *   }
  * }}}
  *
  * In this example, the `F[_]` parameter to `send` requires the `Network` constraint instead
  * of requiring the much more powerful `Async` constraint.
  *
  * An instance of `Network` is available for any effect `F` which has an `Async[F]` instance.
  */
sealed trait Network[F[_]] {

  /** Opens a TCP connection to the specified server.
    *
    * The connection is closed when the resource is released.
    *
    * @param to      address of remote server
    * @param options socket options to apply to the underlying socket
    */
  def client(
      to: SocketAddress[Host],
      options: List[SocketOption] = List.empty
  ): Resource[F, Socket[F]]

  /** Creates a TCP server bound to specified address/port and returns a stream of
    * client sockets -- one per client that connects to the bound address/port.
    *
    * When the stream terminates, all open connections will terminate as well.
    *
    * @param address            address to accept connections from; none for all interfaces
    * @param port               port to bind
    * @param options socket options to apply to the underlying socket
    */
  def server(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = List.empty
  ): Stream[F, Socket[F]]

  /** Like [[server]] but provides the `SocketAddress` of the bound server socket before providing accepted sockets.
    */
  def serverResource(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = List.empty
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])]

  /** Creates a UDP socket bound to the specified address.
    *
    * @param address              address to bind to; defaults to all interfaces
    * @param port                 port to bind to; defaults to an ephemeral port
    * @param options              socket options to apply to the underlying socket
    * @param protocolFamily       protocol family to use when opening the supporting `DatagramChannel`
    */
  def openDatagramSocket(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = Nil,
      protocolFamily: Option[ProtocolFamily] = None
  ): Resource[F, DatagramSocket[F]]

  /** Returns a builder for `TLSContext[F]` values.
    *
    * For example, `Network[IO].tlsContext.system` returns a `F[TLSContext[F]]`.
    */
  def tlsContext: TLSContext.Builder[F]
}

object Network {
  private lazy val acg = AsynchronousChannelGroup.withFixedThreadPool(
    1,
    ThreadFactories.named("fs2-tcp-socket-group", true)
  )
  private lazy val adsg = AsynchronousDatagramSocketGroup.unsafeMake

  def apply[F[_]](implicit F: Network[F]): F.type = F

  implicit def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    new Network[F] {
      private lazy val socketGroup = new SocketGroup[F](acg)
      private lazy val datagramSocketGroup = new DatagramSocketGroup[F](adsg)

      def client(
          to: SocketAddress[Host],
          options: List[SocketOption]
      ): Resource[F, Socket[F]] = socketGroup.client(to, options)

      def server(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Stream[F, Socket[F]] = socketGroup.server(address, port, options)

      def serverResource(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
        socketGroup.serverResource(address, port, options)

      def openDatagramSocket(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption],
          protocolFamily: Option[ProtocolFamily]
      ): Resource[F, DatagramSocket[F]] =
        datagramSocketGroup.open(address, port, options, protocolFamily)

      def tlsContext: TLSContext.Builder[F] = TLSContext.Builder.forAsync[F]
    }
}
