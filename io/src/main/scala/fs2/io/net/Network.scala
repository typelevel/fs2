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

import cats.effect.kernel.{Async, Resource}

import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}

import fs2.internal.ThreadFactories
import fs2.io.net.tls.TLSContext

import java.net.ProtocolFamily
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.ThreadFactory

/** Provides the ability to work with TCP, UDP, and TLS.
  *
  * @example {{{
  * import fs2.Stream
  * import fs2.io.net.{Datagram, Network}
  *
  * def send[F[_]: Network](datagram: Datagram): F[Unit] =
  *   Network[F].openDatagramSocket().use { socket =>
  *     socket.write(packet)
  *   }
  * }}}
  *
  * In this example, the `F[_]` parameter to `send` requires the `Network` constraint instead
  * of requiring the much more powerful `Async` constraint.
  *
  * The `Network` instance has a set of global resources used for managing sockets. Alternatively,
  * use the `socketGroup` and `datagramSocketGroup` operations to manage the lifecycle of underlying
  * resources.
  *
  * An instance of `Network` is available for any effect `F` which has an `Async[F]` instance.
  */
sealed trait Network[F[_]] extends SocketGroup[F] with DatagramSocketGroup[F] {

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
  def datagramSocketGroup(
      threadFactory: ThreadFactory = ThreadFactories.named("fs2-udp", true)
  ): Resource[F, DatagramSocketGroup[F]]

  /** Returns a builder for `TLSContext[F]` values.
    *
    * For example, `Network[IO].tlsContext.system` returns a `F[TLSContext[F]]`.
    */
  def tlsContext: TLSContext.Builder[F]
}

object Network {
  private lazy val globalAcg = AsynchronousChannelGroup.withFixedThreadPool(
    1,
    ThreadFactories.named("fs2-global-tcp", true)
  )
  private lazy val globalAdsg =
    AsynchronousDatagramSocketGroup.unsafe(ThreadFactories.named("fs2-global-udp", true))

  def apply[F[_]](implicit F: Network[F]): F.type = F

  implicit def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    new Network[F] {
      private lazy val globalSocketGroup = SocketGroup.unsafe[F](globalAcg)
      private lazy val globalDatagramSocketGroup = DatagramSocketGroup.unsafe[F](globalAdsg)

      def socketGroup(threadCount: Int, threadFactory: ThreadFactory): Resource[F, SocketGroup[F]] =
        Resource
          .make(
            F.delay(
              AsynchronousChannelGroup.withFixedThreadPool(threadCount, threadFactory)
            )
          )(acg => F.delay(acg.shutdown()))
          .map(SocketGroup.unsafe[F](_))

      def datagramSocketGroup(threadFactory: ThreadFactory): Resource[F, DatagramSocketGroup[F]] =
        Resource
          .make(F.delay(AsynchronousDatagramSocketGroup.unsafe(threadFactory)))(adsg =>
            F.delay(adsg.close())
          )
          .map(DatagramSocketGroup.unsafe[F](_))

      def client(
          to: SocketAddress[Host],
          options: List[SocketOption]
      ): Resource[F, Socket[F]] = globalSocketGroup.client(to, options)

      def server(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Stream[F, Socket[F]] = globalSocketGroup.server(address, port, options)

      def serverResource(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
        globalSocketGroup.serverResource(address, port, options)

      def openDatagramSocket(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption],
          protocolFamily: Option[ProtocolFamily]
      ): Resource[F, DatagramSocket[F]] =
        globalDatagramSocketGroup.openDatagramSocket(address, port, options, protocolFamily)

      def tlsContext: TLSContext.Builder[F] = TLSContext.Builder.forAsync[F]
    }
}
