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

package fs2.io.net
package tls

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import s2n._
import s2nutil._
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.effect.kernel.Resource

private[tls] final class S2nConnection[F[_]](
)

private[tls] object S2nConnection {
  def apply[F[_]](
      socket: Socket[F],
      clientMode: Boolean
  )(implicit F: Async[F]): Resource[F, S2nConnection[F]] =
    for {
      dispatcher <- Dispatcher.sequential[F]
      conn <- Resource.make(
        F.delay(guard(s2n_connection_new(if (clientMode) S2N_CLIENT.toUInt else S2N_SERVER.toUInt)))
      )(conn => F.delay(guard(s2n_connection_free(conn))))
    } yield new S2nConnection()
}
