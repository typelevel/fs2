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

/** Flags describing how a file should be opened by `Files[F].open(path, flags)`.
  *
  * Common flag combinations are available in the companion (e.g. `Flags.Write`) Custom combinations
  * are supported via the `apply` method (e.g., `Flags(Flag.Write, Flag.CreateNew)`).
  */
case class Flags(value: List[Flag]) {

  def contains(flag: Flag): Boolean = value.contains(flag)

  def addIfAbsent(flag: Flag): Flags =
    if (!contains(flag)) Flags(flag :: value) else this
}

object Flags extends FlagsCompanionPlatform {
  def apply(flags: Flag*): Flags = Flags(flags.toList)

  /** Flags used for opening a file for reading. */
  val Read = Flags(Flag.Read)

  /** Flags used for opening a file for writing, creating it if it doesn't exist, and truncating it.
    */
  val Write = Flags(Flag.Write, Flag.Create, Flag.Truncate)

  /** Flags used for opening a file for appending, creating it if it doesn't exist. */
  val Append = Flags(Flag.Append, Flag.Create, Flag.Write)
}
