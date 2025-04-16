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

import com.comcast.ip4s.{GenSocketAddress, SocketAddress}
import cats.effect.Async
import fs2.io.internal.facade

private[net] trait SocketInfoCompanionPlatform {
  private[net] def forAsync[F[_]](sock: facade.net.Socket)(implicit F: Async[F]): SocketInfo[F] = {
    val sock0 = sock
    new AsyncSocketInfo[F] {
      def asyncInstance = F
      def sock: facade.net.Socket = sock0
    }
  }

  private[net] trait AsyncSocketInfo[F[_]] extends SocketInfo[F] {

    implicit protected def asyncInstance: Async[F]

    protected def sock: facade.net.Socket

    override def localAddressGen: F[GenSocketAddress] = ???

    override def supportedOptions: F[Set[SocketOption.Key[?]]] = ???

    override def getOption[A](key: SocketOption.Key[A]): F[Option[A]] =
      key.get(sock)

    override def setOption[A](key: SocketOption.Key[A], value: A): F[Unit] =
      key.set(sock, value)
  }
}
