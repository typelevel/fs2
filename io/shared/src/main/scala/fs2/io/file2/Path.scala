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
package file2

case class Path private (separator: Char, root: String, names: List[Path.Name]) {

  def /(name: String): Path =
    new Path(separator, root, names ++ Path.Name.fromString(name, separator))

  def normalize: Path = {
    def loop(remaining: List[Path.Name], acc: List[Path.Name]): Path =
      remaining match {
        case Nil => Path(separator, root, acc.reverse)
        case hd :: tl => 
          if (hd.value == ".") loop(tl, acc)
          else if (hd.value == "..") loop(tl, acc.tail)
          else loop(tl, hd :: acc)
      }
    loop(names, Nil)
  }

  override def toString = {
    root + names.map(_.value).mkString("", separator.toString, "")
  }
}

object Path extends PathCompanionPlatform {
  val PlatformFileSeparator: String = PlatformFileSeparatorChar.toString
  val PosixFileSeparatorChar: Char = '/'

  case class Name private (value: String)
  private object Name {
    def fromString(value: String, separator: Char): List[Name] =
      value.split(separator).filterNot(_.isEmpty).map(Name(_)).toList
  }

  def apply(first: String, more: String*): Path =
    if (PlatformFileSeparatorChar == PosixFileSeparatorChar) posix(first, more: _*)
    else win32(first, more: _*)

  def posix(first: String, more: String*): Path = {
    val absolute = first.nonEmpty && first.charAt(0) == PosixFileSeparatorChar
    val root = if (absolute) "/" else ""
    val names = (first :: more.toList).flatMap(Name.fromString(_, PosixFileSeparatorChar))
    Path(PosixFileSeparatorChar, root, names)
  }

  def win32(first: String, more: String*): Path =
    ???
    // TODO UNC paths (\\foo\bar), Drives (C:\foo\bar), absolutes (\foo\bar), drive-relatives (C:foo\bar)
}