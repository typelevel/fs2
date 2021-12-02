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

import scodec.Codec
import scodec.bits._
import scodec.codecs._
import shapeless.ops.hlist.{Init, Last, Prepend}
import shapeless.{::, HList, HNil}

trait Block

object Block {

  type Length = ByteVector

  def orderDependent[T](big: ByteVector, little: ByteVector)(implicit ord: ByteOrdering): ByteVector =
    ord match {
      case ByteOrdering.BigEndian => big
      case ByteOrdering.LittleEndian => little
    }

  // format: off
  def block[L <: HList, LB <: HList](hexConstant: ByteVector)(f: Length => Codec[L])(
    implicit
    prepend: Prepend.Aux[L, Unit :: HNil, LB],
    init: Init.Aux[LB, L],
    last: Last.Aux[LB, Unit]
  ): Codec[Unit :: ByteVector :: LB] =
    ("block_type"       | constant(hexConstant)) ::
    ("block_length"     | bytes(4)).flatPrepend { length =>
      f(length) :+ constant(length)
    }

  def ignoredBlock(hexConstant: ByteVector)(implicit ord: ByteOrdering): Codec[Length :: ByteVector :: HNil] =
    block(hexConstant) { length =>
      ("block_bytes"    | fixedSizeBytes(length.toInt(signed = false, ord) - 12, bytes)) ::
      Codec.deriveHNil
    }.dropUnits
  // format: on
}
