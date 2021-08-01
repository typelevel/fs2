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

  def /(name: String): Path

  def /(path: Path): Path

  def resolve(name: String): Path

  def resolve(path: Path): Path

  def normalize: Path

  def isAbsolute: Boolean

  def absolute: Path

  def names: Seq[Path]

  def fileName: Path

  def parent: Option[Path]

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
