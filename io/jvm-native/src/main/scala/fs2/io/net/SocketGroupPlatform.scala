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

import cats.syntax.all._
import cats.effect.kernel.{Async, Resource}
import com.comcast.ip4s.{Host, IpAddress, Ipv4Address, Port, SocketAddress}

private[net] trait SocketGroupCompanionPlatform { self: SocketGroup.type =>

  def fromIpSockets[F[_]: Async](ipSockets: IpSocketsProvider[F]): SocketGroup[F] = new SocketGroup[F] {
    def client(to: SocketAddress[Host], options: List[SocketOption]) =
      ipSockets.connect(to, options)

    def server(address: Option[Host], port: Option[Port], options: List[SocketOption]): Stream[F, Socket[F]] =
      Stream.resource(serverResource(address, port, options)).flatMap(_._2)

    def serverResource(address: Option[Host], port: Option[Port], options: List[SocketOption]): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
      ipSockets.bind(SocketAddress(address.getOrElse(Ipv4Address.Wildcard), port.getOrElse(Port.Wildcard)), options).evalMap(b => b.socketInfo.localAddress.tupleRight(b.clients))
  }
}
