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

package fs2.io.file

import java.nio.file.{CopyOption, StandardCopyOption}

final class CopyFlag private (private[file] val option: CopyOption) extends AnyVal

object CopyFlag extends CopyFlagCompanionApi {
  def fromCopyOption(option: CopyOption): CopyFlag = new CopyFlag(option)

  val AtomicMove = fromCopyOption(StandardCopyOption.ATOMIC_MOVE)
  val CopyAttributes = fromCopyOption(StandardCopyOption.COPY_ATTRIBUTES)
  val ReplaceExisting = fromCopyOption(StandardCopyOption.REPLACE_EXISTING)
}

private[file] trait CopyFlagsCompanionPlatform {
  def fromCopyOptions(options: Iterable[CopyOption]): CopyFlags =
    CopyFlags(options.map(CopyFlag.fromCopyOption(_)).toList)
}
