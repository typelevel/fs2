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

import cats.effect.{Async, LiftIO}

import com.comcast.ip4s.Dns

private[net] trait NetworkPlatform[F[_]]

private[net] trait NetworkCompanionPlatform extends NetworkLowPriority { self: Network.type =>

  implicit def forLiftIO[F[_]: Async: LiftIO]: Network[F] =
    new AsyncProviderBasedNetwork[F] {
      protected def mkIpSocketsProvider =
        new FdPollingIpSocketsProvider[F]()(Dns.forAsync, implicitly, implicitly)
      protected def mkUnixSocketsProvider = new FdPollingUnixSocketsProvider[F]
      protected def mkIpDatagramSocketsProvider =
        throw new UnsupportedOperationException(
          "Datagram sockets not currently supported on Native"
        )
      protected def mkUnixDatagramSocketsProvider =
        throw new UnsupportedOperationException(
          "Unix datagram sockets not currently supported on Native"
        )
    }

  def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    forAsyncAndDns(F, Dns.forAsync(F))

  def forAsyncAndDns[F[_]](implicit F: Async[F], dns: Dns[F]): Network[F] = {
    val _ = (F, dns)
    throw new UnsupportedOperationException("must use forLiftIO instead of forAsync/forAsyncAndDns")
  }
}
