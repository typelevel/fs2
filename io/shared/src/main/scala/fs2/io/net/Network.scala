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

import cats.ApplicativeThrow
import cats.effect.{Async, IO, Resource}
import com.comcast.ip4s.{
  Dns,
  GenSocketAddress,
  Host,
  IpAddress,
  Ipv4Address,
  NetworkInterfaces,
  Port,
  SocketAddress,
  UnixSocketAddress
}
import fs2.io.net.tls.TLSContext

/** Provides the ability to work with stream sockets (e.g. TCP), datagram sockets (e.g. UDP), and TLS.
  *
  * Both IP and unix sockets are supported, though unix socket support depends on the underlying platform support.
  * The socket type is derived from addresses supplied to operations that open new sockets.
  *
  * @example {{{
  * import fs2.Stream
  * import fs2.io.net.{Datagram, Network}
  *
  * def send[F[_]: Network](datagram: Datagram): F[Unit] =
  *   Network[F].bindDatagramSocket().use { socket =>
  *     socket.write(datagram)
  *   }
  * }}}
  *
  * In this example, the `F[_]` parameter to `send` requires the `Network` constraint instead
  * of requiring the much more powerful `Async` constraint.
  *
  * An instance of `Network` is available for any effect `F` which has a `LiftIO[F]` instance.
  */
sealed trait Network[F[_]]
    extends NetworkPlatform[F]
    with SocketGroup[F]
    with DatagramSocketGroup[F] {

  /** Opens a stream socket and connects it to the supplied address.
    *
    * TCP is used when the supplied address contains an IP address or hostname. Unix sockets are also
    * supported (when the supplied address contains a unix socket address).
    *
    * @param address              address to connect to
    * @param options              socket options to apply to the socket
    */
  def connect(address: GenSocketAddress, options: List[SocketOption] = Nil): Resource[F, Socket[F]]

  /** Opens and binds a stream server socket to the supplied address.
    *
    * TCP is used when the supplied address contains an IP address or hostname. Unix sockets are also
    * supported (when the supplied address contains a unix socket address).
    *
    * @param address              address to bind to
    * @param options              socket options to apply to each accepted socket
    */
  def bind(
      address: GenSocketAddress = SocketAddress.Wildcard,
      options: List[SocketOption] = Nil
  ): Resource[F, ServerSocket[F]]

  /** Opens and binds a stream server socket to the supplied address and returns a stream of
    * client sockets, each representing a client connection to the server.
    *
    * @param address              address to bind to
    * @param options              socket options to apply to each accepted socket
    */
  def bindAndAccept(
      address: GenSocketAddress = SocketAddress.Wildcard,
      options: List[SocketOption] = Nil
  ): Stream[F, Socket[F]]

  /** Opens and binds a datagram socket bound to the specified address.
    *
    * @param address              address to bind to
    * @param options              socket options to apply to the socket
    */
  def bindDatagramSocket(
      address: GenSocketAddress = SocketAddress.Wildcard,
      options: List[SocketOption] = Nil
  ): Resource[F, DatagramSocket[F]]

  /** Returns a builder for `TLSContext[F]` values.
    *
    * For example, `Network[IO].tlsContext.system` returns a `F[TLSContext[F]]`.
    */
  def tlsContext: TLSContext.Builder[F]

  /** Explicit instance of `Dns[F]`. */
  def dns: Dns[F]

  /** Explicit instance of `NetworkInterfaces[F]`. */
  def interfaces: NetworkInterfaces[F]
}

object Network extends NetworkCompanionPlatform {
  def forIO: Network[IO] = forLiftIO

  private[fs2] trait UnsealedNetwork[F[_]] extends Network[F]

