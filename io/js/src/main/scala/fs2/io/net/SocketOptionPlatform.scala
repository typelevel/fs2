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

import fs2.js.node.bufferMod.global.BufferEncoding
import fs2.js.node.netMod
import scala.concurrent.duration.FiniteDuration
import cats.effect.kernel.Sync

private[net] trait SocketOptionCompanionPlatform { self: SocketOption.type =>
  sealed trait Key[A] {
    private[net] def set[F[_]: Sync](sock: netMod.Socket, value: A): F[Unit]
  }

  private object Encoding extends Key[BufferEncoding] {
    override private[net] def set[F[_]: Sync](sock: netMod.Socket, value: BufferEncoding): F[Unit] =
      Sync[F].delay(sock.setEncoding(value))
  }

  private object KeepAlive extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: netMod.Socket, value: Boolean): F[Unit] =
      Sync[F].delay(sock.setKeepAlive(value))
  }

  private object NoDelay extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: netMod.Socket, value: Boolean): F[Unit] =
      Sync[F].delay(sock.setNoDelay(value))
  }

  private object Timeout extends Key[FiniteDuration] {
    override private[net] def set[F[_]: Sync](sock: netMod.Socket, value: FiniteDuration): F[Unit] =
      Sync[F].delay(sock.setTimeout(value.toMillis.toDouble))
  }

  // def encoding(value: BufferEncoding): SocketOption = apply(Encoding, value)
  def keepAlive(value: Boolean): SocketOption = apply(KeepAlive, value)
  def noDelay(value: Boolean): SocketOption = apply(NoDelay, value)
  def timeout(value: FiniteDuration): SocketOption = apply(Timeout, value)

}
