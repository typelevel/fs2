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
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import fs2.io.internal.facade

import scala.scalajs.js

private[net] trait DatagramSocketGroupCompanionPlatform {
  type ProtocolFamily = String

  private[net] def forAsync[F[_]: Async]: DatagramSocketGroup[F] =
    new AsyncDatagramSocketGroup[F]

  private final class AsyncDatagramSocketGroup[F[_]](implicit F: Async[F])
      extends DatagramSocketGroup[F] {

    private def setSocketOptions(options: List[DatagramSocketOption])(
        socket: facade.dgram.Socket
    ): F[Unit] =
      options.traverse(option => option.key.set(socket, option.value)).void

    override def openDatagramSocket(
        address: Option[Host],
        port: Option[Port],
        options: List[DatagramSocketOption],
        protocolFamily: Option[ProtocolFamily]
    ): Resource[F, DatagramSocket[F]] = for {
      sock <- F
        .delay(facade.dgram.createSocket(protocolFamily.getOrElse("udp4")))
        .flatTap(setSocketOptions(options))
        .toResource
      socket <- DatagramSocket.forAsync[F](sock)
      _ <- F
        .async_[Unit] { cb =>
          val errorListener: js.Function1[js.Error, Unit] = { error =>
            cb(Left(js.JavaScriptException(error)))
          }
          sock.once[js.Error]("error", errorListener)
          val options = new facade.dgram.BindOptions {}
          address.map(_.toString).foreach(options.address = _)
          port.map(_.value).foreach(options.port = _)
          sock.bind(
            options,
            { () =>
              sock.removeListener("error", errorListener)
              cb(Right(()))
            }
          )
        }
        .toResource
    } yield socket
  }
}
