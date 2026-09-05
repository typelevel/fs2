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

import cats.effect.{Resource, Sync}
import cats.syntax.all._
import com.comcast.ip4s.{
  GenSocketAddress,
  IpAddress,
  Ipv4Address,
  Ipv6Address,
  Port,
  SocketAddress,
  UnixSocketAddress
}

import java.net.SocketOption
import java.net.StandardSocketOptions
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno.ENOPROTOOPT
import scala.scalanative.posix.netinet.in.IPPROTO_TCP
import scala.scalanative.posix.netinet.tcp._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.sys.socketOps._
import scala.scalanative.posix.netinet.in._
import scala.scalanative.posix.arpa.inet._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import NativeUtil._
import netinetinOps._
import syssocket._
import sysun._
import sysunOps._

private[io] object SocketHelpers {

  def openNonBlocking[F[_]](domain: CInt, `type`: CInt)(implicit F: Sync[F]): Resource[F, CInt] =
    Resource
      .make {
        F.delay {
          val SOCK_NONBLOCK =
            if (LinktimeInfo.isLinux)
              syssocket.SOCK_NONBLOCK
            else 0

          guard(socket(domain, `type` | SOCK_NONBLOCK, 0))
        }
      }(fd => F.delay(guard_(close(fd))))
      .evalTap { fd =>
        (if (!LinktimeInfo.isLinux) setNonBlocking(fd) else F.unit) *>
          (if (LinktimeInfo.isMac) setNoSigPipe(fd) else F.unit)
      }

  // TODO: Support other options (e.g. extended options?)

  def supportedOptions[F[_]: Sync]: F[Set[SocketOption[?]]] =
    Sync[F].pure(
      Set(
        StandardSocketOptions.SO_SNDBUF,
        StandardSocketOptions.SO_RCVBUF,
        StandardSocketOptions.SO_REUSEADDR,
        StandardSocketOptions.SO_REUSEPORT,
        StandardSocketOptions.SO_KEEPALIVE,
        StandardSocketOptions.TCP_NODELAY,
        fs2.io.net.SocketOption.OriginalDestination
      )
    )

  def getOption[F[_]: Sync, A](fd: CInt, name: SocketOption[A]): F[Option[A]] = (name match {
    case StandardSocketOptions.SO_SNDBUF =>
      getOptionInt(fd, SO_SNDBUF)
    case StandardSocketOptions.SO_RCVBUF =>
      getOptionInt(fd, SO_RCVBUF)
    case StandardSocketOptions.SO_REUSEADDR =>
      getOptionInt(fd, SO_REUSEADDR)
    case StandardSocketOptions.SO_REUSEPORT =>
      getOptionBool(fd, SO_REUSEPORT)
    case StandardSocketOptions.SO_KEEPALIVE =>
      getOptionBool(fd, SO_KEEPALIVE)
    case StandardSocketOptions.TCP_NODELAY =>
      getTcpOptionBool(fd, TCP_NODELAY)
    case fs2.io.net.SocketOption.OriginalDestination =>
      // linux kernel option: https://github.com/torvalds/linux/blob/master/include/uapi/linux/netfilter_ipv4.h#L52
      val SO_ORIGINAL_DST = 80
      getIpOptSocketAddress(fd, SO_ORIGINAL_DST)
    case _ => Sync[F].pure(None)
  }).asInstanceOf[F[Option[A]]]

  def getOptionBool[F[_]: Sync](fd: CInt, option: CInt): F[Option[Boolean]] =
    getOptionInt(fd, option).map(_.map(v => if (v == 0) false else true))

  def getOptionInt[F[_]: Sync](fd: CInt, option: CInt): F[Option[Int]] =
    getOptionImpl(fd, SOL_SOCKET, option)

  def getTcpOptionBool[F[_]: Sync](fd: CInt, option: CInt): F[Option[Boolean]] =
    getTcpOptionInt(fd, option).map(_.map(v => if (v == 0) false else true))

  def getTcpOptionInt[F[_]: Sync](fd: CInt, option: CInt): F[Option[Int]] =
    getOptionImpl(fd, IPPROTO_TCP /* aka SOL_TCP */, option)

  def getIpOptSocketAddress[F[_]](fd: CInt, option: CInt)(implicit
      F: Sync[F]
  ): F[Option[SocketAddress[IpAddress]]] =
    F.delay {
      val size = sizeOf[sockaddr_storage]
      val ptr = stackalloc[Byte](size)
      val szPtr = stackalloc[UInt]()
      !szPtr = size.toUInt
      val ret = guardMask(
        getsockopt(fd, IPPROTO_IP, option, ptr, szPtr)
      )(_ == ENOPROTOOPT)
      if (ret == ENOPROTOOPT) None
      else {
        val sa = ptr.asInstanceOf[Ptr[sockaddr]]
        Some(toSocketAddress(sa, sa.sa_family.toInt).asIpUnsafe)
      }
    }

  def getOptionImpl[F[_]](fd: CInt, level: CInt, option: CInt)(implicit
      F: Sync[F]
  ): F[Option[Int]] =
    F.delay {
      val ptr = stackalloc[CInt]()
      val szPtr = stackalloc[UInt]()
      !szPtr = sizeof[CInt].toUInt
      val ret = guardMask(
        getsockopt(
          fd,
          level,
          option,
          ptr.asInstanceOf[Ptr[Byte]],
          szPtr
        )
      )(_ == ENOPROTOOPT)
      if (ret == ENOPROTOOPT) None else Some(!ptr)
    }

  // macOS-only
  def setNoSigPipe[F[_]: Sync](fd: CInt): F[Unit] =
    setOption(fd, SO_NOSIGPIPE, true)

  def setOption[F[_]: Sync, T](fd: CInt, name: SocketOption[T], value: T): F[Unit] = name match {
    case StandardSocketOptions.SO_SNDBUF =>
      setOption(
        fd,
        SO_SNDBUF,
        value.asInstanceOf[java.lang.Integer]
      )
    case StandardSocketOptions.SO_RCVBUF =>
      setOption(
        fd,
        SO_RCVBUF,
        value.asInstanceOf[java.lang.Integer]
      )
    case StandardSocketOptions.SO_REUSEADDR =>
      setOption(
        fd,
        SO_REUSEADDR,
        value.asInstanceOf[java.lang.Boolean]
      )
    case StandardSocketOptions.SO_REUSEPORT =>
      SocketHelpers.setOption(
        fd,
        SO_REUSEPORT,
        value.asInstanceOf[java.lang.Boolean]
      )
    case StandardSocketOptions.SO_KEEPALIVE =>
      SocketHelpers.setOption(
        fd,
        SO_KEEPALIVE,
        value.asInstanceOf[java.lang.Boolean]
      )
    case StandardSocketOptions.TCP_NODELAY =>
      setTcpOption(
        fd,
        TCP_NODELAY,
        value.asInstanceOf[java.lang.Boolean]
      )
    case _ => throw new IllegalArgumentException
  }

  def setOption[F[_]](fd: CInt, option: CInt, value: Boolean)(implicit F: Sync[F]): F[Unit] =
    setOptionImpl(fd, SOL_SOCKET, option, if (value) 1 else 0)

  def setOption[F[_]](fd: CInt, option: CInt, value: CInt)(implicit F: Sync[F]): F[Unit] =
    setOptionImpl(fd, SOL_SOCKET, option, value)

  def setTcpOption[F[_]](fd: CInt, option: CInt, value: Boolean)(implicit F: Sync[F]): F[Unit] =
    setOptionImpl(
      fd,
      IPPROTO_TCP, // aka SOL_TCP
      option,
      if (value) 1 else 0
    )

  def setOptionImpl[F[_]](fd: CInt, level: CInt, option: CInt, value: CInt)(implicit
      F: Sync[F]
  ): F[Unit] =
    F.delay {
      val ptr = stackalloc[CInt]()
      !ptr = value
      guard_(
        setsockopt(
          fd,
          level,
          option,
          ptr.asInstanceOf[Ptr[Byte]],
          sizeof[CInt].toUInt
        )
      )
    }

  def checkSocketError[F[_]](fd: Int)(implicit F: Sync[F]): F[Unit] = F.delay {
    val optval = stackalloc[CInt]()
    val optlen = stackalloc[socklen_t]()
    !optlen = sizeof[CInt].toUInt
    guard_ {
      getsockopt(
        fd,
        SOL_SOCKET,
        SO_ERROR,
        optval.asInstanceOf[Ptr[Byte]],
        optlen
      )
    }
    if (!optval != 0)
      throw errnoToThrowable(!optval)
  }

  def getAddress(fd: Int, domain: CInt): GenSocketAddress =
    SocketHelpers.toSocketAddress(domain) { (addr, len) =>
      guard_(getsockname(fd, addr, len))
    }

  def getPeerAddress(fd: Int, domain: CInt): GenSocketAddress =
    SocketHelpers.toSocketAddress(domain) { (addr, len) =>
      guard_(getpeername(fd, addr, len))
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

  def allocateSockaddr[A](domain: CInt)(
      f: (Ptr[sockaddr], Ptr[socklen_t]) => A
  ): A = {
    // FIXME: Scala Native 0.4 doesn't support getsockname for unix sockets; after upgrading to 0.5,
    // this can be simplified to just allocate a sockaddr_un
    val (addr, lenValue) = // allocate enough for unix socket address
      if (domain == AF_UNIX)
        (stackalloc[sockaddr_un]().asInstanceOf[Ptr[sockaddr]], sizeof[sockaddr_un].toUInt)
      else (stackalloc[sockaddr_in6]().asInstanceOf[Ptr[sockaddr]], sizeof[sockaddr_in6].toUInt)

    val len = stackalloc[socklen_t]()
    !len = lenValue
    f(addr, len)
  }

  def toSocketAddress[A](domain: CInt)(
      f: (Ptr[sockaddr], Ptr[socklen_t]) => Unit
  ): GenSocketAddress = allocateSockaddr(domain) { (addr, len) =>
    f(addr, len)
    toSocketAddress(addr, domain)
  }

  def toSocketAddress(addr: Ptr[sockaddr], domain: CInt): GenSocketAddress =
    if (domain == AF_INET) toIpv4SocketAddress(addr.asInstanceOf[Ptr[sockaddr_in]])
    else if (domain == AF_INET6) toIpv6SocketAddress(addr.asInstanceOf[Ptr[sockaddr_in6]])
    else if (domain == AF_UNIX) toUnixSocketAddress(addr.asInstanceOf[Ptr[sockaddr_un]])
    else throw new UnsupportedOperationException

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
      memcpy(addr.atUnsafe(0), addrBytes, 16.toUSize)
      addr
    }.get
    SocketAddress(host, port)
  }

  private[this] def toUnixSocketAddress(addr: Ptr[sockaddr_un]): UnixSocketAddress = {
    val path = fromCString(addr.sun_path.asInstanceOf[CString])
    UnixSocketAddress(path)
  }
}
