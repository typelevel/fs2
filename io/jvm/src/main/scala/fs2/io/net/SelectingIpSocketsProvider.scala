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
package io.net

import cats.effect.LiftIO
import cats.effect.Selector
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s.{Dns, Host, IpAddress, SocketAddress}

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey.OP_ACCEPT
import java.nio.channels.SelectionKey.OP_CONNECT
import java.nio.channels.SocketChannel

private final class SelectingIpSocketsProvider[F[_]](selector: Selector)(implicit
    F: Async[F],
    F2: LiftIO[F],
    F3: Dns[F]
) extends IpSocketsProvider[F] {

  def connect(
      to: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[F, Socket[F]] =
    Resource
      .make(F.delay(selector.provider.openSocketChannel())) { ch =>
        F.delay(ch.close())
      }
      .evalMap { ch =>
        val configure = F.delay {
          ch.configureBlocking(false)
          options.foreach(opt => ch.setOption(opt.key, opt.value))
        }

        val connect = to.resolve.flatMap { ip =>
          F.delay(ch.connect(ip.toInetSocketAddress)).flatMap { connected =>
            selector
              .select(ch, OP_CONNECT)
              .to
              .untilM_(F.delay(ch.finishConnect()))
              .unlessA(connected)
          }
        }

        val make = F
          .delay {
            localAddress(ch) -> remoteAddress(ch)
          }
          .flatMap { case (addr, peerAddr) =>
            SelectingSocket[F](
              selector,
              ch,
              addr,
              peerAddr
            )
          }

        configure *> connect *> make
      }

  def bind(
      address: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[F, ServerSocket[F]] =
    Resource
      .make(F.delay(selector.provider.openServerSocketChannel())) { ch =>
        F.delay(ch.close())
      }
      .evalMap { sch =>
        val configure = address.host.resolve.flatMap { addr =>
          F.delay {
            sch.configureBlocking(false)
            sch.bind(
              new InetSocketAddress(
                if (addr.isWildcard) null else addr.toInetAddress,
                address.port.value
              )
            )
          }
        }

        def acceptLoop: Stream[F, SocketChannel] = Stream
          .bracketFull[F, SocketChannel] { poll =>
            def go: F[SocketChannel] =
              F.delay(sch.accept()).flatMap {
                case null => poll(selector.select(sch, OP_ACCEPT).to) *> go
                case ch   => F.pure(ch)
              }
            go
          }((ch, _) => F.delay(ch.close()))
          .repeat
          .handleErrorWith {
            case _: AsynchronousCloseException | _: ClosedChannelException => acceptLoop
            case ex                                                        => Stream.raiseError(ex)
          }

        val accept = acceptLoop.evalMap { ch =>
          F.delay {
            ch.configureBlocking(false)
            options.foreach(opt => ch.setOption(opt.key, opt.value))
          } *> SelectingSocket[F](
            selector,
            ch,
            localAddress(ch),
            remoteAddress(ch)
          )
        }

        configure *> F.delay(ServerSocket(SocketInfo.forAsync(sch), accept))
      }

  private def localAddress(ch: SocketChannel): SocketAddress[IpAddress] =
    SocketAddress.fromInetSocketAddress(
      ch.getLocalAddress.asInstanceOf[InetSocketAddress]
    )

  private def remoteAddress(ch: SocketChannel): SocketAddress[IpAddress] =
    SocketAddress.fromInetSocketAddress(
      ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
    )

}
