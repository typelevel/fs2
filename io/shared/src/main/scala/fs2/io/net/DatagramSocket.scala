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

import com.comcast.ip4s._

/** Provides the ability to read/write from a UDP socket in the effect `F`.
  */
trait DatagramSocket[F[_]] extends DatagramSocketPlatform[F] {

  /** Local address of this socket. */
  def address: GenSocketAddress

  /** Gets the set of options that may be used with `setOption`. Note some options may not support `getOption`. */
  def supportedOptions: F[Set[SocketOption.Key[?]]]

  /** Gets the value of the specified option, if defined. */
  def getOption[A](key: SocketOption.Key[A]): F[Option[A]]

  /** Sets the specified option to the supplied value. */
  def setOption[A](key: SocketOption.Key[A], value: A): F[Unit]

  def readGen: F[GenDatagram]

  def connect(address: GenSocketAddress): F[Unit]
  def disconnect: F[Unit]

  /** Reads a single datagram from this udp socket.
    */
  def read: F[Datagram]

  /** Reads datagrams received from this udp socket.
    *
    * Note that multiple `reads` may execute at same time, causing each evaluation to receive fair
    * amount of messages.
    *
    * @return stream of datagrams
    */
  def reads: Stream[F, Datagram]

  /** Writes a single datagram to this udp socket.
    *
    * @param datagram datagram to write
    */
  def write(datagram: Datagram): F[Unit] =
    write(datagram.bytes, datagram.remote)

  def write(datagram: GenDatagram): F[Unit] =
    write(datagram.bytes, datagram.remote)

  def write(bytes: Chunk[Byte]): F[Unit]

  def write(bytes: Chunk[Byte], address: GenSocketAddress): F[Unit]

  /** Writes supplied datagrams to this udp socket.
    */
  def writes: Pipe[F, Datagram, Nothing]

  /** Returns the local address of this udp socket. */
  @deprecated(
    "3.13.0",
    "Use address instead, which returns GenSocketAddress instead of F[SocketAddress[IpAddress]]. If ip and port are needed, call .asIpUnsafe"
  )
  def localAddress: F[SocketAddress[IpAddress]]

  /** Joins a multicast group on a specific network interface.
    *
    * @param join group to join
    * @param interface network interface upon which to listen for datagrams
    */
  def join(
      join: MulticastJoin[IpAddress],
      interface: NetworkInterface
  ): F[GroupMembership]

  /** Joins a multicast group on a specific network interface.
    *
    * @param join group to join
    * @param interface network interface upon which to listen for datagrams
    */
  @deprecated("Use overload that takes a com.comcast.ip4s.NetworkInterface", "3.13.0")
  def join(
      join: MulticastJoin[IpAddress],
      interface: DatagramSocket.NetworkInterface
  ): F[GroupMembership]

  /** Result of joining a multicast group on a UDP socket. */
  trait GroupMembership extends GroupMembershipPlatform {

    /** Leaves the multicast group, resulting in no further datagrams from this group being read. */
    def drop: F[Unit]
  }
}

object DatagramSocket extends DatagramSocketCompanionPlatform
