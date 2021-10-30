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

import scodec.bits._
import scodec.{Attempt, Codec, Err}

trait SectionFragmentCodec[A] {
  type Repr
  def tableId: Int
  def subCodec(header: SectionHeader, verifyCrc: Boolean): Codec[Repr]
  def toSection(privateBits: BitVector, extension: Option[SectionExtension], data: Repr): Attempt[A]
  def fromSection(section: A): (BitVector, Option[SectionExtension], Repr)
}

object SectionFragmentCodec {
  private val PsiPrivateBits = bin"011"

  def psi[A, R: Codec](
      tableId: Int,
      toSection: (SectionExtension, R) => A,
      fromSection: A => (SectionExtension, R)
  ): SectionFragmentCodec[A] =
    extended[A, R](
      tableId,
      (_, ext, r) => toSection(ext, r),
      s => { val (ext, r) = fromSection(s); (PsiPrivateBits, ext, r) }
    )

  def extended[A, R](
      tableId: Int,
      toSection: (BitVector, SectionExtension, R) => A,
      fromSection: A => (BitVector, SectionExtension, R)
  )(implicit codecR: Codec[R]): SectionFragmentCodec[A] = {
    val tid = tableId
    val build = toSection
    val extract = fromSection
    new SectionFragmentCodec[A] {
      type Repr = R
      def tableId = tid
      def subCodec(header: SectionHeader, verifyCrc: Boolean) = codecR
      def toSection(privateBits: BitVector, extension: Option[SectionExtension], data: Repr) =
        Attempt.fromOption(
          extension.map(ext => build(privateBits, ext, data)),
          Err("extended section missing expected section extension")
        )
      def fromSection(section: A) =
        extract(section) match { case (privateBits, ext, data) => (privateBits, Some(ext), data) }
    }
  }

  def nonExtended[A, R](
      tableId: Int,
      toCodec: SectionHeader => Codec[R],
      toSection: (BitVector, R) => A,
      fromSection: A => (BitVector, R)
  ): SectionFragmentCodec[A] = {
    val tid = tableId
    val codec = toCodec
    val build = toSection
    val extract = fromSection
    new SectionFragmentCodec[A] {
      type Repr = R
      def tableId = tid
      def subCodec(header: SectionHeader, verifyCrc: Boolean) = codec(header)
      def toSection(privateBits: BitVector, extension: Option[SectionExtension], data: Repr) =
        Attempt.successful(build(privateBits, data))
      def fromSection(section: A) = {
        val (privateBits, r) = extract(section)
        (privateBits, None, r)
      }
    }
  }

  def nonExtendedWithCrc[A, R](
      tableId: Int,
      toCodec: (SectionHeader, Boolean) => Codec[R],
      toSection: (BitVector, R) => A,
      fromSection: A => (BitVector, R)
  ): SectionFragmentCodec[A] = {
    val tid = tableId
    val codec = toCodec
    val build = toSection
    val extract = fromSection
    new SectionFragmentCodec[A] {
      type Repr = R
      def tableId = tid
      def subCodec(header: SectionHeader, verifyCrc: Boolean) = codec(header, verifyCrc)
      def toSection(privateBits: BitVector, extension: Option[SectionExtension], data: Repr) =
        Attempt.successful(build(privateBits, data))
      def fromSection(section: A) = {
        val (privateBits, r) = extract(section)
        (privateBits, None, r)
      }
    }
  }

  def nonExtendedIdentity[A](
      tableId: Int,
      toCodec: SectionHeader => Codec[A]
  ): SectionFragmentCodec[A] =
    SectionFragmentCodec.nonExtended[A, A](
      tableId,
      sHdr => toCodec(sHdr),
      (_, a) => a,
      a => (BitVector.empty, a)
    )

  def nonExtendedIdentityWithCrc[A](
      tableId: Int,
      toCodec: (SectionHeader, Boolean) => Codec[A]
  ): SectionFragmentCodec[A] =
    SectionFragmentCodec.nonExtendedWithCrc[A, A](
      tableId,
      (sHdr, verifyCrc) => toCodec(sHdr, verifyCrc),
      (_, a) => a,
      a => (BitVector.empty, a)
    )
}
