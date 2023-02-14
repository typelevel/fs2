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

import java.net.InetSocketAddress
import java.nio.channels.{
  AsynchronousCloseException,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import java.nio.channels.AsynchronousChannelGroup
import cats.syntax.all._
import cats.effect.syntax.all._
import cats.effect.kernel.{Async, Resource}
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.concurrent.Channel

private[net] trait SocketGroupCompanionPlatform { self: SocketGroup.type =>
  private[fs2] def unsafe[F[_]: Async](channelGroup: AsynchronousChannelGroup): SocketGroup[F] =
    new AsyncSocketGroup[F](channelGroup)

  private final class AsyncSocketGroup[F[_]: Async](channelGroup: AsynchronousChannelGroup)
      extends AbstractAsyncSocketGroup[F] {

    def client(
        to: SocketAddress[Host],
        options: List[SocketOption]
    ): Resource[F, Socket[F]] = {
      def setup: Resource[F, AsynchronousSocketChannel] =
        Resource
          .make(
            Async[F].delay(
              AsynchronousSocketChannel.open(channelGroup)
            )
          )(ch => Async[F].delay(if (ch.isOpen) ch.close else ()))
          .evalTap(ch => Async[F].delay(options.foreach(opt => ch.setOption(opt.key, opt.value))))

      def connect(ch: AsynchronousSocketChannel): F[AsynchronousSocketChannel] =
        to.resolve[F].flatMap { ip =>
          Async[F].async[AsynchronousSocketChannel] { cb =>
            Async[F]
              .delay {
                ch.connect(
                  ip.toInetSocketAddress,
                  null,
                  new CompletionHandler[Void, Void] {
                    def completed(result: Void, attachment: Void): Unit =
                      cb(Right(ch))
                    def failed(rsn: Throwable, attachment: Void): Unit =
                      cb(Left(rsn))
                  }
                )
              }
              .as(Some(Async[F].delay(ch.close())))
          }
        }

      setup.flatMap(ch => Resource.eval(connect(ch))).flatMap(Socket.forAsync(_))
    }

    def serverResource(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption]
    ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] = {

      val setup: Resource[
        F,
        (AsynchronousServerSocketChannel, Channel[F, Either[Throwable, AsynchronousSocketChannel]])
      ] =
        Resource.eval(address.traverse(_.resolve[F])).flatMap { addr =>
          Resource
            .make(
              Async[F].delay(
                AsynchronousServerSocketChannel.open(channelGroup)
              )
            )(sch => Async[F].delay(if (sch.isOpen) sch.close()))
            .evalTap { sch =>
              Async[F].delay(
                sch.bind(
                  new InetSocketAddress(
                    addr.map(_.toInetAddress).orNull,
                    port.map(_.value).getOrElse(0)
                  )
                )
              )
            }
            .mproduct { sch =>
              def acceptChannel: F[AsynchronousSocketChannel] =
                Async[F].async[AsynchronousSocketChannel] { cb =>
                  Async[F]
                    .delay {
                      sch.accept(
                        null,
                        new CompletionHandler[AsynchronousSocketChannel, Void] {
                          def completed(ch: AsynchronousSocketChannel, attachment: Void): Unit =
                            cb(Right(ch))
                          def failed(rsn: Throwable, attachment: Void): Unit =
                            cb(Left(rsn))
                        }
                      )
                    }
                    .as(Some(Async[F].delay(sch.close())))
                }

              Resource
                .make(Channel.synchronous[F, Either[Throwable, AsynchronousSocketChannel]]) {
                  accepted =>
                    accepted.close *>
                      accepted.stream
                        .foreach(_.traverse_(ch => Async[F].delay(ch.close())))
                        .compile
                        .drain
                }
                .flatTap { accepted =>
                  Stream
                    .repeatEval(acceptChannel.attempt)
                    .through(accepted.sendAll)
                    .compile
                    .drain
                    .background
                }
            }

        }

      def acceptIncoming(sch: AsynchronousServerSocketChannel)(
          incoming: Stream[F, Either[Throwable, AsynchronousSocketChannel]]
      ): Stream[F, Socket[F]] = {
        def setOpts(ch: AsynchronousSocketChannel) =
          Async[F].delay {
            options.foreach(o => ch.setOption(o.key, o.value))
          }

        incoming
          .flatMap {
            case Left(_) => Stream.empty[F]
            case Right(accepted) =>
              Stream.resource(Socket.forAsync(accepted).evalTap(_ => setOpts(accepted)))
          }
          .handleErrorWith {
            case err: AsynchronousCloseException =>
              Stream.eval(Async[F].delay(sch.isOpen)).flatMap { isOpen =>
                if (isOpen) Stream.raiseError[F](err)
                else Stream.empty
              }
            case err => Stream.raiseError[F](err)
          }
      }

      setup.map { case (sch, incoming) =>
        val jLocalAddress = sch.getLocalAddress.asInstanceOf[java.net.InetSocketAddress]
        val localAddress = SocketAddress.fromInetSocketAddress(jLocalAddress)
        (localAddress, incoming.stream.through(acceptIncoming(sch)(_)))
      }
    }
  }

}
