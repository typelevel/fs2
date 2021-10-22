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
package mpeg
package transport
package psi

import scodec.Err
import scodec.bits._
import scodec.codecs._

class SectionCodecTest extends Fs2Suite {

  group("support decoding a stream of packets in to a stream of sections") {

    val sectionCodec = SectionCodec.supporting[ProgramAssociationSection]

    test("handles case where section starts at beginning of packet and is fully contained within packet") {
      val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true, Map(ProgramNumber(1) -> Pid(2)))).head
      val pasEnc = sectionCodec.encode(pas).require
      val packet = Packet.payload(Pid(0), ContinuityCounter(0), Some(0), pasEnc)

      val p = Stream.emit(packet) through Demultiplexer.demultiplex(sectionCodec).toPipe
      assertEquals(p.toList, List(pas).map(s => PidStamped(Pid(0), Right(Demultiplexer.SectionResult(s)))))
    }

    test("handles case where section starts at beginning of packet and spans multiple packets") {
      val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true,
        (for (i <- 0 until ProgramAssociationTable.MaxProgramsPerSection)
        yield ProgramNumber(i) -> Pid(i)).toMap
      )).head
      val pasEnc = sectionCodec.encode(pas).require
      val packets = Packet.packetize(Pid(0), ContinuityCounter(0), pasEnc)

      val p = Stream.emits(packets) through Demultiplexer.demultiplex(sectionCodec).toPipe
      assertEquals(p.toList, List(pas).map(s => PidStamped(Pid(0), Right(Demultiplexer.SectionResult(s)))))
    }

    test("checks packet continuity") {
      val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true,
        (for (i <- 0 until ProgramAssociationTable.MaxProgramsPerSection)
        yield ProgramNumber(i) -> Pid(i)).toMap
      )).head
      val pasEnc = sectionCodec.encode(pas).require
      val packets = Packet.packetize(Pid(0), ContinuityCounter(1), pasEnc)
      val withDiscontinuity = packets.updated(0, packets.head.copy(header = packets.head.header.copy(continuityCounter = ContinuityCounter(15))))

      val p = Stream.emits(withDiscontinuity) through Demultiplexer.demultiplex(sectionCodec).toPipe
      assertEquals(p.toList, List(PidStamped(Pid(0), Left(DemultiplexerError.Discontinuity(ContinuityCounter(15), ContinuityCounter(2), 1)))))
    }

    test("upon decoding failure of a section, remaining sections in packet are decoded") {
      case class SmallSection(x: Int) extends Section { def tableId = 0 }
      val sections = List(SmallSection(0), SmallSection(1))

      implicit val sfc: SectionFragmentCodec[SmallSection] =
        SectionFragmentCodec.nonExtended[SmallSection, Int](0, h => (constant(bin"0") ~> uint(7)), (p, i) => SmallSection(i), ss => (bin"010", ss.x))
      val sc = SectionCodec.supporting[SmallSection]

      val encodedSections = sections.toVector map { s => sc.encode(s).require }
      val ss0 = encodedSections(0).bytes
      val ss1 = encodedSections(1).bytes
      val indexOfInt = ss0.toIndexedSeq.zipWithIndex.find { case (x, idx) => ss1(idx.toLong) != x }.map { case (x, idx) => idx }.get
      val ss255 = ss0.update(indexOfInt.toLong, 255.toByte)

      val packets = Packet.packetizeMany(Pid(0), ContinuityCounter(0), ss255.bits +: encodedSections)
      val p = Stream.emits(packets) through Demultiplexer.demultiplex(sc).toPipe

      assertEquals(p.toList,
        PidStamped(Pid(0), Left(DemultiplexerError.Decoding(hex"002001ff".bits, Err("expected constant BitVector(1 bits, 0x0) but got BitVector(1 bits, 0x8)")))) +:
        sections.map { x => PidStamped(Pid(0), Right(Demultiplexer.SectionResult(x))) }
      )
    }

    test("reports invalid CRC") {
      val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true, Map(ProgramNumber(1) -> Pid(2)))).head
      val pasEnc = sectionCodec.encode(pas).require
      val corruptedSection = pasEnc.dropRight(32) ++ (~pasEnc.takeRight(32))
      val packet = Packet.payload(Pid(0), ContinuityCounter(0), Some(0), corruptedSection)
      val p = Stream.emit(packet) through Demultiplexer.demultiplex(sectionCodec).toPipe
      assertEquals(p.toList, List(PidStamped(Pid(0), Left(DemultiplexerError.Decoding(corruptedSection, Err("CRC mismatch: calculated 18564404 does not equal -18564405"))))))
    }

    test("does not report invalid CRC when verifyCrc is disabled") {
      val sectionCodec = SectionCodec.psi.disableCrcVerification.supporting[ProgramAssociationSection]
      val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true, Map(ProgramNumber(1) -> Pid(2)))).head
      val pasEnc = sectionCodec.encode(pas).require
      val corruptedSection = pasEnc.dropRight(32) ++ (~pasEnc.dropRight(32))
      val packet = Packet.payload(Pid(0), ContinuityCounter(0), Some(0), corruptedSection)
      val p = Stream.emit(packet) through Demultiplexer.demultiplex(sectionCodec).toPipe
      assertEquals(p.toList, List(pas).map(s => PidStamped(Pid(0), Right(Demultiplexer.SectionResult(s)))))
    }
  }
}
