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

package fs2.protocols.mpeg
package transport
package psi

import scodec.Codec
import scodec.codecs._

import Descriptor._

case class ProgramMapTable(
    programNumber: ProgramNumber,
    version: Int,
    current: Boolean,
    pcrPid: Pid,
    programInfoDescriptors: List[Descriptor],
    componentStreamMapping: Map[StreamType, List[ProgramMapRecord]]
) extends Table {
  def tableId = ProgramMapSection.TableId
}

object ProgramMapTable {

  def toSection(pmt: ProgramMapTable): ProgramMapSection =
    ProgramMapSection(
      SectionExtension(pmt.programNumber.value, pmt.version, pmt.current, 0, 0),
      pmt.pcrPid,
      pmt.programInfoDescriptors,
      (for {
        (st, pmrs) <- pmt.componentStreamMapping.toVector
        pmr <- pmrs
      } yield (st, pmr)).sortBy { case (k, v) => (k.value, v.pid.value) }
    )

  def fromSection(section: ProgramMapSection): ProgramMapTable = {
    val componentStreamMapping = section.componentStreamMapping
      .foldLeft(Map.empty[StreamType, List[ProgramMapRecord]]) { case (acc, (st, pmr)) =>
        acc.updated(st, acc.get(st).fold(List(pmr))(existing => pmr :: existing))
      }
      .map { case (k, v) => (k, v.reverse) }
    ProgramMapTable(
      section.programNumber,
      section.extension.version,
      section.extension.current,
      section.pcrPid,
      section.programInfoDescriptors,
      componentStreamMapping
    )
  }

  implicit val tableSupport: TableSupport[ProgramMapTable] = new TableSupport[ProgramMapTable] {
    def tableId = ProgramMapSection.TableId
    def toTable(gs: GroupedSections[Section]) =
      gs.narrow[ProgramMapSection].toRight("Not PMT sections").flatMap { sections =>
        if (sections.tail.isEmpty) Right(fromSection(sections.head))
        else Left(s"PMT supports only 1 section but got ${sections.list.size}")
      }
    def toSections(pmt: ProgramMapTable) = GroupedSections(ProgramMapTable.toSection(pmt))
  }
}

case class StreamType(value: Int)
case class ProgramMapRecord(pid: Pid, descriptors: List[Descriptor])
object ProgramMapRecord {
  def apply(pid: Pid) = new ProgramMapRecord(pid, Nil)
}

case class ProgramMapSection(
    extension: SectionExtension,
    pcrPid: Pid,
    programInfoDescriptors: List[Descriptor],
    componentStreamMapping: Vector[(StreamType, ProgramMapRecord)]
) extends ExtendedSection {
  def tableId = ProgramMapSection.TableId
  def programNumber: ProgramNumber = ProgramNumber(extension.tableIdExtension)
}

object ProgramMapSection {
  val TableId = 2

  private type Fragment = (Pid, List[Descriptor], Vector[(StreamType, ProgramMapRecord)])
  private val fragmentCodec: Codec[Fragment] = {
    def pid: Codec[Pid] = reserved(3) ~> Codec[Pid]
    def descriptors: Codec[List[Descriptor]] =
      reserved(4) ~> variableSizeBytes(uint(12), list(Descriptor.codec))
    def programMapRecord: Codec[ProgramMapRecord] =
      (("pid" | pid) :: ("es_descriptors" | descriptors)).as[ProgramMapRecord]

    ("pcr_pid" | pid) ::
      ("program_info_descriptors" | descriptors) ::
      vector {
        (("stream_type" | uint8.as[StreamType]) :: programMapRecord)
          .as[(StreamType, ProgramMapRecord)]
      }
  }.as[Fragment]

  implicit val sectionSubCodec: SectionFragmentCodec[ProgramMapSection] =
    SectionFragmentCodec.psi[ProgramMapSection, Fragment](
      TableId,
      (ext, fragment) =>
        fragment match {
          case (pcrPid, descriptors, mapping) =>
            ProgramMapSection(ext, pcrPid, descriptors, mapping)
        },
      pmt => (pmt.extension, (pmt.pcrPid, pmt.programInfoDescriptors, pmt.componentStreamMapping))
    )(fragmentCodec)
}
