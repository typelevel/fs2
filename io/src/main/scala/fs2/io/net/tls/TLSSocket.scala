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

import javax.net.ssl.SSLSession

import cats.Applicative
import cats.effect.std.Semaphore
import cats.effect.kernel._
import cats.syntax.all._

import com.comcast.ip4s.{IpAddress, SocketAddress}

import fs2.io.net.Socket

/** TCP socket that supports encryption via TLS.
  *
  * To construct a `TLSSocket`, use the `client` and `server` methods on `TLSContext`.
  */
sealed trait TLSSocket[F[_]] extends Socket[F] {

  /** Initiates handshaking -- either the initial or a renegotiation. */
  def beginHandshake: F[Unit]

  /** Provides access to the current `SSLSession` for purposes of querying
    * session info such as the negotiated cipher suite or the peer certificate.
    */
  def session: F[SSLSession]

  /** Provides access to the current application protocol that has been negotiated.
    */
  def applicationProtocol: F[String]
}

object TLSSocket {

  private[tls] def apply[F[_]: Async](
      socket: Socket[F],
      engine: TLSEngine[F]
  ): Resource[F, TLSSocket[F]] =
    Resource.make(mk(socket, engine))(_ => engine.stopWrap >> engine.stopUnwrap)

  private def mk[F[_]: Async](
      socket: Socket[F],
      engine: TLSEngine[F]
  ): F[TLSSocket[F]] =
    for {
      readSem <- Semaphore(1)
    } yield new TLSSocket[F] {
      def write(bytes: Chunk[Byte]): F[Unit] =
        engine.write(bytes)

      private def read0(maxBytes: Int): F[Option[Chunk[Byte]]] =
        engine.read(maxBytes)

      def readN(numBytes: Int): F[Option[Chunk[Byte]]] =
        readSem.permit.use { _ =>
          def go(acc: Chunk[Byte]): F[Option[Chunk[Byte]]] = {
            val toRead = numBytes - acc.size
            if (toRead <= 0) Applicative[F].pure(Some(acc))
            else
              read0(toRead).flatMap {
                case Some(chunk) => go(acc ++ chunk): F[Option[Chunk[Byte]]]
                case None        => Applicative[F].pure(Some(acc)): F[Option[Chunk[Byte]]]
              }
          }
          go(Chunk.empty)
        }

      def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
        readSem.permit.use(_ => read0(maxBytes))

      def reads(maxBytes: Int): Stream[F, Byte] =
        Stream.repeatEval(read(maxBytes)).unNoneTerminate.flatMap(Stream.chunk)

      def writes: Pipe[F, Byte, INothing] =
        _.chunks.foreach(write)

      def endOfOutput: F[Unit] =
        engine.stopWrap >> socket.endOfOutput

      def endOfInput: F[Unit] =
        engine.stopUnwrap >> socket.endOfInput

      def localAddress: F[SocketAddress[IpAddress]] =
        socket.localAddress

      def remoteAddress: F[SocketAddress[IpAddress]] =
        socket.remoteAddress

      def beginHandshake: F[Unit] =
        engine.beginHandshake

      def session: F[SSLSession] =
        engine.session

      def applicationProtocol: F[String] =
        engine.applicationProtocol

      def isOpen: F[Boolean] = socket.isOpen
    }
}
