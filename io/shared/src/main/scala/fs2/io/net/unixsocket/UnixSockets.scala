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

package fs2.io.net.unixsocket

import cats.effect.{Async, IO, LiftIO, Resource}

import com.comcast.ip4s.{UnixSocketAddress => Ip4sUnixSocketAddress}

import fs2.Stream
import fs2.io.net.{Socket, SocketOption, UnixSocketsProvider}

/** Capability of working with AF_UNIX sockets. */
trait UnixSockets[F[_]] {

  /** Returns a resource which opens a unix socket to the specified path.
    */
  def client(address: UnixSocketAddress): Resource[F, Socket[F]]

  /** Listens to the specified path for connections and emits a `Socket` for each connection.
    *
    * Note: the path referred to by `address` must not exist or otherwise binding will fail
    * indicating the address is already in use. To force binding in such a case, pass `deleteIfExists = true`,
    * which will first delete the path.
    *
    * By default, the path is deleted when the server closes. To override this, pass `deleteOnClose = false`.
    */
  def server(
      address: UnixSocketAddress,
      deleteIfExists: Boolean = false,
      deleteOnClose: Boolean = true
  ): Stream[F, Socket[F]]
}

object UnixSockets {
  def apply[F[_]](implicit F: UnixSockets[F]): UnixSockets[F] = F

  def forIO: UnixSockets[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Async: LiftIO]: UnixSockets[F] =
    new AsyncUnixSockets[F]

  private class AsyncUnixSockets[F[_]: Async: LiftIO] extends UnixSockets[F] {

    private val delegate = UnixSocketsProvider.forLiftIO[F]

    def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
      delegate.connect(Ip4sUnixSocketAddress(address.path), Nil)

    def server(
        address: UnixSocketAddress,
        deleteIfExists: Boolean,
        deleteOnClose: Boolean
    ): Stream[F, Socket[F]] =
      Stream
        .resource(
          delegate.bind(
            Ip4sUnixSocketAddress(address.path),
            List(
              SocketOption.unixServerSocketDeleteIfExists(deleteIfExists),
              SocketOption.unixServerSocketDeleteOnClose(deleteOnClose)
            )
          )
        )
        .flatMap(_.accept)
  }
}
