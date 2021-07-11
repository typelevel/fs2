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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import com.comcast.ip4s.Host
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.io.net.tls.TLSContext

private[net] trait NetworkPlatform[F[_]]

private[net] trait NetworkCompanionPlatform { self: Network.type =>
  implicit def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    new UnsealedNetwork[F] {

      private lazy val socketGroup = SocketGroup.forAsync[F]
      private lazy val datagramSocketGroup = DatagramSocketGroup.forAsync[F]

      override def client(
          to: SocketAddress[Host],
          options: List[SocketOption]
      ): Resource[F, Socket[F]] =
        socketGroup.client(to, options)

      override def server(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Stream[F, Socket[F]] =
        socketGroup.server(address, port, options)

      override def serverResource(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
        socketGroup.serverResource(address, port, options)

      override def openDatagramSocket(
          address: Option[Host],
          port: Option[Port],
          options: List[DatagramSocketOption],
          protocolFamily: Option[DatagramSocketGroup.ProtocolFamily]
      ): Resource[F, DatagramSocket[F]] =
        datagramSocketGroup.openDatagramSocket(address, port, options, protocolFamily)

      override def tlsContext: TLSContext.Builder[F] = TLSContext.Builder.forAsync

    }
}
