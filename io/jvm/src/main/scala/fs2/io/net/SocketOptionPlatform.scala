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
import java.net.NetworkInterface

private[net] trait SocketOptionCompanionPlatform {
  type Key[A] = JSocketOption[A]

  def boolean(key: JSocketOption[JBoolean], value: Boolean): SocketOption =
    SocketOption[JBoolean](key, value)

  def integer(key: JSocketOption[JInt], value: Int): SocketOption =
    SocketOption[JInt](key, value)

  def multicastInterface(value: NetworkInterface): SocketOption =
    SocketOption(StandardSocketOptions.IP_MULTICAST_IF, value)

  def multicastLoop(value: Boolean): SocketOption =
    boolean(StandardSocketOptions.IP_MULTICAST_LOOP, value)

  def multicastTtl(value: Int): SocketOption =
    integer(StandardSocketOptions.IP_MULTICAST_TTL, value)

  def typeOfService(value: Int): SocketOption =
    integer(StandardSocketOptions.IP_TOS, value)

  def broadcast(value: Boolean): SocketOption =
    boolean(StandardSocketOptions.SO_BROADCAST, value)

  def keepAlive(value: Boolean): SocketOption =
    boolean(StandardSocketOptions.SO_KEEPALIVE, value)

  def linger(value: Int): SocketOption =
    integer(StandardSocketOptions.SO_LINGER, value)

  def receiveBufferSize(value: Int): SocketOption =
    integer(StandardSocketOptions.SO_RCVBUF, value)

  def reuseAddress(value: Boolean): SocketOption =
    boolean(StandardSocketOptions.SO_REUSEADDR, value)

  def reusePort(value: Boolean): SocketOption =
    boolean(StandardSocketOptionsCompat.SO_REUSEPORT, value)

  def sendBufferSize(value: Int): SocketOption =
    integer(StandardSocketOptions.SO_SNDBUF, value)

  def noDelay(value: Boolean): SocketOption =
    boolean(StandardSocketOptions.TCP_NODELAY, value)
}
