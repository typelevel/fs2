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

import scala.reflect.ClassTag

import fs2._

/**
 * Group of sections that make up a logical message.
 *
 * Intermediate representation between sections and tables. All sections must share the same table id.
 */
sealed abstract class GroupedSections[+A <: Section] {
  def tableId: Int

  def head: A
  def tail: List[A]

  def list: List[A]
}

object GroupedSections {
  implicit class InvariantOps[A <: Section](val self: GroupedSections[A]) extends AnyVal {
    def narrow[B <: A : ClassTag]: Option[GroupedSections[B]] = {
      val matched = self.list.foldLeft(true) { (acc, s) => s match { case _: B => true; case _ => false } }
      if (matched) Some(self.asInstanceOf[GroupedSections[B]])
      else None
    }
  }

  private case class DefaultGroupedSections[A <: Section](head: A, tail: List[A]) extends GroupedSections[A] {
    val tableId = head.tableId
    val list = head :: tail
  }

  def apply[A <: Section](head: A, tail: List[A] = Nil): GroupedSections[A] =
    DefaultGroupedSections[A](head, tail)

  final case class ExtendedTableId(tableId: Int, tableIdExtension: Int)
  final case class ExtendedSectionGrouperState[A <: ExtendedSection](accumulatorByIds: Map[ExtendedTableId, SectionAccumulator[A]])

  def groupExtendedSections[A <: ExtendedSection]: Scan[ExtendedSectionGrouperState[A], A, Either[GroupingError, GroupedSections[A]]] = {
    def toKey(section: A): ExtendedTableId = ExtendedTableId(section.tableId, section.extension.tableIdExtension)
    Scan.stateful[ExtendedSectionGrouperState[A], A, Either[GroupingError, GroupedSections[A]]](ExtendedSectionGrouperState(Map.empty)) { (state, section) =>
      val key = toKey(section)
      val (err, acc) = state.accumulatorByIds.get(key) match {
        case None => (None, SectionAccumulator(section))
        case Some(acc) =>
          acc.add(section) match {
            case Right(acc) => (None, acc)
            case Left(err) => (Some(GroupingError(section.tableId, section.extension.tableIdExtension, err)), SectionAccumulator(section))
          }
      }

      acc.complete match {
        case None =>
          val newState = ExtendedSectionGrouperState(state.accumulatorByIds + (key -> acc))
          val out = err.map(e => Chunk.singleton(Left(e))).getOrElse(Chunk.empty)
          (newState, out)
        case Some(sections) =>
          val newState = ExtendedSectionGrouperState(state.accumulatorByIds - key)
          val out = Chunk.seq((Right(sections) :: err.map(e => Left(e)).toList).reverse)
          (newState, out)
      }
    }
  }

  def noGrouping: Scan[Unit, Section, Either[GroupingError, GroupedSections[Section]]] =
    Scan.lift(s => Right(GroupedSections(s)))

  /**
   * Groups sections in to groups.
   *
   * Extended sections, aka sections with the section syntax indicator set to true, are automatically handled.
   * Non-extended sections are emitted as singleton groups.
   */
  def group: Scan[ExtendedSectionGrouperState[ExtendedSection], Section, Either[GroupingError, GroupedSections[Section]]] = {
    groupGeneral((), noGrouping).imapState(_._2)(s => ((), s))
  }

  /**
   * Groups sections in to groups.
   *
   * Extended sections, aka sections with the section syntax indicator set to true, are automatically handled.
   * The specified `nonExtended` process is used to handle non-extended sections.
   */
  def groupGeneral[NonExtendedState](
    initialNonExtendedState: NonExtendedState,
    nonExtended: Scan[NonExtendedState, Section, Either[GroupingError, GroupedSections[Section]]]
  ): Scan[(NonExtendedState, ExtendedSectionGrouperState[ExtendedSection]), Section, Either[GroupingError, GroupedSections[Section]]] = {
    groupGeneralConditionally(initialNonExtendedState, nonExtended, _ => true)
  }

  /**
   * Groups sections in to groups.
   *
   * Extended sections, aka sections with the section syntax indicator set to true, are automatically handled if `true` is returned from the
   * `groupExtended` function when applied with the section in question.
   *
   * The specified `nonExtended` transducer is used to handle non-extended sections.
   */
  def groupGeneralConditionally[NonExtendedState](
    initialNonExtendedState: NonExtendedState,
    nonExtended: Scan[NonExtendedState, Section, Either[GroupingError, GroupedSections[Section]]],
    groupExtended: ExtendedSection => Boolean = _ => true
  ): Scan[(NonExtendedState, ExtendedSectionGrouperState[ExtendedSection]), Section, Either[GroupingError, GroupedSections[Section]]] = {
    Scan[(NonExtendedState, ExtendedSectionGrouperState[ExtendedSection]), Section, Either[GroupingError, GroupedSections[Section]]]((initialNonExtendedState, ExtendedSectionGrouperState(Map.empty)))({ case ((nonExtendedState, extendedState), section) =>
      section match {
        case s: ExtendedSection if groupExtended(s) =>
          val (s2, out) = groupExtendedSections.transform(extendedState, s)
          (nonExtendedState -> s2, out)
        case s: Section =>
          val (s2, out) = nonExtended.transform(nonExtendedState, s)
          (s2 -> extendedState, out)
      }
    }, { case (nonExtendedState, extendedState) => Chunk.concat(List(nonExtended.onComplete(nonExtendedState), groupExtendedSections.onComplete(extendedState))) })
  }
}
