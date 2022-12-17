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

package fs2.io.internal

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Ipv4Address
import com.comcast.ip4s.Ipv6Address
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress

import java.io.IOException
import scala.scalanative.posix.arpa.inet._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.sys.socketOps._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import NativeUtil._
import netinetin._
import netinetinOps._

private[io] object SocketAddressHelpers {

  def getLocalAddress[F[_]](fd: Int)(implicit F: Sync[F]): F[SocketAddress[IpAddress]] =
    SocketAddressHelpers.toSocketAddress { (addr, len) =>
      F.delay(guard_(getsockname(fd, addr, len)))
    }

  def allocateSockaddr[F[_]](implicit F: Sync[F]): Resource[F, (Ptr[sockaddr], Ptr[socklen_t])] =
    Resource
      .make(F.delay(Zone.open()))(z => F.delay(z.close()))
      .evalMap { implicit z =>
        F.delay {
          val addr = // allocate enough for an IPv6
            alloc[sockaddr_in6]().asInstanceOf[Ptr[sockaddr]]
          val len = alloc[socklen_t]()
          (addr, len)
        }
      }

  def toSockaddr[A](
      address: SocketAddress[IpAddress]
  )(f: (Ptr[sockaddr], socklen_t) => A): A =
    address.host.fold(
      _ =>
        toSockaddrIn(address.asInstanceOf[SocketAddress[Ipv4Address]])(
          f.asInstanceOf[(Ptr[sockaddr_in], socklen_t) => A]
        ),
      _ =>
        toSockaddrIn6(address.asInstanceOf[SocketAddress[Ipv6Address]])(
          f.asInstanceOf[(Ptr[sockaddr_in6], socklen_t) => A]
        )
    )

  private[this] def toSockaddrIn[A](
      address: SocketAddress[Ipv4Address]
  )(f: (Ptr[sockaddr_in], socklen_t) => A): A = {
    val addr = stackalloc[sockaddr_in]()
    val len = stackalloc[socklen_t]()

    toSockaddrIn(address, addr, len)

    f(addr, !len)
  }

  private[this] def toSockaddrIn6[A](
      address: SocketAddress[Ipv6Address]
  )(f: (Ptr[sockaddr_in6], socklen_t) => A): A = {
    val addr = stackalloc[sockaddr_in6]()
    val len = stackalloc[socklen_t]()

    toSockaddrIn6(address, addr, len)

    f(addr, !len)
  }

  def toSockaddr(
      address: SocketAddress[IpAddress],
      addr: Ptr[sockaddr],
      len: Ptr[socklen_t]
  ): Unit =
    address.host.fold(
      _ =>
        toSockaddrIn(
          address.asInstanceOf[SocketAddress[Ipv4Address]],
          addr.asInstanceOf[Ptr[sockaddr_in]],
          len
        ),
      _ =>
        toSockaddrIn6(
          address.asInstanceOf[SocketAddress[Ipv6Address]],
          addr.asInstanceOf[Ptr[sockaddr_in6]],
          len
        )
    )

  private[this] def toSockaddrIn(
      address: SocketAddress[Ipv4Address],
      addr: Ptr[sockaddr_in],
      len: Ptr[socklen_t]
  ): Unit = {
    !len = sizeof[sockaddr_in].toUInt
    addr.sin_family = AF_INET.toUShort
    addr.sin_port = htons(address.port.value.toUShort)
    addr.sin_addr.s_addr = htonl(address.host.toLong.toUInt)
  }

  private[this] def toSockaddrIn6[A](
      address: SocketAddress[Ipv6Address],
      addr: Ptr[sockaddr_in6],
      len: Ptr[socklen_t]
  ): Unit = {
    !len = sizeof[sockaddr_in6].toUInt

    addr.sin6_family = AF_INET6.toUShort
    addr.sin6_port = htons(address.port.value.toUShort)

    val bytes = address.host.toBytes
    var i = 0
    while (i < 0) {
      addr.sin6_addr.s6_addr(i) = bytes(i).toUByte
      i += 1
    }
  }

  def toSocketAddress[F[_]](
      f: (Ptr[sockaddr], Ptr[socklen_t]) => F[Unit]
  )(implicit F: Sync[F]): F[SocketAddress[IpAddress]] = {
    val addr = // allocate enough for an IPv6
      stackalloc[sockaddr_in6]().asInstanceOf[Ptr[sockaddr]]
    val len = stackalloc[socklen_t]()
    !len = sizeof[sockaddr_in6].toUInt

    f(addr, len) *> toSocketAddress(addr)
  }

  def toSocketAddress[F[_]](addr: Ptr[sockaddr])(implicit F: Sync[F]): F[SocketAddress[IpAddress]] =
    if (addr.sa_family.toInt == AF_INET)
      F.pure(toIpv4SocketAddress(addr.asInstanceOf[Ptr[sockaddr_in]]))
    else if (addr.sa_family.toInt == AF_INET6)
      F.pure(toIpv6SocketAddress(addr.asInstanceOf[Ptr[sockaddr_in6]]))
    else
      F.raiseError(new IOException(s"Unsupported sa_family: ${addr.sa_family}"))

  private[this] def toIpv4SocketAddress(addr: Ptr[sockaddr_in]): SocketAddress[Ipv4Address] = {
    val port = Port.fromInt(ntohs(addr.sin_port).toInt).get
    val addrBytes = addr.sin_addr.at1.asInstanceOf[Ptr[Byte]]
    val host = Ipv4Address.fromBytes(
      addrBytes(0).toInt,
      addrBytes(1).toInt,
      addrBytes(2).toInt,
      addrBytes(3).toInt
    )
    SocketAddress(host, port)
  }

  private[this] def toIpv6SocketAddress(addr: Ptr[sockaddr_in6]): SocketAddress[Ipv6Address] = {
    val port = Port.fromInt(ntohs(addr.sin6_port).toInt).get
    val addrBytes = addr.sin6_addr.at1.asInstanceOf[Ptr[Byte]]
    val host = Ipv6Address.fromBytes {
      val addr = new Array[Byte](16)
      var i = 0
      while (i < addr.length) {
        addr(i) = addrBytes(i.toLong)
        i += 1
      }
      addr
    }.get
    SocketAddress(host, port)
  }
}
