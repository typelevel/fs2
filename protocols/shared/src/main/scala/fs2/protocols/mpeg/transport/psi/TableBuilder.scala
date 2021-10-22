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

package fs2.protocols
package mpeg
package transport
package psi

case class TableBuildingError(tableId: Int, message: String) extends MpegError

class TableBuilder private (cases: Map[Int, List[TableSupport[_]]]) {

  def supporting[T <: Table](implicit ts: TableSupport[T]): TableBuilder = {
    val newCases = ts :: cases.getOrElse(ts.tableId, Nil)
    new TableBuilder(cases + (ts.tableId -> newCases))
  }

  def build(gs: GroupedSections[Section]): Either[TableBuildingError, Table] = {
    cases.get(gs.tableId) match {
      case None | Some(Nil) => Left(TableBuildingError(gs.tableId, "Unknown table id"))
      case Some(list) =>
        list.dropRight(1).foldRight[Either[String, _]](list.last.toTable(gs)) { (next, res) => res.fold(_ => next.toTable(gs), Right(_)) } match {
          case Right(table) => Right(table.asInstanceOf[Table])
          case Left(err) => Left(TableBuildingError(gs.tableId, err))
        }
    }
  }
}

object TableBuilder {

  def empty: TableBuilder = new TableBuilder(Map.empty)

  def supporting[T <: Table : TableSupport] = empty.supporting[T]

  def psi: TableBuilder =
    supporting[ProgramAssociationTable].
    supporting[ProgramMapTable].
    supporting[ConditionalAccessTable]
}

trait TableSupport[T <: Table] {
  def tableId: Int
  def toTable(gs: GroupedSections[Section]): Either[String, T]
  def toSections(t: T): GroupedSections[Section]
}

object TableSupport {

  def singleton[A <: Section with Table : reflect.ClassTag](tableId: Int): TableSupport[A] = {
    val tid = tableId
    new TableSupport[A] {
      def tableId = tid
      def toTable(gs: GroupedSections[Section]) =
        gs.narrow[A].toRight(s"Not a ${reflect.ClassTag[A]}").flatMap { sections =>
          if (sections.tail.isEmpty) Right(sections.head)
          else Left(s"${reflect.ClassTag[A]} supports only 1 section but got ${sections.list.size}")
        }
      def toSections(table: A) = GroupedSections(table)
    }
  }
}
