package fs2
package io
package net

import com.comcast.ip4s.{GenSocketAddress, SocketAddress}
import java.net.{SocketAddress => JSocketAddress, InetSocketAddress}

private[net] object SocketAddressHelpers {

  def toGenSocketAddress(address: JSocketAddress): GenSocketAddress = {
    address match {
      case addr: InetSocketAddress => SocketAddress.fromInetSocketAddress(addr)
      case _ => throw new IllegalArgumentException("Unsupported address type: " + address)
    }
  }
}

