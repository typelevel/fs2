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
import com.comcast.ip4s.{Dns, Host, IpAddress, Ipv4Address, Port, SocketAddress}

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey.OP_ACCEPT
import java.nio.channels.SelectionKey.OP_CONNECT
import java.nio.channels.SocketChannel

private final class SelectingSocketGroup[F[_]: LiftIO: Dns](selector: Selector)(implicit
    F: Async[F]
) extends SocketGroup[F] {

  def client(
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

        val make = SelectingSocket[F](
          selector,
          ch,
          remoteAddress(ch)
        )

        configure *> connect *> make
      }

  def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Stream[F, Socket[F]] =
    Stream
      .resource(
        serverResource(
          address,
          port,
          options
        )
      )
      .flatMap { case (_, clients) => clients }

  def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
    serverBound(SocketAddress(address.getOrElse(Ipv4Address.Wildcard), port.getOrElse(Port.Wildcard)), options).evalMap { bound =>
      bound.serverSocketInfo.localAddress.map { case addr: SocketAddress[IpAddress] => (addr, bound.clients) }
    }


  def serverBound(
      address: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[F, BoundServer[F]] =
    Resource
      .make(F.delay(selector.provider.openServerSocketChannel())) { ch =>
        F.delay(ch.close())
      }
      .evalMap { serverCh =>
        val configure = address.host.resolve.flatMap { addr =>
          F.delay {
            serverCh.configureBlocking(false)
            serverCh.bind(
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
              F.delay(serverCh.accept()).flatMap {
                case null => poll(selector.select(serverCh, OP_ACCEPT).to) *> go
                case ch   => F.pure(ch)
              }
            go
          }((ch, _) => F.delay(ch.close()))
          .repeat
          .handleErrorWith {
            case _: AsynchronousCloseException | _: ClosedChannelException => acceptLoop
            case ex                                                        => Stream.raiseError(ex)
          }

        val clients0 = acceptLoop.evalMap { ch =>
          F.delay {
            ch.configureBlocking(false)
            options.foreach(opt => ch.setOption(opt.key, opt.value))
          } *> SelectingSocket[F](
            selector,
            ch,
            remoteAddress(ch)
          )
        }

        configure.as(new UnsealedBoundServer[F] {
          def serverSocketInfo = SocketInfo.forAsync(serverCh)
          def clients = clients0
        })
      }

  private def remoteAddress(ch: SocketChannel) =
    F.delay {
      SocketAddress.fromInetSocketAddress(
        ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
      )
    }

}
