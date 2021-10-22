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
import scodec.bits._
import scodec.codecs._

case class ConditionalAccessTable(
    version: Int,
    current: Boolean,
    descriptors: List[ConditionalAccessDescriptor]
) extends Table {
  def tableId = ConditionalAccessSection.TableId
  def toSections: GroupedSections[ConditionalAccessSection] =
    ConditionalAccessTable.toSections(this)
}

object ConditionalAccessTable {

  def toSections(cat: ConditionalAccessTable): GroupedSections[ConditionalAccessSection] = {
    val grouped = groupBasedOnSize(cat.descriptors.toVector)
    val lastSection = grouped.size - 1
    val sections = grouped.zipWithIndex.map { case (ds, idx) =>
      ConditionalAccessSection(
        SectionExtension(65535, cat.version, cat.current, idx, lastSection),
        ds.toList
      )
    }
    if (sections.isEmpty)
      GroupedSections(
        ConditionalAccessSection(SectionExtension(65535, cat.version, cat.current, 0, 0), Nil)
      )
    else
      GroupedSections(sections.head, sections.tail.toList)
  }

  private def groupBasedOnSize(
      sections: Vector[ConditionalAccessDescriptor]
  ): Vector[Vector[ConditionalAccessDescriptor]] = {
    val MaxBitsLeft = (1024 - 12) * 8L
    def sizeOf(c: ConditionalAccessDescriptor): Long = (6 * 8) + c.privateData.size
    @annotation.tailrec
    def go(
        remaining: Vector[ConditionalAccessDescriptor],
        cur: Vector[ConditionalAccessDescriptor],
        bitsLeft: Long,
        acc: Vector[Vector[ConditionalAccessDescriptor]]
    ): Vector[Vector[ConditionalAccessDescriptor]] =
      if (remaining.isEmpty) acc :+ cur
      else {
        val next = remaining.head
        val bitsNeeded = (6 * 8) + sizeOf(next)
        val newBitsLeft = bitsLeft - bitsNeeded
        if (newBitsLeft >= 0) go(remaining.tail, cur :+ next, newBitsLeft, acc)
        else {
          go(remaining, Vector.empty, MaxBitsLeft, acc :+ cur)
        }
      }
    go(sections, Vector.empty, MaxBitsLeft, Vector.empty)
  }

  def fromSections(
      sections: GroupedSections[ConditionalAccessSection]
  ): Either[String, ConditionalAccessTable] = {
    def extract[A](name: String, f: ConditionalAccessSection => A): Either[String, A] = {
      val extracted = sections.list.map(f).distinct
      if (extracted.size == 1) Right(extracted.head)
      else Left(s"sections have diferring $name: " + extracted.mkString(", "))
    }
    for {
      version <- extract("versions", _.extension.version)
    } yield {
      val current = sections.list.foldLeft(false)((acc, s) => acc || s.extension.current)
      ConditionalAccessTable(
        version,
        current,
        (for {
          section <- sections.list
          descriptor <- section.descriptors
        } yield descriptor)
      )
    }
  }

  implicit val tableSupport: TableSupport[ConditionalAccessTable] =
    new TableSupport[ConditionalAccessTable] {
      def tableId = ConditionalAccessSection.TableId
      def toTable(gs: GroupedSections[Section]) =
        gs.narrow[ConditionalAccessSection].toRight(s"Not CAT sections").flatMap { sections =>
          fromSections(sections)
        }
      def toSections(cat: ConditionalAccessTable) = ConditionalAccessTable.toSections(cat)
    }
}

case class ConditionalAccessSection(
    extension: SectionExtension,
    descriptors: List[ConditionalAccessDescriptor]
) extends ExtendedSection {
  def tableId = ConditionalAccessSection.TableId
}

object ConditionalAccessSection {
  val TableId = 1

  type Fragment = List[ConditionalAccessDescriptor]

  private val fragmentCodec: Codec[Fragment] =
    list(Codec[ConditionalAccessDescriptor])

  implicit val sectionFragmentCodec: SectionFragmentCodec[ConditionalAccessSection] =
    SectionFragmentCodec.psi[ConditionalAccessSection, Fragment](
      TableId,
      (ext, descriptors) => ConditionalAccessSection(ext, descriptors),
      cat => (cat.extension, cat.descriptors)
    )(fragmentCodec)
}

case class ConditionalAccessDescriptor(systemId: Int, pid: Pid, privateData: BitVector)

object ConditionalAccessDescriptor {
  val Tag = 9

  implicit val codec: Codec[ConditionalAccessDescriptor] = {
    constant(Tag) ~>
      variableSizeBytes(
        uint8,
        ("ca_system_id" | uint16) ::
          (reserved(3) ~>
            ("ca_pid" | Codec[Pid])) ::
          bits
      )
  }.as[ConditionalAccessDescriptor]

}
