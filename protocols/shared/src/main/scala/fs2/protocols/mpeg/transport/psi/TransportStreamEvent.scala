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

import cats.data.Chain
import scodec.bits.BitVector

import psi.{Table => TableMessage}

abstract class TransportStreamEvent

object TransportStreamEvent {
  case class Pes(pid: Pid, pes: PesPacket) extends TransportStreamEvent
  case class Table(pid: Pid, table: TableMessage) extends TransportStreamEvent
  case class ScrambledPayload(pid: Pid, payload: BitVector) extends TransportStreamEvent
  case class Metadata[A](pid: Option[Pid], metadata: A) extends TransportStreamEvent
  case class Error(pid: Option[Pid], err: MpegError) extends TransportStreamEvent

  def pes(pid: Pid, pes: PesPacket): TransportStreamEvent = Pes(pid, pes)
  def table(pid: Pid, table: TableMessage): TransportStreamEvent = Table(pid, table)
  def scrambledPayload(pid: Pid, content: BitVector): TransportStreamEvent =
    ScrambledPayload(pid, content)
  def metadata[A](md: A): TransportStreamEvent = Metadata(None, md)
  def metadata[A](pid: Pid, md: A): TransportStreamEvent = Metadata(Some(pid), md)
  def error(pid: Pid, e: MpegError): TransportStreamEvent = Error(Some(pid), e)
  def error(pid: Option[Pid], e: MpegError): TransportStreamEvent = Error(pid, e)

  private def sectionsToTables[S](
      group: Scan[S, Section, Either[GroupingError, GroupedSections[Section]]],
      tableBuilder: TableBuilder
  ): Scan[(Map[Pid, S], TransportStreamIndex), PidStamped[
    Either[MpegError, Section]
  ], TransportStreamEvent] = {

    import MpegError._

    val sectionsToTablesForPid: Scan[S, Section, Either[MpegError, TableMessage]] =
      group.map {
        case Left(e)   => Left(e)
        case Right(gs) => tableBuilder.build(gs)
      }

    val sectionsToTables: Scan[Map[Pid, S], PidStamped[Either[MpegError, Section]], PidStamped[
      Either[MpegError, TableMessage]
    ]] =
      Scan(Map.empty[Pid, S])(
        { case (state, PidStamped(pid, e)) =>
          e match {
            case Right(section) =>
              val groupingState = state.getOrElse(pid, group.initial)
              val (s, out) = sectionsToTablesForPid.transform(groupingState, section)
              (state.updated(pid, s), out.map(PidStamped(pid, _)))
            case Left(err) =>
              (state, Chunk.singleton(PidStamped(pid, Left(err))))
          }
        },
        state =>
          Chunk.concat(
            state
              .foldLeft(Chain.empty[Chunk[PidStamped[Either[MpegError, TableMessage]]]]) {
                case (acc, (pid, gs)) =>
                  acc :+ sectionsToTablesForPid.onComplete(gs).map(PidStamped(pid, _))
              }
              .toList
          )
      )

    sectionsToTables.andThen(PidStamped.preserve(passErrors(TransportStreamIndex.build))).map {
      case PidStamped(pid, value) =>
        value match {
          case Left(e)           => error(pid, e)
          case Right(Left(tsi))  => metadata(tsi)
          case Right(Right(tbl)) => table(pid, tbl)
        }
    }
  }

  def fromPacketStream[S](
      sectionCodec: SectionCodec,
      group: Scan[S, Section, Either[GroupingError, GroupedSections[Section]]],
      tableBuilder: TableBuilder
  ): Scan[
    ((Map[Pid, ContinuityCounter], Demultiplexer.State), (Map[Pid, S], TransportStreamIndex)),
    Packet,
    TransportStreamEvent
  ] = {
    val demuxed =
      Demultiplexer
        .demultiplex(sectionCodec)
        .andThen(
          sectionsToTables(group, tableBuilder).semipass[PidStamped[
            Either[DemultiplexerError, Demultiplexer.Result]
          ], TransportStreamEvent]({
            case PidStamped(pid, Right(Demultiplexer.SectionResult(section))) =>
              Right(PidStamped(pid, Right(section)))
            case PidStamped(pid, Right(Demultiplexer.PesPacketResult(p))) => Left(pes(pid, p))
            case PidStamped(pid, Left(e))                                 => Right(PidStamped(pid, Left(e.toMpegError)))
          })
        )
    demuxed.semipass[Packet, TransportStreamEvent]({
      case Packet(header, _, _, Some(payload)) if header.scramblingControl != 0 =>
        Left(scrambledPayload(header.pid, payload))
      case p @ Packet(_, _, _, _) =>
        Right(p)
    })
  }

  def fromSectionStream[S](
      group: Scan[S, Section, Either[GroupingError, GroupedSections[Section]]],
      tableBuilder: TableBuilder
  ): Scan[(Map[Pid, S], TransportStreamIndex), PidStamped[Section], TransportStreamEvent] =
    sectionsToTables(group, tableBuilder).contramap[PidStamped[Section]](_.map(Right(_)))
}
