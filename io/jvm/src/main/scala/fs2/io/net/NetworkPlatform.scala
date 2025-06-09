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

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.Selector
import cats.effect.kernel.{Async, Resource}

import com.comcast.ip4s.{Dns, GenSocketAddress}

import fs2.internal.ThreadFactories

import java.util.concurrent.ThreadFactory

private[net] trait NetworkPlatform[F[_]] {

  /** Provides an isolated `SocketGroup[F]` with the specified thread pool configuration.
    * The resulting socket group is shutdown during resource finalization, resulting in
    * closure of any sockets that were created.
    *
    * Note: `Network` is a `SocketGroup` so only use this operation if you need explicit
    * control over the lifecycle of the socket group.
    *
    * @param threadCount number of threads to allocate in the fixed thread pool backing the NIO channel group
    * @param threadFactory factory used to create fixed threads
    */
  @deprecated(
    "3.13.0",
    "Explicitly managed socket groups are no longer supported; use connect and bind operations on Network instead"
  )
  def socketGroup(
      threadCount: Int = 1,
      threadFactory: ThreadFactory = ThreadFactories.named("fs2-tcp", true)
  ): Resource[F, SocketGroup[F]]

  /** Provides an isolated `DatagramSocketGroup[F]` with the specified thread configuration.
    * The resulting socket group is shutdown during resource finalization, resulting in
    * closure of any sockets that were created.
    *
    * Note: `Network` is a `DatagramSocketGroup` so only use this operation if you need explicit
    * control over the lifecycle of the socket group.
    *
    * @param threadFactory factory used to create selector thread
    */
  @deprecated(
    "3.13.0",
    "Explicitly managed socket groups are no longer supported; use bindDatagramSocket operation on Network instead"
  )
  def datagramSocketGroup(
      threadFactory: ThreadFactory = ThreadFactories.named("fs2-udp", true)
  ): Resource[F, DatagramSocketGroup[F]]

}

private[net] trait NetworkCompanionPlatform extends NetworkLowPriority { self: Network.type =>

  implicit def forLiftIO[F[_]: Async: LiftIO]: Network[F] =
    new AsyncNetwork[F] {
      private lazy val fallback = forAsync[F]

      private def tryGetSelector =
        IO.pollers.map(_.collectFirst { case selector: Selector => selector }).to[F]

      private implicit def dns: Dns[F] = Dns.forAsync[F]

      private def selecting[A](
          ifSelecting: SelectingIpSocketsProvider[F] => Resource[F, A],
          orElse: => Resource[F, A]
      ): Resource[F, A] =
        Resource.eval(tryGetSelector).flatMap {
          case Some(selector) => ifSelecting(new SelectingIpSocketsProvider(selector))
          case None           => orElse
        }

      override def connect(
          address: GenSocketAddress,
          options: List[SocketOption]
      ): Resource[F, Socket[F]] =
        matchAddress(
          address,
          sa => selecting(_.connectIp(sa, options), fallback.connect(sa, options)),
          ua => fallback.connect(ua, options)
        )

      override def bind(
          address: GenSocketAddress,
          options: List[SocketOption]
      ): Resource[F, ServerSocket[F]] =
        matchAddress(
          address,
          sa => selecting(_.bindIp(sa, options), fallback.bind(sa, options)),
          ua => fallback.bind(ua, options)
        )

      override def bindDatagramSocket(
          address: GenSocketAddress,
          options: List[SocketOption]
      ): Resource[F, DatagramSocket[F]] =
        fallback.bindDatagramSocket(address, options)

      // Implementations of deprecated operations

      override def datagramSocketGroup(
          threadFactory: ThreadFactory
      ): Resource[F, DatagramSocketGroup[F]] =
        Resource.pure(this)

      override def socketGroup(
          threadCount: Int,
          threadFactory: ThreadFactory
      ): Resource[F, SocketGroup[F]] =
        Resource.pure(this)
    }

  def forAsyncAndDns[F[_]](implicit F: Async[F], dns: Dns[F]): Network[F] = {
    val _ = dns
    forAsync
  }

  def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    new AsyncProviderBasedNetwork[F] {
      protected def mkIpSocketsProvider = AsynchronousChannelGroupIpSocketsProvider.forAsync[F]
      protected def mkUnixSocketsProvider = AutoDetectingUnixSocketsProvider.forAsync[F]

      protected def mkIpDatagramSocketsProvider = AsyncIpDatagramSocketsProvider.forAsync[F]
      protected def mkUnixDatagramSocketsProvider =
        AutoDetectingUnixDatagramSocketsProvider.forAsync[F]

      // Implementations of deprecated operations

      def datagramSocketGroup(threadFactory: ThreadFactory): Resource[F, DatagramSocketGroup[F]] =
        Resource.pure(this)

      def socketGroup(threadCount: Int, threadFactory: ThreadFactory): Resource[F, SocketGroup[F]] =
        Resource.pure(this)
    }
}
