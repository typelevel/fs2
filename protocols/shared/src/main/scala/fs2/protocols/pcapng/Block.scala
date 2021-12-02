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

import pcap._
import scodec.Codec
import scodec.bits._
import scodec.codecs._
import shapeless.ops.hlist.{Init, Last, Prepend}
import shapeless.{::, HList, HNil}

sealed trait Block

object Block {

  case class SectionHeaderBlock(
    length: ByteVector,
    ordering: ByteOrdering,
    majorVersion: Int,
    minorVersion: Int,
    bytes: ByteVector
  ) extends Block

  sealed trait BodyBlock extends Block
  case class InterfaceDescriptionBlock(length: ByteVector, bytes: ByteVector) extends BodyBlock
  case class EnhancedPacketBlock(length: ByteVector, bytes: ByteVector) extends BodyBlock

  private val sectionHeaderConstant = hex"0A0D0D0A"
  private def interfaceDescriptionHex(implicit ord: ByteOrdering) =
    endiannessDependent(hex"00000001", hex"01000000")
  private def enhancedPacketHex(implicit ord: ByteOrdering) =
    endiannessDependent(hex"00000006", hex"06000000")

  private def endiannessDependent[T](big: ByteVector, little: ByteVector)(implicit ord: ByteOrdering) =
    ord match {
      case ByteOrdering.BigEndian => big
      case ByteOrdering.LittleEndian => little
    }

  // format: off
  private def block[L <: HList, LB <: HList](const: ByteVector)(f: ByteVector => Codec[L])(
    implicit
    prepend: Prepend.Aux[L, Unit :: HNil, LB],
    init: Init.Aux[LB, L],
    last: Last.Aux[LB, Unit]
  ): Codec[Unit :: ByteVector :: LB] =
    ("block_type"       | constant(const)) ::
    ("block_length"     | bytes(4)).flatPrepend { length =>
      f(length) :+ constant(length)
    }

  private def ignoredBlock(constant: ByteVector)(implicit ord: ByteOrdering) =
    block(constant) { length =>
      ("block_bytes"    | fixedSizeBytes(length.toInt(signed = false, ord) - 12, bytes)) ::
      Codec.deriveHNil
    }.dropUnits

  private def sectionHeader(implicit ord: ByteOrdering) = {
    ("version_major"    | guint16 ) ::
    ("version_minor"    | guint16 ) ::
    ("remaining"        | bytes   )
  }

  val sectionHeaderCodec: Codec[SectionHeaderBlock] = "SHB" | block(sectionHeaderConstant) { length =>
    ("magic_number"     | ByteOrderMagic).flatPrepend { implicit ord =>
    ("body_bytes"       | fixedSizeBytes(length.toInt(signed = false, ord) - 16, sectionHeader))
  }}.dropUnits.as[SectionHeaderBlock]
  // format: on

  def interfaceDescriptionBlock(implicit ord: ByteOrdering): Codec[InterfaceDescriptionBlock] =
    "IDB" | ignoredBlock(interfaceDescriptionHex).as[InterfaceDescriptionBlock]

  def enhancedPacketBlock(implicit ord: ByteOrdering): Codec[EnhancedPacketBlock] =
    "EPB" | ignoredBlock(enhancedPacketHex).as[EnhancedPacketBlock]
}
