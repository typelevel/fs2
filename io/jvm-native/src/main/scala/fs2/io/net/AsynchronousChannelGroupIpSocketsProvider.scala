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
import cats.effect.{Async, Resource}
import com.comcast.ip4s.{Dns, Host, SocketAddress}

private[net] class AsynchronousChannelGroupIpSocketsProvider[F[_]] private (
  channelGroup: AsynchronousChannelGroup
)(implicit F: Async[F], F2: Dns[F]) extends IpSocketsProvider[F] {

  override def connect(
    address: SocketAddress[Host],
    options: List[SocketOption]
  ): Resource[F, Socket[F]] = {
    
    def setup: Resource[F, AsynchronousSocketChannel] =
      Resource
        .make(
          F.delay(
            AsynchronousSocketChannel.open(channelGroup)
          )
        )(ch => F.delay(if (ch.isOpen) ch.close else ()))
        .evalTap(ch => F.delay(options.foreach(opt => ch.setOption(opt.key, opt.value))))

    def connect(ch: AsynchronousSocketChannel): F[AsynchronousSocketChannel] =
      address.resolve[F].flatMap { ip =>
        F.async[AsynchronousSocketChannel] { cb =>
          F.delay {
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
            .as(Some(F.delay(ch.close())))
        }
      }

    setup.evalMap(ch => connect(ch) *> Socket.forAsync(ch))
 }

  override def bind(
    address: SocketAddress[Host],
    options: List[SocketOption]
  ): Resource[F, ServerSocket[F]] = {
    

    val setup: Resource[F, AsynchronousServerSocketChannel] =
      Resource.eval(address.host.resolve[F]).flatMap { addr =>
        Resource
          .make(
            F.delay(
              AsynchronousServerSocketChannel.open(channelGroup)
            )
          )(sch => F.delay(if (sch.isOpen) sch.close()))
          .evalTap(ch =>
            F.delay(
              ch.bind(
                new InetSocketAddress(
                  if (addr.isWildcard) null else addr.toInetAddress,
                  address.port.value
                )
              )
            )
          )
      }

    def acceptIncoming(
        sch: AsynchronousServerSocketChannel
    ): Stream[F, Socket[F]] = {
      def go: Stream[F, Socket[F]] = {
        def acceptChannel = Resource.makeFull[F, AsynchronousSocketChannel] { poll =>
          poll {
            F.async[AsynchronousSocketChannel] { cb =>
              F.delay {
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
                .as(Some(F.delay(sch.close())))
            }
          }
        }(ch => F.delay(if (ch.isOpen) ch.close else ()))

        def setOpts(ch: AsynchronousSocketChannel) =
          F.delay {
            options.foreach(o => ch.setOption(o.key, o.value))
          }

        Stream.resource(acceptChannel.attempt).flatMap {
          case Left(_)         => Stream.empty[F]
          case Right(accepted) => Stream.eval(setOpts(accepted) *> Socket.forAsync(accepted))
        } ++ go
      }

      go.handleErrorWith {
        case err: AsynchronousCloseException =>
          Stream.eval(F.delay(sch.isOpen)).flatMap { isOpen =>
            if (isOpen) Stream.raiseError[F](err)
            else Stream.empty
          }
        case err => Stream.raiseError[F](err)
      }
    }

    setup.map(sch => ServerSocket(SocketInfo.forAsync(sch), acceptIncoming(sch)))
  }
}

private[net] object AsynchronousChannelGroupIpSocketsProvider {

  def forAsyncAndDns[F[_]: Async: Dns]: AsynchronousChannelGroupIpSocketsProvider[F] =
    new AsynchronousChannelGroupIpSocketsProvider[F](null)

  def forAsync[F[_]: Async]: AsynchronousChannelGroupIpSocketsProvider[F] =
    forAsyncAndDns(Async[F], Dns.forAsync[F])
}
