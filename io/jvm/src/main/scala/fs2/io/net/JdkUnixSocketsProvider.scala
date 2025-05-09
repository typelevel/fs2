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

import cats.syntax.all._
import cats.effect.{Async, Resource}
import cats.effect.syntax.all._

import com.comcast.ip4s.UnixSocketAddress

import fs2.io.file.Files

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{ServerSocketChannel, SocketChannel}

private[net] object JdkUnixSocketsProvider {

  def supported: Boolean = StandardProtocolFamily.values.size > 2

  def forAsyncAndFiles[F[_]: Async: Files]: UnixSocketsProvider[F] =
    new JdkUnixSocketsProvider[F]

  def forAsync[F[_]](implicit F: Async[F]): UnixSocketsProvider[F] =
    forAsyncAndFiles(F, Files.forAsync[F])
}

private[net] class JdkUnixSocketsProvider[F[_]: Files](implicit F: Async[F])
    extends UnixSocketsProvider.AsyncUnixSocketsProvider[F] {
  protected def openChannel(address: UnixSocketAddress, options: List[SocketOption]) =
    evalOnVirtualThreadIfAvailable(
      Resource
        .make(
          F.blocking(SocketChannel.open(StandardProtocolFamily.UNIX))
        )(ch => F.blocking(ch.close()))
        .evalTap { ch =>
          F.blocking(ch.connect(UnixDomainSocketAddress.of(address.path)))
            .cancelable(F.blocking(ch.close())) *>
            F.delay(options.foreach(o => ch.setOption(o.key, o.value)))
        }
    )

  protected def openServerChannel(address: UnixSocketAddress, options: List[SocketOption]) =
    evalOnVirtualThreadIfAvailable(
      Resource
        .make(
          F.blocking(ServerSocketChannel.open(StandardProtocolFamily.UNIX))
        )(ch => F.blocking(ch.close()))
        .evalTap { sch =>
          F.blocking(sch.bind(UnixDomainSocketAddress.of(address.path)))
            .cancelable(F.blocking(sch.close()))
        }
        .map { sch =>
          SocketInfo.forAsync(sch) ->
            Resource
              .makeFull[F, SocketChannel] { poll =>
                poll(F.blocking(sch.accept).cancelable(F.blocking(sch.close())))
              }(ch => F.blocking(ch.close()))
              .evalTap(ch => F.delay(options.foreach(o => ch.setOption(o.key, o.value))))
        }
    )
}
