package fs2.io.tcp

import java.net.SocketOption

/** Key-value pair of [[SocketOption]] and a corresponding value* */
final case class SocketOptionMapping[A](key: SocketOption[A], value: A)
object SocketOptionMapping {
  def boolean(
      key: SocketOption[java.lang.Boolean],
      value: Boolean
  ): SocketOptionMapping[java.lang.Boolean] =
    SocketOptionMapping(key, value)
  def integer(
      key: SocketOption[java.lang.Integer],
      value: Int
  ): SocketOptionMapping[java.lang.Integer] = SocketOptionMapping(key, value)
}
