package fs2.io.net.unixsocket

import jnr.unixsocket.{UnixSocketAddress => JnrUnixSocketAddress}

case class UnixSocketAddress(path: String) {
  private[unixsocket] def toJnr: JnrUnixSocketAddress = new JnrUnixSocketAddress(path)
}
