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

import cats.effect.kernel.Async
import jnr.unixsocket.{
  UnixServerSocketChannel,
  UnixSocketChannel,
  UnixSocketAddress => JnrUnixSocketAddress
}

object JnrUnixSockets {

  lazy val supported: Boolean =
    try {
      Class.forName("jnr.unixsocket.UnixSocketChannel")
      true
    } catch {
      case _: ClassNotFoundException => false
    }

  implicit def forAsync[F[_]](implicit F: Async[F]): UnixSockets[F] =
    new UnixSockets.AsyncUnixSockets[F] {
      protected def openChannel(address: UnixSocketAddress) =
        F.delay(UnixSocketChannel.open(new JnrUnixSocketAddress(address.path)))

      protected def openServerChannel(address: UnixSocketAddress) = F.blocking {
        val serverChannel = UnixServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        val sock = serverChannel.socket()
        sock.bind(new JnrUnixSocketAddress(address.path))
        (F.blocking(serverChannel.accept()), F.blocking(serverChannel.close()))
      }
    }
}
