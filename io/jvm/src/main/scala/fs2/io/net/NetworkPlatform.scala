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

import com.comcast.ip4s.{Dns, GenSocketAddress, Host, IpAddress, Port, SocketAddress, UnixSocketAddress}

import fs2.internal.ThreadFactories
import fs2.io.net.tls.TLSContext

import java.net.ProtocolFamily
import java.nio.channels.AsynchronousChannelGroup
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

}

private[net] trait NetworkCompanionPlatform extends NetworkLowPriority { self: Network.type =>
  private lazy val globalAcg = AsynchronousChannelGroup.withFixedThreadPool(
    1,
    ThreadFactories.named("fs2-global-tcp", true)
  )
  private lazy val globalAdsg =
    AsynchronousDatagramSocketGroup.unsafe(ThreadFactories.named("fs2-global-udp", true))

  def forIO: Network[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Async: LiftIO]: Network[F] =
    new AsyncNetwork[F] {
      private lazy val fallback = forAsync[F]

      private def tryGetSelector =
        IO.pollers.map(_.collectFirst { case selector: Selector => selector }).to[F]

      private implicit def dns: Dns[F] = Dns.forAsync[F]

      def socketGroup(threadCount: Int, threadFactory: ThreadFactory): Resource[F, SocketGroup[F]] =
        Resource.eval(tryGetSelector).flatMap {
          case Some(selector) => Resource.pure(new SelectingSocketGroup[F](selector))
          case None           => fallback.socketGroup(threadCount, threadFactory)
        }

      def datagramSocketGroup(threadFactory: ThreadFactory): Resource[F, DatagramSocketGroup[F]] =
        fallback.datagramSocketGroup(threadFactory)

      def client(
          to: SocketAddress[Host],
          options: List[SocketOption]
      ): Resource[F, Socket[F]] = Resource.eval(tryGetSelector).flatMap {
        case Some(selector) => new SelectingSocketGroup(selector).client(to, options)
        case None           => fallback.client(to, options)
      }

      def server(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Stream[F, Socket[F]] = Stream.eval(tryGetSelector).flatMap {
        case Some(selector) => new SelectingSocketGroup(selector).server(address, port, options)
        case None           => fallback.server(address, port, options)
      }

      def serverResource(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption]
      ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
        Resource.eval(tryGetSelector).flatMap {
          case Some(selector) =>
            new SelectingSocketGroup(selector).serverResource(address, port, options)
          case None => fallback.serverResource(address, port, options)
        }

      def openDatagramSocket(
          address: Option[Host],
          port: Option[Port],
          options: List[SocketOption],
          protocolFamily: Option[ProtocolFamily]
      ): Resource[F, DatagramSocket[F]] =
        fallback.openDatagramSocket(address, port, options, protocolFamily)
    }

  def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    forAsyncAndDns(F, Dns.forAsync(F))

  def forAsyncAndDns[F[_]](implicit F: Async[F], dns: Dns[F]): Network[F] =
    new AsyncNetwork[F] {
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
    }


  private abstract class AsyncNetwork[F[_]](implicit F: Async[F]) extends UnsealedNetwork[F] {

    def tlsContext: TLSContext.Builder[F] = TLSContext.Builder.forAsync[F]

    private def unixSockets = fs2.io.net.unixsocket.UnixSockets.forAsync[F]

    def tcp: NeedAddress[F, TcpBuilder[F]] = new UnsealedNeedAddress[F, TcpBuilder[F]] {
      def address(address: GenSocketAddress): TcpBuilder[F] = {
        def mkClient(address: GenSocketAddress, options: List[SocketOption]) =
          address match {
            case sa: SocketAddress[_] => client(sa, options)
            case ua: UnixSocketAddress => unixSockets.client(fs2.io.net.unixsocket.UnixSocketAddress(ua.path))
            case _ => ???
          }
        TcpBuilder[F](mkClient, address, Nil)
      }
   }
  }

}
