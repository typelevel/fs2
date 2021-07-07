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
import cats.syntax.all._
import com.comcast.ip4s.{Host, Port}
import fs2.js.node.dgramMod

private[net] trait DatagramSocketGroupCompanionPlatform {
  type ProtocolFamily = dgramMod.SocketType

  private[net] def forAsync[F[_]: Async]: DatagramSocketGroup[F] =
    new AsyncDatagramSocketGroup[F]

  private final class AsyncDatagramSocketGroup[F[_]](implicit F: Async[F])
      extends DatagramSocketGroup[F] {

    private def setSocketOptions(options: List[DatagramSocketOption])(
        socket: dgramMod.Socket
    ): F[Unit] =
      options.traverse(option => option.key.set(socket, option.value)).void

    override def openDatagramSocket(
        address: Option[Host],
        port: Option[Port],
        options: List[DatagramSocketOption],
        protocolFamily: Option[ProtocolFamily]
    ): Resource[F, DatagramSocket[F]] = Resource
      .eval {
        for {
          sock <- F.delay(dgramMod.createSocket(protocolFamily.getOrElse(dgramMod.SocketType.udp4)))
          _ <- F.async_[Unit] { cb =>
            val options = port.foldLeft(
              address.foldLeft(dgramMod.BindOptions())((opt, addr) => opt.setAddress(addr.toString))
            )((opt, port) => opt.setPort(port.value.toDouble))
            sock.bind(options, () => cb(Right(())))
          }
          _ <- setSocketOptions(options)(sock)
        } yield sock
      }
      .flatMap(DatagramSocket.forAsync[F])

  }
}
