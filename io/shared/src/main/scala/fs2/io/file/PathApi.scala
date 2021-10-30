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

import cats.Show
import cats.kernel.Hash
import cats.kernel.Monoid
import cats.kernel.Order

private[file] trait PathApi {

  /** Joins the given path segments together using the platform-specific separator as a delimiter,
    * then normalizes the resulting path.
    */
  def /(name: String): Path

  /** Joins the given path segments together using the platform-specific separator as a delimiter,
    * then normalizes the resulting path.
    */
  def /(path: Path): Path

  /** Resolve the given path against this path. */
  def resolve(name: String): Path

  /** Resolve the given path against this path. */
  def resolve(path: Path): Path

  /** Resolves the given path against this path's parent path. */
  def resolveSibling(name: String): Path

  /** Resolves the given path against this path's parent path. */
  def resolveSibling(path: Path): Path

  /** Constructs a relative path between this path and a given path. */
  def relativize(path: Path): Path

  /** Returns a path that is this path with redundant name elements eliminated. */
  def normalize: Path

  /** Tells whether or not this path is absolute. */
  def isAbsolute: Boolean

  /** Returns a Path object representing the absolute path of this path. */
  def absolute: Path

  /** Returns the name elements in the path. */
  def names: Seq[Path]

  /** Returns the name of the file or directory denoted by this path as a Path object. The file name
    * is the farthest element from the root in the directory hierarchy.
    */
  def fileName: Path

  /** Returns the extension of the path, from the last occurrence of the . (period) character to end
    * of string in the last portion of the path. If there is no . in the last portion of the path,
    * or if there are no . characters other than the first character of the filename of path, an
    * empty string is returned.
    */
  def extName: String

  /** Returns the parent path, or None if this path does not have a parent. */
  def parent: Option[Path]

  /** Tests if this path starts with the given path. This path starts with the given path if this
    * path's root component starts with the root component of the given path, and this path starts
    * with the same name elements as the given path. If the given path has more name elements than
    * this path then false is returned.
    */
  def startsWith(path: String): Boolean

  /** Tests if this path starts with the given path. This path starts with the given path if this
    * path's root component starts with the root component of the given path, and this path starts
    * with the same name elements as the given path. If the given path has more name elements than
    * this path then false is returned.
    */
  def startsWith(path: Path): Boolean

  /** Tests if this path ends with the given path. If the given path has N elements, and no root
    * component, and this path has N or more elements, then this path ends with the given path if
    * the last N elements of each path, starting at the element farthest from the root, are equal.
    */
  def endsWith(path: String): Boolean

  /** Tests if this path ends with the given path. If the given path has N elements, and no root
    * component, and this path has N or more elements, then this path ends with the given path if
    * the last N elements of each path, starting at the element farthest from the root, are equal.
    */
  def endsWith(path: Path): Boolean

  def toString: String
}

private[file] trait PathCompanionApi {
  def apply(path: String): Path

  implicit def instances: Monoid[Path] with Order[Path] with Hash[Path] with Show[Path] = algebra

  private object algebra extends Monoid[Path] with Order[Path] with Hash[Path] with Show[Path] {
    val empty: Path = Path("")
    def combine(x: Path, y: Path): Path = x / y
    def compare(x: Path, y: Path): Int = x.toString.compare(y.toString)
    def hash(x: Path): Int = x.hashCode()
    def show(t: Path): String = t.toString
  }
}
