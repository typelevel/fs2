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

/** Accumulates sections of the same table id and table id extension. */
private[psi] class SectionAccumulator[A <: ExtendedSection] private (
    val sections: GroupedSections[A],
    sectionByNumber: Map[Int, A]
) {

  def add(section: A): Either[String, SectionAccumulator[A]] = {
    def validate(err: => String)(f: Boolean): Either[String, Unit] =
      if (f) Right(()) else Left(err)

    def checkEquality[B](name: String)(f: A => B): Either[String, Unit] =
      validate(name + " do not match")(f(section) == f(sections.head))

    val sectionNumber = section.extension.sectionNumber
    for {
      _ <- checkEquality("table ids")(_.tableId)
      _ <- checkEquality("table id extensions")(_.extension.tableIdExtension)
      _ <- checkEquality("versions")(_.extension.version)
      _ <- checkEquality("last section numbers")(_.extension.lastSectionNumber)
      _ <- validate("invalid section number")(
        sectionNumber <= sections.head.extension.lastSectionNumber
      )
      _ <- validate("duplicate section number")(!sectionByNumber.contains(sectionNumber))
    } yield new SectionAccumulator(
      GroupedSections(section, sections.list),
      sectionByNumber + (section.extension.sectionNumber -> section)
    )
  }

  def complete: Option[GroupedSections[A]] =
    if (sectionByNumber.size == (sections.head.extension.lastSectionNumber + 1)) Some(sections)
    else None
}

private[psi] object SectionAccumulator {

  def apply[A <: ExtendedSection](section: A): SectionAccumulator[A] =
    new SectionAccumulator(
      GroupedSections(section),
      Map(section.extension.sectionNumber -> section)
    )
}
