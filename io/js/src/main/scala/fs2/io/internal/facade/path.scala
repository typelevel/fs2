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

package fs2.io.internal.facade

import org.typelevel.scalaccompat.annotation._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@nowarn212("cat=unused")
private[io] object path {

  @js.native
  @JSImport("path", "sep")
  def sep: String = js.native

  @js.native
  @JSImport("path", "join")
  def join(paths: String*): String = js.native

  @js.native
  @JSImport("path", "resolve")
  def resolve(paths: String*): String = js.native

  @js.native
  @JSImport("path", "relative")
  def relative(from: String, to: String): String = js.native

  @js.native
  @JSImport("path", "normalize")
  def normalize(path: String): String = js.native

  @js.native
  @JSImport("path", "isAbsolute")
  def isAbsolute(path: String): Boolean = js.native

  @js.native
  @JSImport("path", "basename")
  def basename(path: String): String = js.native

  @js.native
  @JSImport("path", "extname")
  def extname(path: String): String = js.native

  @js.native
  @JSImport("path", "parse")
  def parse(path: String): ParsedPath = js.native

  @js.native
  trait ParsedPath extends js.Object {
    def dir: String = js.native
    def root: String = js.native
    def base: String = js.native
    def name: String = js.native
    def ext: String = js.native
  }

  @js.native
  @JSImport("path", "format")
  def format(pathObject: ParsedPath): String = js.native
}
