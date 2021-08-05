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

import fs2.internal.jsdeps.node.pathMod

import scala.annotation.tailrec

final case class Path private (override val toString: String) extends PathApi {

  def /(name: String): Path = Path(pathMod.join(toString, name))
  def /(path: Path): Path = this / path.toString

  def resolve(name: String): Path = resolve(Path(name))
  def resolve(path: Path): Path = if (path.isAbsolute) path else this / path

  def resolveSibling(name: String): Path = resolveSibling(Path(name))
  def resolveSibling(path: Path): Path = parent.fold(path)(_.resolve(path))

  def relativize(path: Path): Path = Path(pathMod.relative(toString, path.toString))

  def normalize: Path = new Path(pathMod.normalize(toString))

  def isAbsolute: Boolean = pathMod.isAbsolute(toString)

  def absolute: Path = Path(pathMod.resolve(toString))

  def names: Seq[Path] = {
    @tailrec
    def go(path: Path, acc: List[Path]): List[Path] =
      path.parent match {
        case None         => acc
        case Some(parent) => go(parent, path.fileName :: acc)
      }

    go(this, Nil)
  }

  def fileName: Path = Path(pathMod.basename(toString))

  def extName: String = pathMod.extname(toString)

  def parent: Option[Path] = {
    val parsed = pathMod.parse(toString)
    if (parsed.dir.isEmpty || parsed.base.isEmpty)
      None
    else
      Some(Path(parsed.dir))
  }

  def startsWith(path: String): Boolean = startsWith(Path(path))
  def startsWith(path: Path): Boolean =
    isAbsolute == path.isAbsolute && names.startsWith(path.names)

  def endsWith(path: String): Boolean = endsWith(Path(path))
  def endsWith(that: Path): Boolean = {
    val thisNames = this.names
    val thatNames = that.names
    (isAbsolute == that.isAbsolute || thisNames.size > thatNames.size) && thisNames.endsWith(
      thatNames
    )
  }

  override def equals(that: Any) = that match {
    case p: Path => toString == p.toString
    case _       => false
  }

  override def hashCode = toString.hashCode
}

object Path extends PathCompanionApi {
  private[file] val sep = pathMod.sep

  def apply(path: String): Path =
    if (path.endsWith(sep))
      new Path(path.dropRight(sep.length))
    else
      new Path(path)
}
