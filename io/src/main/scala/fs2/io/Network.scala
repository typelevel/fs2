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

import cats.effect.Async

/**
 * Provides the ability to work with TCP, UDP, and TLS.
 * 
 * @example {{{
 * import fs2.Stream
 * import fs2.io.{Network, udp}
 * 
 * def send[F[_]: Network](socketGroup: udp.SocketGroup, packet: udp.Packet): F[Unit] =
 *   socketGroup.open().use { socket =>
 *     socket.write(packet)
 *   }
 * }}}
 * 
 * In this example, the `F[_]` parameter to `send` requires the `Network` constraint instead
 * of requiring the much more powerful `Async`.
 * 
 * An instance is available for any effect `F` which has an `Async[F]` instance.
 */
sealed trait Network[F[_]] {
  private[io] implicit val async: Async[F]
}

object Network {
  def apply[F[_]](implicit F: Network[F]): F.type = F

  implicit def forAsync[F[_]](implicit F: Async[F]): Network[F] =
    new Network[F] { val async = F }
}
