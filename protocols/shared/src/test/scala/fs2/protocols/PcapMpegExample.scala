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

// Adapted from scodec-protocols, licensed under 3-clause BSD

package fs2
package protocols

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{Ipv4Address, SocketAddress}
import fs2.interop.scodec.StreamDecoder
import fs2.io.file.{Files, Path}
import fs2.timeseries.TimeStamped

import pcap.{CaptureFile, LinkType}

/** Example of decoding a PCAP file that contains:
  *  - captured ethernet frames
  *  - of IPv4 packets
  *  - of UDP datagrams
  *  - containing MPEG transport stream packets
  */
object PcapMpegExample extends IOApp.Simple {

  case class CapturedPacket(
      source: SocketAddress[Ipv4Address],
      destination: SocketAddress[Ipv4Address],
      packet: mpeg.transport.Packet
  )

  val run: IO[Unit] = {
    val decoder: StreamDecoder[TimeStamped[CapturedPacket]] = CaptureFile.payloadStreamDecoderPF {
      case LinkType.Ethernet =>
        for {
          ethernetHeader <- ethernet.EthernetFrameHeader.sdecoder
          ipHeader <- ip.Ipv4Header.sdecoder(ethernetHeader)
          udpDatagram <- ip.udp.DatagramHeader.sdecoder(ipHeader.protocol)
          packets <- StreamDecoder.tryMany(mpeg.transport.Packet.codec).map { p =>
            CapturedPacket(
              SocketAddress(ipHeader.sourceIp, udpDatagram.sourcePort),
              SocketAddress(ipHeader.destinationIp, udpDatagram.destinationPort),
              p
            )
          }
        } yield packets
    }

    Files[IO]
      .readAll(Path("path/to/pcap"))
      .through(decoder.toPipeByte)
      .map(_.toString)
      .foreach(IO.println)
      .compile
      .drain
  }
}
