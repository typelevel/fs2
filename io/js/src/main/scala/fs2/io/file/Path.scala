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
package io.file

import fs2.internal.jsdeps.node.pathMod
import fs2.internal.jsdeps.node.fsMod

final class Path(private val path: String) extends AnyVal {
  def basename: Path = Path(pathMod.basename(path))
  def basename(ext: String): Path = Path(pathMod.basename(path, ext))
  def dirname: Path = Path(pathMod.dirname(path))
  def extname: String = pathMod.extname(path)
  def isAbsolute: Boolean = pathMod.isAbsolute(path)
  def normalize: Path = Path(pathMod.normalize(path))
  def relativeTo(that: Path): Path = Path(pathMod.relative(this.path, that.path))

  def /(that: Path): Path = Path.join(this, that)

  override def toString: String = path

  private[file] def toPathLike: fsMod.PathLike = path.asInstanceOf[fsMod.PathLike]
}

object Path {
  def apply(path: String): Path = new Path(path)

  def join(paths: Path*): Path = Path(pathMod.join(paths.map(_.path): _*))
  def resolve(paths: Path*): Path = Path(pathMod.resolve(paths.map(_.path): _*))
}
