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

import java.net.{SocketOption => JSocketOption}
import java.net.StandardSocketOptions

import java.lang.{Boolean => JBoolean, Integer => JInt}
import java.net.{NetworkInterface => JNetworkInterface}

import com.comcast.ip4s.NetworkInterface
import com.comcast.ip4s.SocketAddress
import com.comcast.ip4s.IpAddress

private[net] trait SocketOptionCompanionPlatform {
  type Key[A] = JSocketOption[A]

  def boolean(key: JSocketOption[JBoolean], value: Boolean): SocketOption =
    SocketOption[JBoolean](key, value)

  def integer(key: JSocketOption[JInt], value: Int): SocketOption =
    SocketOption[JInt](key, value)

  val MulticastInterface = StandardSocketOptions.IP_MULTICAST_IF
  def multicastInterface(value: NetworkInterface): SocketOption =
    SocketOption(MulticastInterface, JNetworkInterface.getByName(value.name))

  def multicastInterface(value: JNetworkInterface): SocketOption =
    SocketOption(MulticastInterface, value)

  val MulticastLoop = StandardSocketOptions.IP_MULTICAST_LOOP
  def multicastLoop(value: Boolean): SocketOption =
    boolean(MulticastLoop, value)

  val MulticastTtl = StandardSocketOptions.IP_MULTICAST_TTL
  def multicastTtl(value: Int): SocketOption =
    integer(MulticastTtl, value)

  val TypeOfService = StandardSocketOptions.IP_TOS
  def typeOfService(value: Int): SocketOption =
    integer(TypeOfService, value)

  val Broadcast = StandardSocketOptions.SO_BROADCAST
  def broadcast(value: Boolean): SocketOption =
    boolean(Broadcast, value)

  val KeepAlive = StandardSocketOptions.SO_KEEPALIVE
  def keepAlive(value: Boolean): SocketOption =
    boolean(KeepAlive, value)

  val Linger = StandardSocketOptions.SO_LINGER
  def linger(value: Int): SocketOption =
    integer(Linger, value)

  val ReceiveBufferSize = StandardSocketOptions.SO_RCVBUF
  def receiveBufferSize(value: Int): SocketOption =
    integer(ReceiveBufferSize, value)

  val ReuseAddress = StandardSocketOptions.SO_REUSEADDR
  def reuseAddress(value: Boolean): SocketOption =
    boolean(ReuseAddress, value)

  // Note: this option was added in Java 9 so lazily load it to avoid failure on Java 8
  lazy val ReusePort = StandardSocketOptions.SO_REUSEPORT
  def reusePort(value: Boolean): SocketOption =
    boolean(ReusePort, value)

  val SendBufferSize = StandardSocketOptions.SO_SNDBUF
  def sendBufferSize(value: Int): SocketOption =
    integer(SendBufferSize, value)

  val NoDelay = StandardSocketOptions.TCP_NODELAY
  def noDelay(value: Boolean): SocketOption =
    boolean(NoDelay, value)

  val OriginalDestination: Key[SocketAddress[IpAddress]] = new Key[SocketAddress[IpAddress]] {
    def name() = "SO_ORIGINAL_DST"
    def `type`() = classOf[SocketAddress[IpAddress]]
  }

  val UnixSocketDeleteIfExists: Key[JBoolean] = new Key[JBoolean] {
    def name() = "FS2_UNIX_DELETE_IF_EXISTS"
    def `type`() = classOf[JBoolean]
  }
  def unixSocketDeleteIfExists(value: JBoolean): SocketOption =
    boolean(UnixSocketDeleteIfExists, value)

  val UnixSocketDeleteOnClose: Key[JBoolean] = new Key[JBoolean] {
    def name() = "FS2_UNIX_DELETE_ON_CLOSE"
    def `type`() = classOf[JBoolean]
  }
  def unixSocketDeleteOnClose(value: Boolean): SocketOption =
    boolean(UnixSocketDeleteOnClose, value)
}
