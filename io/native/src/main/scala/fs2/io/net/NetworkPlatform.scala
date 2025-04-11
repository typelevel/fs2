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

import cats.ApplicativeThrow
import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.{Async, Resource}

import com.comcast.ip4s.{Dns, GenSocketAddress, Host, IpAddress, Port, SocketAddress, UnixSocketAddress}

private[net] trait NetworkPlatform[F[_]]

private[net] trait NetworkCompanionPlatform extends NetworkLowPriority { self: Network.type =>

  private def matchAddress[F[_]: ApplicativeThrow, A](address: GenSocketAddress, ifIp: SocketAddress[Host] => F[A], ifUnix: UnixSocketAddress => F[A]): F[A] =
    address match {
      case sa: SocketAddress[Host] => ifIp(sa)
      case ua: UnixSocketAddress => ifUnix(ua)
      case other => ApplicativeThrow[F].raiseError(new UnsupportedOperationException(s"Unsupported address type: $other"))
    }

  def forIO: Network[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Async: LiftIO]: Network[F] =
    new AsyncNetwork[F] {
      private lazy val ipSockets = new FdPollingIpSocketsProvider[F]()(Dns.forAsync, implicitly, implicitly)
      private lazy val unixSockets = new FdPollingUnixSocketsProvider[F]

      def connect(
        address: GenSocketAddress,
        options: List[SocketOption]
      ): Resource[F, Socket[F]] =
        matchAddress(address,
          sa => ipSockets.connect(sa, options),
          ua => unixSockets.connect(ua, options))

      def bind(
        address: GenSocketAddress,
        options: List[SocketOption]
      ): Resource[F, ServerSocket[F]] =
        matchAddress(address,
          sa => ipSockets.bind(sa, options),
          ua => unixSockets.bind(ua, options))

    }
}
