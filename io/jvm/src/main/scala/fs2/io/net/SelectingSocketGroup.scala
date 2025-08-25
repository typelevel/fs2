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

import scala.concurrent.duration._

import cats.effect.LiftIO
import cats.effect.Selector
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s.Dns
import com.comcast.ip4s.Host
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress

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
          localAddress(ch),
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
    Resource
      .make(F.delay(selector.provider.openServerSocketChannel())) { ch =>
        def waitForDeregistration: F[Unit] =
          // sleep time set to be short enough to not noticeably delay shutdown but long enough to
          // give the runtime/cpu time to do something else; some guesswork involved here
          F.delay(ch.isRegistered()).ifM(F.sleep(2.millis) >> waitForDeregistration, F.unit)
        F.delay(ch.close()) >> waitForDeregistration
      }
      .evalMap { serverCh =>
        val configure = address.traverse(_.resolve).flatMap { ip =>
          F.delay {
            serverCh.configureBlocking(false)
            serverCh.bind(
              new InetSocketAddress(
                ip.map(_.toInetAddress).orNull,
                port.map(_.value).getOrElse(0)
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

        val clients = acceptLoop.evalMap { ch =>
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

        val socketAddress = F.delay {
          SocketAddress.fromInetSocketAddress(
            serverCh.getLocalAddress.asInstanceOf[InetSocketAddress]
          )
        }

        configure *> socketAddress.tupleRight(clients)
      }

  private def localAddress(ch: SocketChannel) =
    F.delay {
      SocketAddress.fromInetSocketAddress(
        ch.getLocalAddress.asInstanceOf[InetSocketAddress]
      )
    }

  private def remoteAddress(ch: SocketChannel) =
    F.delay {
      SocketAddress.fromInetSocketAddress(
        ch.getRemoteAddress.asInstanceOf[InetSocketAddress]
      )
    }

}
