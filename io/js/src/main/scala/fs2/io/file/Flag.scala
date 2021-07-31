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

import fs2.internal.jsdeps.node.fsMod

final class Flag private (private[file] val bits: Long) extends AnyVal

object Flag extends FlagCompanionApi {
  private def apply(bits: Long): Flag = new Flag(bits)
  private def apply(bits: Double): Flag = Flag(bits.toLong)

  val Read = Flag(fsMod.constants.O_RDONLY)
  val Write = Flag(fsMod.constants.O_WRONLY)
  val Append = Flag(fsMod.constants.O_APPEND)

  val Truncate = Flag(fsMod.constants.O_TRUNC)
  val Create = Flag(fsMod.constants.O_CREAT)
  val CreateNew = Flag(fsMod.constants.O_CREAT.toLong | fsMod.constants.O_EXCL.toLong)

  val Sync = Flag(fsMod.constants.O_SYNC)
  val Dsync = Flag(fsMod.constants.O_DSYNC)
}

private[file] trait FlagsCompanionPlatform {}
