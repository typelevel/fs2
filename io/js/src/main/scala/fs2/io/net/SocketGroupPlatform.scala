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
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._
import com.comcast.ip4s.Host
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.internal.jsdeps.node.eventsMod
import fs2.internal.jsdeps.node.netMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.io.internal.EventEmitterOps._

import scala.scalajs.js

private[net] trait SocketGroupCompanionPlatform { self: SocketGroup.type =>

  private[net] def forAsync[F[_]: Async]: SocketGroup[F] = new AsyncSocketGroup[F]

  private[net] final class AsyncSocketGroup[F[_]](implicit F: Async[F])
      extends AbstractAsyncSocketGroup[F] {

    private def setSocketOptions(options: List[SocketOption])(socket: netMod.Socket): F[Unit] =
      options.traverse(option => option.key.set(socket, option.value)).void

    override def client(
        to: SocketAddress[Host],
        options: List[SocketOption]
    ): Resource[F, Socket[F]] =
      (for {
        sock <- F
          .delay(
            new netMod.Socket(netMod.SocketConstructorOpts().setAllowHalfOpen(true))
          )
          .flatTap(setSocketOptions(options))
          .toResource
        socket <- Socket.forAsync(sock)
        _ <- F
          .async[Unit] { cb =>
            registerListener[js.Error](sock, nodeStrings.error)(_.once_error(_, _)) { error =>
              cb(Left(js.JavaScriptException(error)))
            }.evalTap(_ =>
              F.delay(sock.connect(to.port.value.toDouble, to.host.toString, () => cb(Right(()))))
            ).allocated
              .map { case ((), cancel) => Some(cancel) }
          }
          .toResource
      } yield socket).adaptError { case IOException(ex) => ex }

    override def serverResource(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption]
    ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
      (for {
        dispatcher <- Dispatcher[F]
        queue <- Queue.unbounded[F, Option[netMod.Socket]].toResource
        server <- Resource.make(
          F
            .delay(
              netMod.createServer(
                netMod.ServerOpts().setPauseOnConnect(true).setAllowHalfOpen(true),
                sock => dispatcher.unsafeRunAndForget(queue.offer(Some(sock)))
              )
            )
        )(server =>
          F.async[Unit] { cb =>
            if (server.listening)
              F.delay(server.close(e => cb(e.toLeft(()).leftMap(js.JavaScriptException)))) >> queue
                .offer(None)
                .as(None)
            else
              F.delay(cb(Right(()))).as(None)
          }
        )
        _ <- F
          .async_[Unit] { cb =>
            server.once_error(nodeStrings.error, e => cb(Left(js.JavaScriptException(e))))
            server.listen(
              address.foldLeft(
                netMod.ListenOptions().setPort(port.fold(0.0)(_.value.toDouble))
              )((opts, host) => opts.setHost(host.toString)),
              () => {
                server.asInstanceOf[eventsMod.EventEmitter].removeAllListeners("error")
                cb(Right(()))
              }
            )
          }
          .toResource
        ipAddress <- F
          .delay(server.address())
          .map { address =>
            val info = address.asInstanceOf[netMod.AddressInfo]
            SocketAddress(IpAddress.fromString(info.address).get, Port.fromInt(info.port.toInt).get)
          }
          .toResource
        sockets = Stream
          .fromQueueNoneTerminated(queue)
          .evalTap(setSocketOptions(options))
          .flatMap(sock => Stream.resource(Socket.forAsync(sock)))
      } yield (ipAddress, sockets)).adaptError { case IOException(ex) => ex }

  }

}
