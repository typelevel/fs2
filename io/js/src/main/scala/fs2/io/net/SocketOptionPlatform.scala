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

import cats.effect.kernel.Sync
import fs2.io.internal.facade

import scala.concurrent.duration._

private[net] trait SocketOptionCompanionPlatform { self: SocketOption.type =>
  sealed trait Key[A] {
    private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: A): F[Unit]
    private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[A]]
  }

  private object Encoding extends Key[String] {
    override private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: String): F[Unit] =
      Sync[F].delay {
        sock.setEncoding(value)
        ()
      }
    override private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[String]] =
      Sync[F].raiseError(new UnsupportedOperationException)
  }

  private object KeepAlive extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: Boolean): F[Unit] =
      Sync[F].delay {
        sock.setKeepAlive(value)
        ()
      }
    override private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[Boolean]] =
      Sync[F].raiseError(new UnsupportedOperationException)
  }

  private object NoDelay extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: Boolean): F[Unit] =
      Sync[F].delay {
        sock.setNoDelay(value)
        ()
      }

    override private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[Boolean]] =
      Sync[F].raiseError(new UnsupportedOperationException)
  }

  private object Timeout extends Key[FiniteDuration] {
    override private[net] def set[F[_]: Sync](
        sock: facade.net.Socket,
        value: FiniteDuration
    ): F[Unit] =
      Sync[F].delay {
        sock.setTimeout(value.toMillis.toDouble)
        ()
      }
    override private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[FiniteDuration]] =
      Sync[F].delay {
        Some(sock.timeout.toLong.millis)
      }
  }

  object UnixServerSocketDeleteIfExists extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](
        sock: facade.net.Socket,
        value: Boolean
    ): F[Unit] = Sync[F].unit
    override private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[Boolean]] =
      Sync[F].pure(None)
  }
 
  object UnixServerSocketDeleteOnClose extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](
        sock: facade.net.Socket,
        value: Boolean
    ): F[Unit] = Sync[F].unit
    override private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[Boolean]] =
      Sync[F].pure(None)
  }

  def encoding(value: String): SocketOption = apply(Encoding, value)
  def keepAlive(value: Boolean): SocketOption = apply(KeepAlive, value)
  def noDelay(value: Boolean): SocketOption = apply(NoDelay, value)
  def timeout(value: FiniteDuration): SocketOption = apply(Timeout, value)
  def unixServerSocketDeleteIfExists(value: Boolean): SocketOption =
    apply(UnixServerSocketDeleteIfExists, value)
  def unixServerSocketDeleteOnClose(value: Boolean): SocketOption =
    apply(UnixServerSocketDeleteOnClose, value)
}
