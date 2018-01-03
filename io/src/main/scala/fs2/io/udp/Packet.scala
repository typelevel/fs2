package fs2
package io
package udp

import java.net.InetSocketAddress

/**
  * A single packet to send to the specified remote address or received from the specified address.
  *
  * @param remote   remote party to send/receive packet to/from
  * @param bytes    data to send/receive
  */
final case class Packet(remote: InetSocketAddress, bytes: Chunk[Byte])
