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
import fs2.io.file.Files
import fs2.io.net.{Socket, SocketOption, UnixSocketsProvider}

private[unixsocket] trait UnixSocketsCompanionPlatform {
  def forIO: UnixSockets[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Async: LiftIO]: UnixSockets[F] = {
    val _ = LiftIO[F]
    forAsyncAndFiles
  }

  def forAsyncAndFiles[F[_]: Async: Files]: UnixSockets[F] =
    new AsyncUnixSockets[F]

  def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] =
    forAsyncAndFiles(F, Files.forAsync(F))

  private class AsyncUnixSockets[F[_]: Files](implicit F: Async[F])
      extends UnixSockets[F] {

    private val delegate = UnixSocketsProvider.forAsyncAndFiles[F]

    def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
      delegate.connect(Ip4sUnixSocketAddress(address.path), Nil)

    def server(
        address: UnixSocketAddress,
        deleteIfExists: Boolean,
        deleteOnClose: Boolean
    ): Stream[F, Socket[F]] =
      Stream.resource(
        delegate.bind(Ip4sUnixSocketAddress(address.path),
          List(SocketOption.unixServerSocketDeleteIfExists(deleteIfExists),
               SocketOption.unixServerSocketDeleteOnClose(deleteOnClose)))
      ).flatMap(_.clients)
  }
}
