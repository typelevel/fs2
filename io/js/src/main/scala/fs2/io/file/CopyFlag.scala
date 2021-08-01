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

package fs2
package io
package file

import cats.kernel.Monoid
import fs2.internal.jsdeps.node.fsMod

final class CopyFlag private (private val bits: Long) extends AnyVal {
  def jsBits: Long = bits ^ ~CopyFlag.ReplaceExisting.bits // Toggle the inverted bit
}

object CopyFlag extends CopyFlagCompanionApi {
  private def apply(bits: Long): CopyFlag = new CopyFlag(bits)
  private def apply(bits: Double): CopyFlag = CopyFlag(bits.toLong)

  val ReplaceExisting = CopyFlag(
    fsMod.constants.COPYFILE_EXCL
  ) // Reuse this bit with inverted semantics
  val Reflink = CopyFlag(fsMod.constants.COPYFILE_FICLONE)
  val ReflinkOrFail = CopyFlag(fsMod.constants.COPYFILE_FICLONE_FORCE)

  private[file] implicit val monoid: Monoid[CopyFlag] = new Monoid[CopyFlag] {
    override def combine(x: CopyFlag, y: CopyFlag): CopyFlag = CopyFlag(x.bits | y.bits)
    override def empty: CopyFlag = CopyFlag(0)
  }
}

private[file] trait CopyFlagsCompanionPlatform {}
