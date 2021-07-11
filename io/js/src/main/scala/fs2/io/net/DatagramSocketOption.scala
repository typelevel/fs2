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

import fs2.internal.jsdeps.node.dgramMod
import cats.effect.kernel.Sync

/** Specifies a socket option on a TCP/UDP socket.
  *
  * The companion provides methods for creating a socket option from each of the
  * JDK [[java.net.StandardSocketOptions]] as well as the ability to construct arbitrary
  * additional options. See the docs on `StandardSocketOptions` for details on each.
  */
sealed trait DatagramSocketOption {
  type Value
  val key: DatagramSocketOption.Key[Value]
  val value: Value
}

object DatagramSocketOption {
  sealed trait Key[A] {
    private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: A): F[Unit]
  }

  def apply[A](key0: Key[A], value0: A): DatagramSocketOption = new DatagramSocketOption {
    type Value = A
    val key = key0
    val value = value0
  }

  private object Broadcast extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: Boolean): F[Unit] =
      Sync[F].delay(sock.setBroadcast(value))
  }

  private object MulticastInterface extends Key[String] {
    override private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: String): F[Unit] =
      Sync[F].delay(sock.setMulticastInterface(value))
  }

  private object MulticastLoopback extends Key[Boolean] {
    override private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: Boolean): F[Unit] =
      Sync[F].delay(sock.setMulticastLoopback(value))
  }

  private object MulticastTtl extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setMulticastTTL(value.toDouble))
  }

  private object ReceiveBufferSize extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setRecvBufferSize(value.toDouble))
  }

  private object SendBufferSize extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setSendBufferSize(value.toDouble))
  }

  private object Ttl extends Key[Int] {
    override private[net] def set[F[_]: Sync](sock: dgramMod.Socket, value: Int): F[Unit] =
      Sync[F].delay(sock.setTTL(value.toDouble))
  }

  def broadcast(value: Boolean): DatagramSocketOption = apply(Broadcast, value)
  def multicastInterface(value: String): DatagramSocketOption = apply(MulticastInterface, value)
  def multicastLoopback(value: Boolean): DatagramSocketOption = apply(MulticastLoopback, value)
  def multicastTtl(value: Int): DatagramSocketOption = apply(MulticastTtl, value)
  def receiveBufferSize(value: Int): DatagramSocketOption = apply(ReceiveBufferSize, value)
  def sendBufferSize(value: Int): DatagramSocketOption = apply(SendBufferSize, value)
  def ttl(value: Int): DatagramSocketOption = apply(Ttl, value)
}
