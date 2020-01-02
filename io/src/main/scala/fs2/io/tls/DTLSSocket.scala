package fs2
package io
package tls

import scala.concurrent.duration._

import java.net.{InetAddress, InetSocketAddress, NetworkInterface}
import javax.net.ssl.SSLSession

import cats.Applicative
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._

import fs2.io.udp.{Packet, Socket}

/**
  * UDP socket that supports encryption via DTLS.
  *
  * To construct a `DTLSSocket`, use the `dtlsClient` and `dtlsServer` methods on `TLSContext`.
  */
sealed trait DTLSSocket[F[_]] extends Socket[F] {

  /** Initiates handshaking -- either the initial or a renegotiation. */
  def beginHandshake: F[Unit]

  /**
    * Provides access to the current `SSLSession` for purposes of querying
    * session info such as the negotiated cipher suite or the peer certificate.
    */
  def session: F[SSLSession]
}

object DTLSSocket {

  private[tls] def apply[F[_]: Concurrent](
      socket: Socket[F],
      remoteAddress: InetSocketAddress,
      engine: TLSEngine[F]
  ): Resource[F, DTLSSocket[F]] =
    Resource.make(mk(socket, remoteAddress, engine))(_.close)

  private def mk[F[_]: Concurrent](
      socket: Socket[F],
      remoteAddress: InetSocketAddress,
      engine: TLSEngine[F]
  ): F[DTLSSocket[F]] =
    Applicative[F].pure {
      new DTLSSocket[F] {

        private def binding(
            writeTimeout: Option[FiniteDuration]
        ): TLSEngine.Binding[F] = new TLSEngine.Binding[F] {
          def write(data: Chunk[Byte]) =
            if (data.isEmpty) Applicative[F].unit
            else socket.write(Packet(remoteAddress, data), writeTimeout)
          def read = socket.read(None).map(p => Some(p.bytes))
        }
        def read(timeout: Option[FiniteDuration] = None): F[Packet] =
          socket.read(timeout).flatMap { p =>
            engine.unwrap(p.bytes, binding(None)).flatMap {
              case Some(bytes) => Applicative[F].pure(Packet(p.remote, bytes))
              case None        => read(timeout)
            }
          }
        def reads(timeout: Option[FiniteDuration] = None): Stream[F, Packet] =
          Stream.repeatEval(read(timeout))
        def write(packet: Packet, timeout: Option[FiniteDuration] = None): F[Unit] =
          engine.wrap(packet.bytes, binding(writeTimeout = timeout))

        def writes(timeout: Option[FiniteDuration] = None): Pipe[F, Packet, Unit] =
          _.flatMap(p => Stream.eval(write(p, timeout)))
        def localAddress: F[InetSocketAddress] = socket.localAddress
        def close: F[Unit] = socket.close
        def join(group: InetAddress, interface: NetworkInterface): F[AnySourceGroupMembership] =
          Sync[F].raiseError(new RuntimeException("DTLSSocket does not support multicast"))
        def join(
            group: InetAddress,
            interface: NetworkInterface,
            source: InetAddress
        ): F[GroupMembership] =
          Sync[F].raiseError(new RuntimeException("DTLSSocket does not support multicast"))
        def beginHandshake: F[Unit] = engine.beginHandshake
        def session: F[SSLSession] = engine.session
      }
    }
}
