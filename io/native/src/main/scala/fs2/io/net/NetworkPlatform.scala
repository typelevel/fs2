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

import cats.effect.kernel.{Async, Resource}

import com.comcast.ip4s.{Dns, Host, IpAddress, Port, SocketAddress}

import fs2.io.net.tls.TLSContext

private[net] trait NetworkPlatform[F[_]]

private[net] trait NetworkCompanionPlatform { self: Network.type =>

  implicit def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    new UnsealedNetwork[F] {
      private lazy val globalSocketGroup = SocketGroup.unsafe[F](null)
      private implicit val dnsInstance: Dns[F] =
        Dns.forAsync(F) // Temporary until forAsync is made non-implicit

      def client(
          to: SocketAddress[Host],
          options: List[SocketOption]
      ): Resource[F, Socket[F]] = globalSocketGroup.client(to, options)

      def server(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Stream[F, Socket[F]] = globalSocketGroup.server(address, port, options)

      def serverResource(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
        globalSocketGroup.serverResource(address, port, options)

      def tlsContext: TLSContext.Builder[F] = TLSContext.Builder.forAsync
    }

}
