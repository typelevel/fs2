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

import fs2._

sealed abstract class TransportStreamIndex {
  import TransportStreamIndex._

  def pat: Option[ProgramAssociationTable]
  def cat: Option[ConditionalAccessTable]

  def pmt(prg: ProgramNumber): Either[LookupError, ProgramMapTable]

  def programMapRecords(
      program: ProgramNumber,
      streamType: StreamType
  ): Either[LookupError, List[ProgramMapRecord]] =
    for {
      p <- pat.toRight(LookupError.MissingProgramAssociation)
      _ <- p.programByPid.get(program).toRight(LookupError.UnknownProgram)
      q <- pmt(program)
      pmrs <- q.componentStreamMapping.get(streamType).toRight(LookupError.UnknownStreamType)
    } yield pmrs

  def programManRecord(
      program: ProgramNumber,
      streamType: StreamType
  ): Either[LookupError, ProgramMapRecord] =
    programMapRecords(program, streamType).map(_.head)

  def withPat(pat: ProgramAssociationTable): TransportStreamIndex
  def withPmt(pmt: ProgramMapTable): TransportStreamIndex
  def withCat(cat: ConditionalAccessTable): TransportStreamIndex
}

object TransportStreamIndex {

  sealed abstract class LookupError
  object LookupError {
    case object UnknownProgram extends LookupError
    case object UnknownStreamType extends LookupError
    case object MissingProgramAssociation extends LookupError
    case object MissingProgramMap extends LookupError
  }

  private case class DefaultTransportStreamIndex(
      pat: Option[ProgramAssociationTable],
      cat: Option[ConditionalAccessTable],
      pmts: Map[ProgramNumber, ProgramMapTable]
  ) extends TransportStreamIndex {

    def pmt(prg: ProgramNumber): Either[LookupError, ProgramMapTable] =
      pmts.get(prg).toRight(LookupError.UnknownProgram)

    def withPat(pat: ProgramAssociationTable): TransportStreamIndex = {
      val programs = pat.programByPid.keys.toSet
      copy(pat = Some(pat), pmts = pmts.view.filter { case (k, v) => programs(k) }.toMap)
    }

    def withPmt(pmt: ProgramMapTable): TransportStreamIndex =
      copy(pmts = pmts + (pmt.programNumber -> pmt))

    def withCat(cat: ConditionalAccessTable): TransportStreamIndex =
      copy(cat = Some(cat))
  }

  def empty: TransportStreamIndex = DefaultTransportStreamIndex(None, None, Map.empty)

  def build: Scan[TransportStreamIndex, Table, Either[TransportStreamIndex, Table]] =
    Scan.stateful(empty) { (tsi, section) =>
      val updatedTsi = section match {
        case pat: ProgramAssociationTable =>
          Some(tsi.withPat(pat))
        case pmt: ProgramMapTable =>
          Some(tsi.withPmt(pmt))
        case cat: ConditionalAccessTable =>
          Some(tsi.withCat(cat))
        case other => None
      }
      val out = updatedTsi match {
        case Some(newTsi) if newTsi != tsi =>
          Chunk(Right(section), Left(newTsi))
        case _ =>
          Chunk(Right(section))
      }
      (updatedTsi.getOrElse(tsi), out)
    }
}