  private[fs2] abstract class AsyncNetwork[F[_]](implicit F: Async[F]) extends Network[F] {

    def dns: Dns[F] = Dns.forAsync(F)
    def interfaces: NetworkInterfaces[F] = NetworkInterfaces.forAsync(F)

    override def bindAndAccept(
        address: GenSocketAddress,
        options: List[SocketOption]
    ): Stream[F, Socket[F]] =
      Stream.resource(bind(address, options)).flatMap(_.accept)

    override def tlsContext: TLSContext.Builder[F] = TLSContext.Builder.forAsync[F]

    protected def matchAddress[G[_]: ApplicativeThrow, A](
        address: GenSocketAddress,
        ifIp: SocketAddress[Host] => G[A],
        ifUnix: UnixSocketAddress => G[A]
    ): G[A] =
      address match {
        case sa: SocketAddress[Host] => ifIp(sa)
        case ua: UnixSocketAddress   => ifUnix(ua)
        case other =>
          ApplicativeThrow[G].raiseError(
            new UnsupportedOperationException(s"Unsupported address type: $other")
          )
      }

    // Implementations of deprecated operations

    override def client(
        to: SocketAddress[Host],
        options: List[SocketOption]
    ): Resource[F, Socket[F]] = connect(to, options)

    override def server(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption]
    ): Stream[F, Socket[F]] = Stream.resource(serverResource(address, port, options)).flatMap(_._2)

    override def serverResource(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption]
    ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
      bind(
        SocketAddress(address.getOrElse(Ipv4Address.Wildcard), port.getOrElse(Port.Wildcard)),
        options
      )
        .map(ss => ss.address.asIpUnsafe -> ss.accept)

    override def openDatagramSocket(
        address: Option[Host],
        port: Option[Port],
        options: List[DatagramSocketOption],
        protocolFamily: Option[DatagramSocketGroup.ProtocolFamily]
    ): Resource[F, DatagramSocket[F]] =
      bindDatagramSocket(
        SocketAddress(address.getOrElse(Ipv4Address.Wildcard), port.getOrElse(Port.Wildcard)),
        options.map(_.toSocketOption)
      )
  }

  private[fs2] abstract class AsyncProviderBasedNetwork[F[_]](implicit F: Async[F])
      extends AsyncNetwork[F] {
    protected def mkIpSocketsProvider: IpSocketsProvider[F]
    protected def mkUnixSocketsProvider: UnixSocketsProvider[F]
    protected def mkIpDatagramSocketsProvider: IpDatagramSocketsProvider[F]
    protected def mkUnixDatagramSocketsProvider: UnixDatagramSocketsProvider[F]

    protected lazy val ipSockets: IpSocketsProvider[F] = mkIpSocketsProvider
    protected lazy val unixSockets: UnixSocketsProvider[F] = mkUnixSocketsProvider
    protected lazy val ipDatagramSockets: IpDatagramSocketsProvider[F] = mkIpDatagramSocketsProvider
    protected lazy val unixDatagramSockets: UnixDatagramSocketsProvider[F] =
      mkUnixDatagramSocketsProvider

    override def connect(
        address: GenSocketAddress,
        options: List[SocketOption]
    ): Resource[F, Socket[F]] =
      matchAddress(
        address,
        sa => ipSockets.connectIp(sa, options),
        ua => unixSockets.connectUnix(ua, options)
      )

    override def bind(
        address: GenSocketAddress,
        options: List[SocketOption]
    ): Resource[F, ServerSocket[F]] =
      matchAddress(
        address,
        sa => ipSockets.bindIp(sa, options),
        ua => unixSockets.bindUnix(ua, options)
      )

    override def bindDatagramSocket(
        address: GenSocketAddress,
        options: List[SocketOption] = Nil
    ): Resource[F, DatagramSocket[F]] =
      matchAddress(
        address,
        sa => ipDatagramSockets.bindDatagramSocket(sa, options),
        ua => unixDatagramSockets.bindDatagramSocket(ua, options)
      )
  }

  def apply[F[_]](implicit F: Network[F]): F.type = F
}
