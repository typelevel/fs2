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
package tls

import java.net.NetworkInterface

import cats.Applicative
import cats.effect.kernel.{Async, Resource, Sync}
import cats.syntax.all._

import com.comcast.ip4s._

/** UDP socket that supports encryption via DTLS.
  *
  * To construct a `DTLSSocket`, use the `dtlsClient` and `dtlsServer` methods on `TLSContext`.
  */
sealed trait DTLSSocket[F[_]] extends DatagramSocket[F] {

  /** Initiates handshaking -- either the initial or a renegotiation. */
  def beginHandshake: F[Unit]

  /** Provides access to the current `SSLSession` for purposes of querying
    * session info such as the negotiated cipher suite or the peer certificate.
    */
  def session: F[SSLSession]
}

object DTLSSocket {

  private[tls] def apply[F[_]: Async](
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress],
      engine: TLSEngine[F]
  ): Resource[F, DTLSSocket[F]] =
    Resource.make(mk(socket, remoteAddress, engine))(_ => engine.stopWrap >> engine.stopUnwrap)

  private def mk[F[_]: Async](
      socket: DatagramSocket[F],
      remoteAddress: SocketAddress[IpAddress],
      engine: TLSEngine[F]
  ): F[DTLSSocket[F]] =
    Applicative[F].pure {
      new DTLSSocket[F] {

        def read: F[Datagram] =
          engine.read(Int.MaxValue).flatMap {
            case Some(bytes) => Applicative[F].pure(Datagram(remoteAddress, bytes))
            case None        => read
          }

        def reads: Stream[F, Datagram] =
          Stream.repeatEval(read)
        def write(datagram: Datagram): F[Unit] =
          engine.write(datagram.bytes)

        def writes: Pipe[F, Datagram, Nothing] =
          _.foreach(write)
        def localAddress: F[SocketAddress[IpAddress]] = socket.localAddress
        def join(join: MulticastJoin[IpAddress], interface: NetworkInterface): F[GroupMembership] =
          Sync[F].raiseError(new RuntimeException("DTLSSocket does not support multicast"))
        def beginHandshake: F[Unit] = engine.beginHandshake
        def session: F[SSLSession] = engine.session
      }
    }
}
