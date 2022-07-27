package fs2.protocols
package ip

import fs2.interop.scodec.StreamDecoder
import fs2.protocols.ethernet.{EthernetFrameHeader, EtherType}

import com.comcast.ip4s.IpAddress

sealed trait IpHeader {
  def protocol: Int
  def sourceIp: IpAddress
  def destinationIp: IpAddress
}

object IpHeader {
  def sdecoder(ethernetHeader: EthernetFrameHeader): StreamDecoder[IpHeader] =
    ethernetHeader.ethertype match {
      case Some(EtherType.IPv4) => StreamDecoder.once(Ipv4Header.codec)
      case Some(EtherType.IPv6) => StreamDecoder.once(Ipv6Header.codec)
      case _ => StreamDecoder.empty
    }
}

private[ip] trait UnsealedIpHeader extends IpHeader
