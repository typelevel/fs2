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

class GroupingTest extends Fs2Suite {

  val des = GroupedSections.groupExtendedSections[ProgramAssociationSection].toPipe[Pure]

  val pat3: ProgramAssociationTable = {
    val pidMap = (0 until ProgramAssociationTable.MaxProgramsPerSection * 3).map { n =>
      ProgramNumber(n) -> Pid(n)
    }.toMap
    ProgramAssociationTable(TransportStreamId(5), 1, true, pidMap)
  }

  test("handles stream of a specific table id and extension") {
    val p = Stream.emits(pat3.toSections.list).through(des).map {
      case Right(sections) => ProgramAssociationTable.fromSections(sections)
      case l @ Left(_)     => l
    }
    assertEquals(p.toList, List(Right(pat3)))
  }

  test("handles stream containing sections for the same table id but differing extension ids") {
    val patA = pat3
    val patB = pat3.copy(
      tsid = TransportStreamId(pat3.tsid.value + 1),
      programByPid = pat3.programByPid.map { case (prg, Pid(n)) => prg -> Pid(n + 1) }
    )

    val sections = Stream.emits(patA.toSections.list).interleave(Stream.emits(patB.toSections.list))
    val p = sections.through(des).map(_.flatMap(ProgramAssociationTable.fromSections))
    assertEquals(p.toList, List(Right(patA), Right(patB)))
  }
}
