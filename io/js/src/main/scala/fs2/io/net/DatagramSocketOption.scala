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

import cats.effect.kernel.Sync
import fs2.io.internal.facade

/** Specifies a socket option on a TCP/UDP socket.
  *
  * The companion provides methods for creating a socket option from each of the
  * JDK `java.net.StandardSocketOptions` as well as the ability to construct arbitrary
  * additional options. See the docs on `StandardSocketOptions` for details on each.
  */
sealed trait DatagramSocketOption {
  type Value
  val key: DatagramSocketOption.Key[Value]
  val value: Value

  private[net] def toSocketOption: SocketOption =
    SocketOption(key.toSocketOption, value)
}

object DatagramSocketOption {
  sealed trait Key[A] {
    private[net] def get[F[_]: Sync](sock: facade.dgram.Socket): F[Option[A]] = {
      val _ = sock
      Sync[F].raiseError(new UnsupportedOperationException("option does not support get"))
    }

    private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: A): F[Unit]

    private[net] def toSocketOption: SocketOption.Key[A]
  }

  def apply[A](key0: Key[A], value0: A): DatagramSocketOption = new DatagramSocketOption {
    type Value = A
    val key = key0
    val value = value0
  }

  object Broadcast extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Boolean): F[Unit] =
      Sync[F].delay(sock.setBroadcast(value))
    override private[net] def toSocketOption: SocketOption.Key[Boolean] = SocketOption.Broadcast
  }

  object MulticastInterface extends Key[String] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: String): F[Unit] =
      Sync[F].delay(sock.setMulticastInterface(value))
    override private[net] def toSocketOption: SocketOption.Key[String] = SocketOption.MulticastInterface
  }

  object MulticastLoopback extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Boolean): F[Unit] =
      Sync[F].delay {
        sock.setMulticastLoopback(value)
        ()
      }
    override private[net] def toSocketOption: SocketOption.Key[Boolean] = SocketOption.MulticastLoop
  }

  object MulticastTtl extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay {
        sock.setMulticastTTL(value)
        ()
      }
    override private[net] def toSocketOption: SocketOption.Key[Int] = SocketOption.MulticastTtl
  }

  object ReceiveBufferSize extends Key[Int] {
    override private[net] def get[F[_]: Sync](sock: facade.dgram.Socket) =
      Sync[F].delay(Some(sock.getRecvBufferSize))
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setRecvBufferSize(value))
    override private[net] def toSocketOption: SocketOption.Key[Int] = SocketOption.ReceiveBufferSize
  }

  object SendBufferSize extends Key[Int] {
    override private[net] def get[F[_]: Sync](sock: facade.dgram.Socket) =
      Sync[F].delay(Some(sock.getSendBufferSize))
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setSendBufferSize(value))
    override private[net] def toSocketOption: SocketOption.Key[Int] = SocketOption.SendBufferSize
  }

  object Ttl extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: facade.dgram.Socket, value: Int): F[Unit] =
      Sync[F].delay {
        sock.setTTL(value)
        ()
      }
    override private[net] def toSocketOption: SocketOption.Key[Int] = SocketOption.Ttl
  }

  def broadcast(value: Boolean): DatagramSocketOption = apply(Broadcast, value)
  def multicastInterface(value: String): DatagramSocketOption = apply(MulticastInterface, value)
  def multicastLoopback(value: Boolean): DatagramSocketOption = apply(MulticastLoopback, value)
  def multicastTtl(value: Int): DatagramSocketOption = apply(MulticastTtl, value)
  def receiveBufferSize(value: Int): DatagramSocketOption = apply(ReceiveBufferSize, value)
  def sendBufferSize(value: Int): DatagramSocketOption = apply(SendBufferSize, value)
  def ttl(value: Int): DatagramSocketOption = apply(Ttl, value)
}
