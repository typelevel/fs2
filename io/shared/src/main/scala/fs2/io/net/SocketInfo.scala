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

import cats.MonadThrow
import cats.syntax.all._
import com.comcast.ip4s.{GenSocketAddress, IpAddress, SocketAddress}

trait SocketInfo[F[_]] {

  /** Asks for the local address of this socket. Like `localAddress` but supports unix sockets as well. */
  def localAddressGen: F[GenSocketAddress]

  def supportedOptions: F[Set[SocketOption.Key[?]]]

  def getOption[A](key: SocketOption.Key[A]): F[Option[A]]

  def setOption[A](key: SocketOption.Key[A], value: A): F[Unit]
}

object SocketInfo extends SocketInfoCompanionPlatform {
  private[net] def downcastAddress[F[_]: MonadThrow](address: F[GenSocketAddress]): F[SocketAddress[IpAddress]] =
    address.flatMap {
      case a: SocketAddress[IpAddress] @unchecked => MonadThrow[F].pure(a)
      case _ => MonadThrow[F].raiseError(new UnsupportedOperationException("invalid address type"))
    }
}
