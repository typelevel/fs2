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

import cats.effect.{Async, LiftIO, Resource, Selector}
import cats.syntax.all._
import com.comcast.ip4s.{Dns, Host, SocketAddress}

import java.net.InetSocketAddress

private final class SelectingIpDatagramSocketsProvider[F[_]](selector: Selector)(implicit
    F: Async[F],
    F2: LiftIO[F],
    F3: Dns[F]
) extends IpDatagramSocketsProvider[F] {

  override def bindDatagramSocket(
      address: SocketAddress[Host],
      options: List[SocketOption]
  ): Resource[F, DatagramSocket[F]] =
    Resource
      .make(F.delay(selector.provider.openDatagramChannel()))(ch => F.delay(ch.close()))
      .evalMap { ch =>
        address.host.resolve[F].flatMap { addr =>
          val jAddr = new InetSocketAddress(addr.toInetAddress, address.port.value)
          F.delay {
            ch.configureBlocking(false)
            ch.bind(jAddr)
            options.foreach(opt => ch.setOption(opt.key, opt.value))
          } *> F
            .delay {
              val isa = ch.getLocalAddress.asInstanceOf[InetSocketAddress]
              val inet = isa.getAddress
              new InetSocketAddress(inet, isa.getPort)
            }
            .flatMap(local =>
              SelectingDatagramSocket(selector, ch, SocketAddress.fromInetSocketAddress(local))
            )
        }
      }
}
