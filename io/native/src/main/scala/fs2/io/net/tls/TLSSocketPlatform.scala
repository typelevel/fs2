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

private[tls] trait TLSSocketPlatform[F[_]]

private[tls] trait TLSSocketCompanionPlatform { self: TLSSocket.type =>

  private[tls] def forAsync[F[_]]: Resource[F, TLSSocket[F]] = ???

  private[tls] final class AsyncTLSSocket[F[_]: Async] extends UnsealedTLSSocket[F] {
    // Members declared in fs2.io.net.Socket
    def endOfInput: F[Unit] = ???
    def endOfOutput: F[Unit] = ???
    def isOpen: F[Boolean] = ???
    def localAddress: F[com.comcast.ip4s.SocketAddress[com.comcast.ip4s.IpAddress]] = ???
    def read(maxBytes: Int): F[Option[fs2.Chunk[Byte]]] = ???
    def readN(numBytes: Int): F[fs2.Chunk[Byte]] = ???
    def reads: fs2.Stream[F, Byte] = ???
    def remoteAddress: F[com.comcast.ip4s.SocketAddress[com.comcast.ip4s.IpAddress]] = ???
    def write(bytes: fs2.Chunk[Byte]): F[Unit] = ???
    def writes: fs2.Pipe[F, Byte, Nothing] = ???

    // Members declared in fs2.io.net.tls.TLSSocket
    def applicationProtocol: F[String] = ???
    def session: F[fs2.io.net.tls.SSLSession] = ???
  }
}
