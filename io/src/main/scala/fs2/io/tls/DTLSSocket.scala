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
package tls

import scala.concurrent.duration._

import java.net.{InetAddress, InetSocketAddress, NetworkInterface}
import javax.net.ssl.SSLSession

import cats.Applicative
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._

import fs2.io.udp.{Packet, Socket}

/** UDP socket that supports encryption via DTLS.
  *
  * To construct a `DTLSSocket`, use the `dtlsClient` and `dtlsServer` methods on `TLSContext`.
  */
sealed trait DTLSSocket[F[_]] extends Socket[F] {

  /** Initiates handshaking -- either the initial or a renegotiation. */
  def beginHandshake: F[Unit]

  /** Provides access to the current `SSLSession` for purposes of querying
    * session info such as the negotiated cipher suite or the peer certificate.
    */
  def session: F[SSLSession]
}

object DTLSSocket {

  private[tls] def apply[F[_]: Async](
      socket: Socket[F],
      remoteAddress: InetSocketAddress,
      engine: TLSEngine[F]
  ): Resource[F, DTLSSocket[F]] =
    Resource.make(mk(socket, remoteAddress, engine))(_.close)

  private def mk[F[_]: Async](
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
