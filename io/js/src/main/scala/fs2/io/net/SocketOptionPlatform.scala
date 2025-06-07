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

import scala.annotation.nowarn
import scala.concurrent.duration._

private[net] trait SocketOptionCompanionPlatform { self: SocketOption.type =>
  sealed trait Key[A] {
    @nowarn
    private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: A): F[Unit] =
      Sync[F].raiseError(new UnsupportedOperationException("option does not support TCP"))

    @nowarn
    private[net] def get[F[_]: Sync](sock: facade.net.Socket): F[Option[A]] =
      unsupportedGet

    @nowarn
    private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: A): F[Unit] =
      Sync[F].raiseError(new UnsupportedOperationException("option does not support UDP"))

    @nowarn
    private[net] def get[F[_]: Sync](sock: facade.dgram.Socket): F[Option[A]] =
      unsupportedGet
  }

  private def unsupportedGet[F[_]: Sync, A]: F[A] =
    Sync[F].raiseError(new UnsupportedOperationException("option does not support get"))

  object Encoding extends Key[String] {
    override private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: String): F[Unit] =
      Sync[F].delay {
        sock.setEncoding(value)
        ()
      }
  }
  def encoding(value: String): SocketOption = apply(Encoding, value)

  object KeepAlive extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: Boolean): F[Unit] =
      Sync[F].delay {
        sock.setKeepAlive(value)
        ()
      }
  }
  def keepAlive(value: Boolean): SocketOption = apply(KeepAlive, value)

  object NoDelay extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.net.Socket, value: Boolean): F[Unit] =
      Sync[F].delay {
        sock.setNoDelay(value)
        ()
      }
  }
  def noDelay(value: Boolean): SocketOption = apply(NoDelay, value)

  object Timeout extends Key[FiniteDuration] {
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
  def timeout(value: FiniteDuration): SocketOption = apply(Timeout, value)

  object UnixSocketDeleteIfExists extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](
        sock: facade.net.Socket,
        value: Boolean
    ): F[Unit] = Sync[F].unit
  }
  def unixSocketDeleteIfExists(value: Boolean): SocketOption =
    apply(UnixSocketDeleteIfExists, value)

  object UnixSocketDeleteOnClose extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](
        sock: facade.net.Socket,
        value: Boolean
    ): F[Unit] = Sync[F].unit
  }
  def unixSocketDeleteOnClose(value: Boolean): SocketOption =
    apply(UnixSocketDeleteOnClose, value)

  // Datagram options

  object Broadcast extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Boolean): F[Unit] =
      Sync[F].delay(sock.setBroadcast(value))
  }
  def broadcast(value: Boolean): SocketOption = apply(Broadcast, value)

  object MulticastInterface extends Key[String] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: String): F[Unit] =
      Sync[F].delay(sock.setMulticastInterface(value))
  }
  def multicastInterface(value: String): SocketOption = apply(MulticastInterface, value)

  object MulticastLoop extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Boolean): F[Unit] =
      Sync[F].delay {
        sock.setMulticastLoopback(value)
        ()
      }
  }
  def multicastLoop(value: Boolean): SocketOption = apply(MulticastLoop, value)

  object MulticastTtl extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay {
        sock.setMulticastTTL(value)
        ()
      }
  }
  def multicastTtl(value: Int): SocketOption = apply(MulticastTtl, value)

  object ReceiveBufferSize extends Key[Int] {
    override private[net] def get[F[_]: Sync](sock: facade.dgram.Socket) =
      Sync[F].delay(Some(sock.getRecvBufferSize))
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setRecvBufferSize(value))
  }
  def receiveBufferSize(value: Int): SocketOption = apply(ReceiveBufferSize, value)

  object SendBufferSize extends Key[Int] {
    override private[net] def get[F[_]: Sync](sock: facade.dgram.Socket) =
      Sync[F].delay(Some(sock.getSendBufferSize))
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setSendBufferSize(value))
  }
  def sendBufferSize(value: Int): SocketOption = apply(SendBufferSize, value)

  object Ttl extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay {
        sock.setTTL(value)
        ()
      }
  }
  def ttl(value: Int): SocketOption = apply(Ttl, value)
}
