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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Mutex
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress

private[tls] trait TLSSocketPlatform[F[_]]

private[tls] trait TLSSocketCompanionPlatform { self: TLSSocket.type =>

  private[tls] def apply[F[_]: Async](
      socket: Socket[F],
      connection: S2nConnection[F]
  ): Resource[F, TLSSocket[F]] =
    Resource.eval(mk(socket, connection)) <*
      Resource.makeFull[F, Unit](poll => poll(connection.handshake))(_ =>
        connection.shutdown.attempt.void
      )

  def mk[F[_]](
      socket: Socket[F],
      connection: S2nConnection[F]
  )(implicit F: Async[F]): F[TLSSocket[F]] =
    for {
      readMutex <- Mutex[F]
      writeMutex <- Mutex[F]
    } yield new UnsealedTLSSocket[F] {
      def write(bytes: Chunk[Byte]): F[Unit] =
        writeMutex.lock.surround(connection.write(bytes))

      private def read0(maxBytes: Int): F[Option[Chunk[Byte]]] =
        connection.read(maxBytes)

      def readN(numBytes: Int): F[Chunk[Byte]] =
        readMutex.lock.surround {
          def go(acc: Chunk[Byte]): F[Chunk[Byte]] = {
            val toRead = numBytes - acc.size
            if (toRead <= 0) F.pure(acc)
            else
              read0(toRead).flatMap {
                case Some(chunk) => go(acc ++ chunk)
                case None        => F.pure(acc)
              }
          }
          go(Chunk.empty)
        }

      def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
        readMutex.lock.surround(read0(maxBytes))

      def reads: Stream[F, Byte] =
        Stream.repeatEval(read(8192)).unNoneTerminate.unchunks

      def writes: Pipe[F, Byte, Nothing] =
        _.chunks.foreach(write)

      def endOfOutput: F[Unit] =
        socket.endOfOutput

      def endOfInput: F[Unit] =
        socket.endOfInput

      def localAddress: F[SocketAddress[IpAddress]] =
        socket.localAddress

      def remoteAddress: F[SocketAddress[IpAddress]] =
        socket.remoteAddress

      def session: F[SSLSession] = connection.session

      def applicationProtocol: F[String] = connection.applicationProtocol

      def isOpen: F[Boolean] = socket.isOpen
    }
}
