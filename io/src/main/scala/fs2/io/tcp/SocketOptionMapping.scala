package fs2.io.tcp

import java.net.SocketOption

/** Key-value pair of [[SocketOption]] and a corresponding value**/
final case class SocketOptionMapping[A](key: SocketOption[A], value: A)
