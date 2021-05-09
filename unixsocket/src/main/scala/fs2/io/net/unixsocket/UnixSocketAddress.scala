package fs2.io.net.unixsocket

import java.io.File
import java.nio.file.Path
import jnr.unixsocket.{UnixSocketAddress => JnrUnixSocketAddress}

case class UnixSocketAddress(path: String) {
  private[unixsocket] def toJnr: JnrUnixSocketAddress = new JnrUnixSocketAddress(path)
}

object UnixSocketAddress {
  def apply(path: Path): UnixSocketAddress = apply(path.toFile())
  def apply(path: File): UnixSocketAddress = apply(path.getPath())
}
