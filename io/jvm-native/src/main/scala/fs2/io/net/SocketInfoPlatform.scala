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

import com.comcast.ip4s.{GenSocketAddress, IpAddress, SocketAddress}
import cats.effect.Async

import java.net.InetSocketAddress
import java.nio.channels.NetworkChannel

import scala.jdk.CollectionConverters.*

private[net] trait SocketInfoCompanionPlatform {
  private[net] def forAsync[F[_]](ch: NetworkChannel)(implicit F: Async[F]): SocketInfo[F] =
    new AsyncSocketInfo[F] {
      def asyncInstance = F
      def channel = ch
    }

  private[net] trait AsyncSocketInfo[F[_]] extends SocketInfo[F] {

    implicit protected def asyncInstance: Async[F]
    protected def channel: NetworkChannel

    override def localAddressGen: F[GenSocketAddress] =
      asyncInstance.delay(
        channel.getLocalAddress match {
          case addr: InetSocketAddress => SocketAddress.fromInetSocketAddress(addr)
          // TODO handle unix sockets
        }
      )

    override def supportedOptions: F[Set[SocketOption.Key[_]]] =
      asyncInstance.delay {
        channel.supportedOptions.asScala.toSet
      }

    override def getOption[A](key: SocketOption.Key[A]): F[Option[A]] =
      asyncInstance.delay {
        try {
          Some(channel.getOption(key))
        } catch {
          case _: UnsupportedOperationException => None
        }
      }

    override def setOption[A](key: SocketOption.Key[A], value: A): F[Unit] =
      asyncInstance.delay {
        channel.setOption(key, value)
        ()
      }
  }

}

