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

package fs2.protocols
package pcapng

import fs2.protocols.pcap.LinkType.Ethernet
import scodec.Attempt.Successful
import scodec.DecodeResult
import scodec.bits.ByteOrdering.LittleEndian
import scodec.bits._

class BlockTest extends munit.FunSuite {
  import BlockTest.DHCP._
  import BlockTest.{RealData => RD}

  test("dhcp shb") {
    val actual = SectionHeaderBlock.codec.decode(SHB.bytes.bits)
    assertEquals(actual, fullyDecoded(SHB.expected))
  }

  test("dhcp idb") {
    val actual = InterfaceDescriptionBlock.codec(LittleEndian).decode(Interface.bytes.bits)
    assertEquals(actual, fullyDecoded(Interface.expected))
  }

  test("dhcp epb1") {
    val actual = EnhancedPacketBlock.codec(LittleEndian).decode(Enhanced1.bytes.bits)
    assertEquals(actual, fullyDecoded(Enhanced1.expected))
  }

  test("dhcp epb2") {
    val actual = EnhancedPacketBlock.codec(LittleEndian).decode(Enhanced2.bytes.bits)
    assertEquals(actual, fullyDecoded(Enhanced2.expected))
  }

  test("dhcp epb3") {
    val actual = EnhancedPacketBlock.codec(LittleEndian).decode(Enhanced3.bytes.bits)
    assertEquals(actual, fullyDecoded(Enhanced3.expected))
  }

  test("dhcp epb4") {
    val actual = EnhancedPacketBlock.codec(LittleEndian).decode(Enhanced4.bytes.bits)
    assertEquals(actual, fullyDecoded(Enhanced4.expected))
  }

  test("real-data dummy") {
    val actual = DummyBlock.codec(LittleEndian).decode(RD.bytes.bits)
    assertEquals(actual, fullyDecoded(RD.expectedDummy))
  }

  test("real-data epb") {
    val actual = EnhancedPacketBlock.codec(LittleEndian).decode(RD.bytes.bits)
    assertEquals(actual, fullyDecoded(RD.expectedEPB))
  }

  private def fullyDecoded[V](v: V) =
    Successful(DecodeResult(v, BitVector.empty))
}

private object BlockTest {

  //  https://gitlab.com/wireshark/wireshark/-/wikis/Development/PcapNg dhcp.pcapng
  object DHCP {
    object SHB {
      val header = hex"0a0d0d0a"
      val length = hex"1c000000"
      val parsed = hex"4d3c2b1a01000000"
      val nonParsed = hex"ffffffffffffffff"
      val bytes = header ++ length ++ parsed ++ nonParsed ++ length

      val expected = SectionHeaderBlock(Length(length), LittleEndian, 1, 0, nonParsed)
    }

    object Interface {
      val header = hex"01000000"
      val length = hex"20000000"
      val parsed = hex"01000000ffff0000"
      val nonParsed = hex"090001000600000000000000"
      val bytes = header ++ length ++ parsed ++ nonParsed ++ length
      val expected = InterfaceDescriptionBlock(Length(length), Ethernet, 65535, nonParsed)
    }

    val enhancedHeader = hex"06000000"

    val opts = ByteVector.empty

    object Enhanced1 {
      val length = hex"5c010000"
      val parsed = hex"0000000083ea03000d8a33353a0100003a010000"
      val data =
        hex"""ffffffffffff000b
            8201fc4208004500012ca8360000fa11178b00000000ffffffff004400430118
            591f0101060000003d1d0000000000000000000000000000000000000000000b
            8201fc4200000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000638253633501013d0701000b8201fc423204
            0000000037040103062aff00000000000000"""
      val padding = hex"0000"
      val bytes = enhancedHeader ++ length ++ parsed ++ data ++ padding ++ length

      val expected = EnhancedPacketBlock(Length(length), 0, 256643, 892570125, 314, 314, data, opts)
    }

    object Enhanced2 {
      val length = hex"78010000"
      val parsed = hex"0000000083ea0300348b33355601000056010000"
      val data =
        hex"""000b8201fc42000874adf19b
            0800450001480445000080110000c0a80001c0a8000a00430044013422330201
            060000003d1d0000000000000000c0a8000ac0a8000100000000000b8201fc42
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            00000000000000000000638253633501020104ffffff003a04000007083b0400
            000c4e330400000e103604c0a80001ff00000000000000000000000000000000
            00000000000000000000"""
      val padding = hex"0000"
      val bytes = enhancedHeader ++ length ++ parsed ++ data ++ padding ++ length

      val expected = EnhancedPacketBlock(Length(length), 0, 256643, 892570420, 342, 342, data, opts)
    }

    object Enhanced3 {
      val length = hex"5c010000"
      val pared = hex"0000000083ea03009c9b34353a0100003a010000"
      val data =
        hex"""ffffffff
            ffff000b8201fc4208004500012ca8370000fa11178a00000000ffffffff0044
            004301189fbd0101060000003d1e000000000000000000000000000000000000
            0000000b8201fc42000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            000000000000000000000000000000000000638253633501033d0701000b8201
            fc423204c0a8000a3604c0a8000137040103062aff00"""
      val padding = hex"0000"
      val bytes = enhancedHeader ++ length ++ pared ++ data ++ padding ++ length

      val expected = EnhancedPacketBlock(Length(length), 0, 256643, 892640156, 314, 314, data, opts)
    }

    object Enhanced4 {
      val length = hex"78010000"
      val parsed = hex"0000000083ea0300d69c34355601000056010000"
      val data =
        hex"""000b8201fc420008
            74adf19b0800450001480446000080110000c0a80001c0a8000a004300440134
            dfdb0201060000003d1e0000000000000000c0a8000a0000000000000000000b
            8201fc4200000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000000000000000000000000000000000000000
            0000000000000000000000000000638253633501053a04000007083b0400000c
            4e330400000e103604c0a800010104ffffff00ff000000000000000000000000
            0000000000000000000000000000"""
      val padding = hex"0000"
      val bytes = enhancedHeader ++ length ++ parsed ++ data ++ padding ++ length

      val expected = EnhancedPacketBlock(Length(length), 0, 256643, 892640470, 342, 342, data, opts)
    }
  }

  object RealData {
    val header = hex"06000000"
    val length = Length(hex"88000000")
    val props = hex"0000000003d205008418cd174200000042000000"
    val data =
      hex"""a483e7e0b1ad0200c9690a01080045000034ead7400022067cb336f4a8700a01
            07d40050cc0c19b4409fe64f4fb1801000953a4800000101080a2ff46c4b386d
            1949"""
    val padding = hex"0000"
    val opts = hex"018004000400000002000400010000000280040000000000048004000800000000000000"
    val bytes = header ++ length.bv ++ props ++ data ++ padding ++ opts ++ length.bv

    val expectedEPB = EnhancedPacketBlock(length, 0, 381443, 399317124, 66, 66, data, opts)
    val expectedDummy = DummyBlock(header, length, props ++ data ++ padding ++ opts)
  }
}
