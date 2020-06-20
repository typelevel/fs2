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

        def read(timeout: Option[FiniteDuration] = None): F[Packet] =
          engine.read(Int.MaxValue, timeout).flatMap {
            case Some(bytes) => Applicative[F].pure(Packet(remoteAddress, bytes))
            case None        => read(timeout)
          }

        def reads(timeout: Option[FiniteDuration] = None): Stream[F, Packet] =
          Stream.repeatEval(read(timeout))
        def write(packet: Packet, timeout: Option[FiniteDuration] = None): F[Unit] =
          engine.write(packet.bytes, timeout)

        def writes(timeout: Option[FiniteDuration] = None): Pipe[F, Packet, INothing] =
          _.foreach(write(_, timeout))
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
