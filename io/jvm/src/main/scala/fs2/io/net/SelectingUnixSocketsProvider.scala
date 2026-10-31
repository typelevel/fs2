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

import cats.effect.{Async, LiftIO, Resource, Selector}
import cats.syntax.all._
import com.comcast.ip4s.UnixSocketAddress

import fs2.io.file.Files

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.SelectionKey

/** Unix domain sockets backed by non-blocking JDK 16+ channels and the Cats Effect `Selector`.
  *
  * Only use this when `JdkUnixSocketsProvider.supported` is true.
  */
private final class SelectingUnixSocketsProvider[F[_]: Files](selector: Selector)(implicit
    F: Async[F],
    F2: LiftIO[F]
) extends UnixSocketsProvider[F] {

  override def connectUnix(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, Socket[F]] =
    Resource
      .make(F.delay(selector.provider.openSocketChannel(StandardProtocolFamily.UNIX))) { ch =>
        F.delay(ch.close())
      }
      .evalMap { ch =>
        val configure = F.delay {
          ch.configureBlocking(false)
          options.foreach(opt => ch.setOption(opt.key, opt.value))
        }

        val connect =
          F.delay(ch.connect(UnixDomainSocketAddress.of(address.path))).flatMap { connected =>
            selector
              .select(ch, SelectionKey.OP_CONNECT)
              .to
              .untilM_(F.delay(ch.finishConnect()))
              .unlessA(connected)
          }

        configure *> connect *> SelectingSocket[F](selector, ch, UnixSocketAddress(""), address)
      }

  override def bindUnix(
      address: UnixSocketAddress,
      options: List[SocketOption]
  ): Resource[F, ServerSocket[F]] = {
    val (filteredOptions, delete) = SocketOption.extractUnixSocketDeletes(options, address)

    delete *> Resource
      .make(
        F.delay(selector.provider.openServerSocketChannel(StandardProtocolFamily.UNIX))
      )(SelectingSocket.closeServerChannel[F])
      .evalMap { sch =>
        val configure = F.delay {
          sch.configureBlocking(false)
          sch.bind(UnixDomainSocketAddress.of(address.path))
        }

        val accept = SelectingSocket.acceptLoop[F](selector, sch).evalMap { ch =>
          F.delay {
            ch.configureBlocking(false)
            filteredOptions.foreach(opt => ch.setOption(opt.key, opt.value))
          } *> SelectingSocket[F](selector, ch, address, UnixSocketAddress(""))
        }

        configure *> F.delay(ServerSocket(SocketInfo.forAsync(sch), accept))
      }
  }
}
