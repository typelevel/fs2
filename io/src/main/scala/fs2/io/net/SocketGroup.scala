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
import java.nio.channels.spi.AsynchronousChannelProvider

import cats.syntax.all._
import cats.effect.kernel.{Async, Resource}

import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}

/** Supports creation of client and server TCP sockets that all share
  * an underlying non-blocking channel group.
  */
trait SocketGroup[F[_]] {

  /** Opens a TCP connection to the specified server.
    *
    * The connection is closed when the resource is released.
    *
    * @param to      address of remote server
    * @param options socket options to apply to the underlying socket
    */
  def client(
      to: SocketAddress[Host],
      options: List[SocketOption] = List.empty
  ): Resource[F, Socket[F]]

  /** Creates a TCP server bound to specified address/port and returns a stream of
    * client sockets -- one per client that connects to the bound address/port.
    *
    * When the stream terminates, all open connections will terminate as well.
    *
    * @param address            address to accept connections from; none for all interfaces
    * @param port               port to bind
    * @param options socket options to apply to the underlying socket
    */
  def server(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = List.empty
  ): Stream[F, Socket[F]]

  /** Like [[server]] but provides the `SocketAddress` of the bound server socket before providing accepted sockets.
    */
  def serverResource(
      address: Option[Host] = None,
      port: Option[Port] = None,
      options: List[SocketOption] = List.empty
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])]
}

private[net] object SocketGroup {
  private[fs2] def unsafe[F[_]: Async](channelGroup: AsynchronousChannelGroup): SocketGroup[F] =
    new AsyncSocketGroup[F](channelGroup)

  private final class AsyncSocketGroup[F[_]: Async](channelGroup: AsynchronousChannelGroup)
      extends SocketGroup[F] {

    def client(
        to: SocketAddress[Host],
        options: List[SocketOption]
    ): Resource[F, Socket[F]] = {
      def setup: F[AsynchronousSocketChannel] =
        Async[F].delay {
          val ch =
            AsynchronousChannelProvider.provider.openAsynchronousSocketChannel(channelGroup)
          options.foreach(opt => ch.setOption(opt.key, opt.value))
          ch
        }

      def connect(ch: AsynchronousSocketChannel): F[AsynchronousSocketChannel] =
        to.resolve[F].flatMap { ip =>
          Async[F].async_[AsynchronousSocketChannel] { cb =>
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
        }

      Resource
        .make(setup.flatMap(connect))(ch => Async[F].delay(if (ch.isOpen()) ch.close() else ()))
        .evalMap(Socket.forAsync(_))
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
      serverResourceShared(address, port, options).map { case (addr, clients) =>
        (addr, clients.flatMap(shared => Stream.resource(shared.resource)))
      }

    def serverResourceShared(
        address: Option[Host],
        port: Option[Port],
        options: List[SocketOption]
    ): Resource[F, (SocketAddress[IpAddress], Stream[F, Shared[F, Socket[F]]])] = {
      val setup: F[AsynchronousServerSocketChannel] =
        address.traverse(_.resolve[F]).flatMap { addr =>
          Async[F].delay {
            val ch = AsynchronousChannelProvider.provider
              .openAsynchronousServerSocketChannel(channelGroup)
            options.foreach { opt =>
              ch.setOption[opt.Value](opt.key, opt.value)
            }
            ch.bind(
              new InetSocketAddress(
                addr.map(_.toInetAddress).orNull,
                port.map(_.value).getOrElse(0)
              )
            )
            ch
          }
        }

      def cleanup(sch: AsynchronousServerSocketChannel): F[Unit] =
        Async[F].delay(if (sch.isOpen) sch.close())

      def acceptIncoming(
          sch: AsynchronousServerSocketChannel
      ): Stream[F, Shared[F, Socket[F]]] = {
        def go: Stream[F, Shared[F, Socket[F]]] = {
          def acceptChannel: Resource[F, AsynchronousSocketChannel] =
            Resource.makeFull[F, AsynchronousSocketChannel] { poll =>
              poll {
                Async[F].async_[AsynchronousSocketChannel] { cb =>
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
              }
            }(ch => Async[F].delay(if (ch.isOpen()) ch.close() else ()))

          val sharedSocket = Shared.allocate(acceptChannel.evalMap(Socket.forAsync(_)))
          Stream.resource(sharedSocket.attempt).flatMap {
            case Left(_)            => Stream.empty[F]
            case Right((shared, _)) => Stream(shared)
          } ++ go
        }

        go.handleErrorWith {
          case err: AsynchronousCloseException =>
            Stream.eval(Async[F].delay(sch.isOpen)).flatMap { isOpen =>
              if (isOpen) Stream.raiseError[F](err)
              else Stream.empty
            }
          case err => Stream.raiseError[F](err)
        }
      }

      Resource.make(setup)(cleanup).map { sch =>
        val jLocalAddress = sch.getLocalAddress.asInstanceOf[java.net.InetSocketAddress]
        val localAddress = SocketAddress.fromInetSocketAddress(jLocalAddress)
        (localAddress, acceptIncoming(sch))
      }
    }
  }
}
