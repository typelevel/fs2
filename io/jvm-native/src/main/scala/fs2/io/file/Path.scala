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

import java.nio.file.{FileSystems, Path => JPath, Paths}

/** Path to a file or directory on a file system.
  *
  * The path API is inspired by the `java.nio.file.Path` API. On the JVM,
  * a NIO path can be converted to an FS2 path via `Path.fromNioPath(p)`
  * and likewise an FS2 path can be converted to a NIO path via `p.toNioPath`.
  *
  * (When using this class on Node.js, the `fromNioPath` and `toNioPath` methods are
  * not accessible.)
  *
  * Generally, most methods have the same behavior as their NIO counterparts, though
  * there are some slight differences -- e.g., `resolve` normalizes.
  */
final class Path private (val toNioPath: JPath) extends PathApi {

  def /(name: String): Path = Path(Paths.get(toString, name)).normalize
  def /(path: Path): Path = this / path.toString

  def resolve(name: String): Path = Path(toNioPath.resolve(name)).normalize
  def resolve(path: Path): Path = Path(toNioPath.resolve(path.toNioPath)).normalize

  def resolveSibling(name: String): Path = Path(toNioPath.resolveSibling(name))
  def resolveSibling(path: Path): Path = Path(toNioPath.resolveSibling(path.toNioPath))

  def relativize(path: Path): Path = Path(toNioPath.relativize(path.toNioPath))

  def normalize: Path = new Path(toNioPath.normalize())

  def isAbsolute: Boolean = toNioPath.isAbsolute()

  def absolute: Path = Path(toNioPath.toAbsolutePath())

  def names: Seq[Path] = List.tabulate(toNioPath.getNameCount())(i => Path(toNioPath.getName(i)))

  def fileName: Path = Path(toNioPath.getFileName())

  def extName: String = {
    val fn = fileName.toString
    val i = fn.lastIndexOf('.')
    if (i == 0 | i == -1) "" else fn.substring(i)
  }

  def parent: Option[Path] = Option(toNioPath.getParent()).map(Path(_))

  def startsWith(path: String): Boolean = toNioPath.startsWith(path)
  def startsWith(path: Path): Boolean = toNioPath.startsWith(path.toNioPath)

  def endsWith(path: String): Boolean = toNioPath.endsWith(path)
  def endsWith(path: Path): Boolean = toNioPath.endsWith(path.toNioPath)

  override def toString = toNioPath.toString

  override def equals(that: Any) = that match {
    case p: Path => toNioPath == p.toNioPath
    case _       => false
  }

  override def hashCode = toNioPath.hashCode
}

object Path extends PathCompanionApi {
  private def apply(path: JPath): Path = new Path(path)
  def apply(path: String): Path = fromNioPath(FileSystems.getDefault.getPath(path))
  def fromNioPath(path: JPath): Path = Path(path)
}
