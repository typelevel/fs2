package fs2
package io
package net

import com.comcast.ip4s.{GenSocketAddress, SocketAddress, UnixSocketAddress}
import java.net.{InetSocketAddress, UnixDomainSocketAddress}
import jnr.unixsocket.{UnixSocketAddress => JnrUnixSocketAddress}

private[net] object SocketAddressHelpers {

  def toGenSocketAddress(address: java.net.SocketAddress): GenSocketAddress = {
    address match {
      case addr: InetSocketAddress => SocketAddress.fromInetSocketAddress(addr)
      case _ =>
        if (JdkUnixSocketsProvider.supported && address.isInstanceOf[UnixDomainSocketAddress]) {
          UnixSocketAddress(address.asInstanceOf[UnixDomainSocketAddress].getPath.toString)
        } else if (JnrUnixSocketsProvider.supported && address.isInstanceOf[JnrUnixSocketAddress]) {
          UnixSocketAddress(address.asInstanceOf[JnrUnixSocketAddress].path)
        } else throw new IllegalArgumentException("Unsupported address type: " + address)
    }
  }
}
