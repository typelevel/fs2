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
import scala.scalanative.posix.arpa.inet._
import scala.scalanative.posix.errno.ENOPROTOOPT
import scala.scalanative.posix.netinet.in.IPPROTO_TCP
import scala.scalanative.posix.netinet.in.IPPROTO_IP
import scala.scalanative.posix.netinet.in.IPPROTO_IPV6
import scala.scalanative.posix.netinet.in.IP_TOS
import scala.scalanative.posix.netinet.in.IP_MULTICAST_LOOP
import scala.scalanative.posix.netinet.in.IP_MULTICAST_IF
import scala.scalanative.posix.netinet.tcp._
import scala.scalanative.posix.string._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.unistd._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.jdk.CollectionConverters._
import java.net.Inet4Address
import java.net.{NetworkInterface => JNetworkInterface}
import scala.scalanative.libc.errno.errno

import NativeUtil._
import netinetin._
import Ipmulticast._
import IpmulticastOps._
import netinetinOps._
import syssocket._
import sysun._
import sysunOps._
import com.comcast.ip4s.NetworkInterface
import com.comcast.ip4s.Cidr
import org.typelevel.scalaccompat.annotation._

@nowarn212("cat=unused")
@extern
object NetIf {
  def if_nametoindex(ifname: CString): CUnsignedInt = extern
}

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
        StandardSocketOptions.TCP_NODELAY
      )
    )

  def DatagramSupportedOptions[F[_]: Sync]: F[Set[SocketOption[?]]] =
    Sync[F].pure(
      Set(
        StandardSocketOptions.SO_SNDBUF,
        StandardSocketOptions.SO_RCVBUF,
        StandardSocketOptions.SO_REUSEADDR,
        StandardSocketOptions.SO_BROADCAST,
        StandardSocketOptions.IP_TOS,
        StandardSocketOptions.IP_MULTICAST_IF,
        StandardSocketOptions.IP_MULTICAST_TTL,
        StandardSocketOptions.IP_MULTICAST_LOOP,
        StandardSocketOptions.SO_REUSEPORT
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
    case StandardSocketOptions.IP_MULTICAST_LOOP =>
      getIpOptionBool(fd, IP_MULTICAST_LOOP)
    case StandardSocketOptions.IP_TOS =>
      getIpOptionInt(fd, IP_TOS)
    case StandardSocketOptions.SO_BROADCAST =>
      getOptionBool(fd, SO_BROADCAST)
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

  def getIpOptionInt[F[_]: Sync](fd: CInt, option: CInt): F[Option[Int]] =
    getOptionImpl(fd, IPPROTO_IP, option)

  def getIpOptionBool[F[_]: Sync](fd: CInt, option: CInt): F[Option[Boolean]] =
    getIpOptionInt(fd, option).map(_.map(v => if (v == 0) false else true))

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
    case StandardSocketOptions.IP_MULTICAST_LOOP =>
      setIpOption(fd, IP_MULTICAST_LOOP, if (value.asInstanceOf[Boolean]) 1 else 0)
    case StandardSocketOptions.IP_TOS =>
      setIpOption(fd, IP_TOS, value.asInstanceOf[Int])
    case StandardSocketOptions.SO_BROADCAST =>
      SocketHelpers.setOption(
        fd,
        SO_BROADCAST,
        value.asInstanceOf[java.lang.Boolean]
      )
    case StandardSocketOptions.IP_MULTICAST_TTL =>
      SocketHelpers.setIpOption(
        fd,
        IP_MULTICAST_TTL,
        value.asInstanceOf[java.lang.Integer]
      )
    case StandardSocketOptions.IP_MULTICAST_IF =>
      value match {
        case nif: JNetworkInterface =>
          val ifaceAddr = getFirstIpv4Address(nif).getOrElse(Ipv4Address.fromLong(0L))
          SocketHelpers.setIpMulticastIfByAddress(fd, ifaceAddr)

        case other =>
          throw new IllegalArgumentException(
            s"Expected java.net.NetworkInterface for IP_MULTICAST_IF but got ${other.getClass}"
          )
      }
    case _ =>
      throw new IllegalArgumentException
  }

  def setIpMulticastIfByAddress[F[_]: Sync](fd: CInt, interfaceAddr: Ipv4Address): F[Unit] =
    Sync[F].delay {
      val inAddr = stackalloc[in_addr]()
      (!inAddr).s_addr = htonl(interfaceAddr.toLong.toUInt)

      guard_(
        setsockopt(
          fd,
          IPPROTO_IP,
          IP_MULTICAST_IF,
          inAddr.asInstanceOf[Ptr[Byte]],
          sizeof[in_addr].toUInt
        )
      )
    }

  def getFirstIpv4Address(nif: JNetworkInterface): Option[Ipv4Address] =
    nif.getInetAddresses.asScala.collectFirst { case inet4: Inet4Address =>
      Ipv4Address.fromBytes(inet4.getAddress)
    }.flatten

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

  def setIpOption[F[_]: Sync](fd: CInt, option: CInt, value: CInt): F[Unit] =
    setOptionImpl(fd, IPPROTO_IP, option, value)

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
    while (i < 16) {
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
  private[this] def toIpmreq(
      multiaddr: Ipv4Address,
      address: Ipv4Address,
      mreq: Ptr[ip_mreq]
  ): Unit = {
    mreq.imr_multiaddr.s_addr = htonl(multiaddr.toLong.toUInt)
    mreq.imr_address.s_addr = htonl(address.toLong.toUInt)
  }

  private[this] def toIpmreq6(
      multiaddr: Ipv6Address,
      index: CInt,
      mreq: Ptr[ipv6_mreq]
  ): Unit = {
    val bytes = multiaddr.toBytes
    var i = 0
    while (i < 16) {
      mreq.ipv6mr_multiaddr.s6_addr(i) = bytes(i).toUByte
      i += 1
    }
    mreq.ipv6mr_ifindex = index
  }

  private[this] def toIpmreqSource(
      multiaddr: Ipv4Address,
      interface: Ipv4Address,
      sourceaddr: Ipv4Address,
      mreq_source: Ptr[ip_mreq_source]
  ): Unit = {
    mreq_source.imr_multiaddr.s_addr = htonl(multiaddr.toLong.toUInt)
    mreq_source.imr_interface.s_addr = htonl(interface.toLong.toUInt)
    mreq_source.imr_sourceaddr.s_addr = htonl(sourceaddr.toLong.toUInt)
  }

  def ipv4AddressOf(interface: NetworkInterface): Option[Ipv4Address] =
    interface.addresses.collectFirst { case Cidr(ipv4: Ipv4Address, _) =>
      ipv4
    }

  def join(fd: CInt, group: IpAddress, interface: NetworkInterface): Unit =
    group.fold(
      _ =>
        ipv4AddressOf(interface) match {
          case Some(ifaceAddr) =>
            setIpv4MulticastMembership(
              fd,
              group.asInstanceOf[Ipv4Address],
              ifaceAddr,
              IP_ADD_MEMBERSHIP
            )
          case None =>
            throw new Exception("No IPv4Address found for Network Interface")
        },
      _ => {
        val index = interfaceIndex(interface.name)
        setIpv6MulticastGroup(
          fd,
          group.asInstanceOf[Ipv6Address],
          index,
          IPV6_ADD_MEMBERSHIP
        )
      }
    )

  def join(fd: CInt, group: IpAddress, interface: NetworkInterface, source: IpAddress): Unit =
    group.fold(
      _ =>
        ipv4AddressOf(interface) match {
          case Some(ifaceAddr) =>
            setSourceSpecificGroup(
              fd,
              group.asInstanceOf[Ipv4Address],
              ifaceAddr,
              source.asInstanceOf[Ipv4Address],
              IP_ADD_SOURCE_MEMBERSHIP
            )
          case None =>
            throw new Exception("No IPv4Address found for Network Interface")
        },
      _ => throw new Exception("Source Specific Multicast not implemented for IPv6Address")
    )

  def drop(fd: CInt, group: IpAddress, interface: NetworkInterface): Unit =
    group.fold(
      _ =>
        ipv4AddressOf(interface) match {
          case Some(ifaceAddr) =>
            setIpv4MulticastMembership(
              fd,
              group.asInstanceOf[Ipv4Address],
              ifaceAddr,
              IP_DROP_MEMBERSHIP
            )
          case None =>
            throw new Exception("No IPv4Address found for Network Interface")
        },
      _ => {
        val index = interfaceIndex(interface.name)
        setIpv6MulticastGroup(
          fd,
          group.asInstanceOf[Ipv6Address],
          index,
          IPV6_DROP_MEMBERSHIP
        )
      }
    )

  def drop(fd: CInt, group: IpAddress, interface: NetworkInterface, source: IpAddress): Unit =
    group.fold(
      _ =>
        ipv4AddressOf(interface) match {
          case Some(ifaceAddr) =>
            setSourceSpecificGroup(
              fd,
              group.asInstanceOf[Ipv4Address],
              ifaceAddr,
              source.asInstanceOf[Ipv4Address],
              IP_DROP_SOURCE_MEMBERSHIP
            )
          case None =>
            throw new Exception("No IPv4Address found for Network Interface")
        },
      _ => throw new Exception("Source Specific Multicast not implemented for IPv6Address")
    )

  def block(fd: CInt, group: IpAddress, interface: NetworkInterface, source: IpAddress): Unit =
    group.fold(
      _ =>
        ipv4AddressOf(interface) match {
          case Some(ifaceAddr) =>
            setSourceSpecificGroup(
              fd,
              group.asInstanceOf[Ipv4Address],
              ifaceAddr,
              source.asInstanceOf[Ipv4Address],
              IP_BLOCK_SOURCE
            )
          case None =>
            throw new Exception("No IPv4Address found for Network Interface")
        },
      _ => throw new Exception("Block not implemented for IPv6Address")
    )

  def unblock(fd: CInt, group: IpAddress, interface: NetworkInterface, source: IpAddress): Unit =
    group.fold(
      _ =>
        ipv4AddressOf(interface) match {
          case Some(ifaceAddr) =>
            setSourceSpecificGroup(
              fd,
              group.asInstanceOf[Ipv4Address],
              ifaceAddr,
              source.asInstanceOf[Ipv4Address],
              IP_UNBLOCK_SOURCE
            )
          case None =>
            throw new Exception("No IPv4Address found for Network Interface")
        },
      _ => throw new Exception("Unblock not implemented for IPv6Address")
    )

  private[io] def setIpv4MulticastMembership(
      fd: CInt,
      multiaddr: Ipv4Address,
      interface: Ipv4Address,
      option: CInt
  ): Unit = {
    val mreq = stackalloc[ip_mreq]()
    toIpmreq(multiaddr, interface, mreq)
    guard_(
      setsockopt(
        fd,
        IPPROTO_IP,
        option,
        mreq.asInstanceOf[Ptr[Byte]],
        sizeof[ip_mreq].toUInt
      )
    )
  }

  private[io] def setSourceSpecificGroup(
      fd: CInt,
      multiaddr: Ipv4Address,
      interface: Ipv4Address,
      imr_sourceaddr: Ipv4Address,
      option: CInt
  ): Unit = {
    val mreq_source = stackalloc[ip_mreq_source]()
    toIpmreqSource(multiaddr, interface, imr_sourceaddr, mreq_source)
    guard_(
      setsockopt(
        fd,
        IPPROTO_IP,
        option,
        mreq_source.asInstanceOf[Ptr[Byte]],
        sizeof[ip_mreq].toUInt
      )
    )
  }

  private[io] def setIpv6MulticastGroup(
      fd: CInt,
      multiaddr: Ipv6Address,
      index: CInt,
      option: CInt
  ): Unit = {
    val mreq = stackalloc[ipv6_mreq]()
    toIpmreq6(multiaddr, index, mreq)
    guard_(
      setsockopt(
        fd,
        IPPROTO_IPV6,
        option,
        mreq.asInstanceOf[Ptr[Byte]],
        sizeof[ip_mreq].toUInt
      )
    )
  }

  def disconnectSockaddr[A](f: (Ptr[sockaddr], socklen_t) => A): A = {
    val addr = stackalloc[sockaddr]()
    for (i <- 0 until 14) addr._2(i) = 0
    addr._1 = AF_UNSPEC.toUShort
    f(addr, sizeof[sockaddr].toUInt)
  }

  def interfaceIndex(name: String): CInt = Zone.acquire { implicit x =>
    NetIf.if_nametoindex(toCString(name)).toInt
  }

}
