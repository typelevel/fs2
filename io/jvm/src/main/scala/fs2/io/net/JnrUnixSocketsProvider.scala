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

import java.nio.channels.SocketChannel
import jnr.unixsocket.{
  UnixServerSocketChannel,
  UnixSocketAddress => JnrUnixSocketAddress,
  UnixSocketChannel
}

private[net] object JnrUnixSocketsProvider {

  lazy val supported: Boolean =
    try {
      Class.forName("jnr.unixsocket.UnixSocketChannel")
      true
    } catch {
      case _: ClassNotFoundException => false
    }

  def forAsyncAndFiles[F[_]: Async: Files]: UnixSocketsProvider[F] =
    new JnrUnixSocketsProvider[F]

  def forAsync[F[_]](implicit F: Async[F]): UnixSocketsProvider[F] =
    forAsyncAndFiles(F, Files.forAsync[F])
}

private[net] class JnrUnixSocketsProvider[F[_]](implicit F: Async[F], F2: Files[F])
    extends UnixSocketsProvider.AsyncUnixSocketsProvider[F] {

  protected def openChannel(address: UnixSocketAddress) =
    Resource.make(F.blocking(UnixSocketChannel.open(new JnrUnixSocketAddress(address.path))))(ch =>
      F.blocking(ch.close())
    )

  protected def openServerChannel(address: UnixSocketAddress, options: List[SocketOption]) =
    Resource
      .make(F.blocking(UnixServerSocketChannel.open()))(ch => F.blocking(ch.close()))
      .evalTap { sch =>
        F.blocking(sch.socket().bind(new JnrUnixSocketAddress(address.path)))
          .cancelable(F.blocking(sch.close()))
      }
      .map { sch =>
        def raiseOptionError[A]: F[A] =
          F.raiseError(
            new UnsupportedOperationException(
              "JNR unix server sockets do not support socket options"
            )
          )
        val info: SocketInfo[F] = new SocketInfo[F] {
          override def supportedOptions = F.pure(Set.empty)
          override def getOption[A](key: SocketOption.Key[A]) = raiseOptionError
          override def setOption[A](key: SocketOption.Key[A], value: A) = raiseOptionError
          override def localAddressGen = F.pure(address)
          override def localAddress = localAddressGen.map(_.asIpUnsafe)
        }
        info ->
          Resource
            .makeFull[F, SocketChannel] { poll =>
              F.widen(poll(F.blocking(sch.accept).cancelable(F.blocking(sch.close()))))
            }(ch => F.blocking(ch.close()))
            .evalTap(ch => F.delay(options.foreach(o => ch.setOption(o.key, o.value))))
      }
}
