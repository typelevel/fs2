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

import java.net.{NetworkInterface => JNetworkInterface}

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
        override def connect(address: GenSocketAddress) =
          socket.connect(address)

        override def disconnect =
          socket.disconnect

        override def readGen: F[GenDatagram] = read.map(_.toGenDatagram)

        override def read: F[Datagram] =
          engine.read(Int.MaxValue).flatMap {
            case Some(bytes) => Applicative[F].pure(Datagram(remoteAddress, bytes))
            case None        => read
          }

        override def reads: Stream[F, Datagram] =
          Stream.repeatEval(read)

        override def write(bytes: Chunk[Byte]): F[Unit] =
          engine.write(bytes)

        override def write(bytes: Chunk[Byte], address: GenSocketAddress): F[Unit] =
          if (address == remoteAddress) engine.write(bytes)
          else
            new IOException(
              "Cannot write to a DTLSSocket with an address that differs from the connected address"
            ).raiseError

        override def write(datagram: Datagram): F[Unit] =
          engine.write(datagram.bytes)

        override def writes: Pipe[F, Datagram, Nothing] =
          _.foreach(write)

        override def address = socket.address

        @deprecated(
          "3.13.0",
          "Use address instead, which returns GenSocketAddress instead of F[SocketAddress[IpAddress]]. If ip and port are needed, call .asIpUnsafe"
        )
        override def localAddress: F[SocketAddress[IpAddress]] = socket.localAddress

        override def join(
            join: MulticastJoin[IpAddress],
            interface: NetworkInterface
        ): F[GroupMembership] =
          Sync[F].raiseError(new RuntimeException("DTLSSocket does not support multicast"))

        override def join(
            join: MulticastJoin[IpAddress],
            interface: JNetworkInterface
        ): F[GroupMembership] =
          Sync[F].raiseError(new RuntimeException("DTLSSocket does not support multicast"))

        override def supportedOptions = socket.supportedOptions
        override def getOption[A](key: SocketOption.Key[A]) = socket.getOption(key)
        override def setOption[A](key: SocketOption.Key[A], value: A) = socket.setOption(key, value)

        override def beginHandshake: F[Unit] = engine.beginHandshake
        override def session: F[SSLSession] = engine.session
      }
    }
}
