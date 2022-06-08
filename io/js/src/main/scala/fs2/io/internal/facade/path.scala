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

import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

package object path {

  @js.native
  @JSImport("path", "sep")
  private[io] def sep: String = js.native

  @js.native
  @JSImport("path", "join")
  @nowarn
  private[io] def join(paths: String*): String = js.native

  @js.native
  @JSImport("path", "resolve")
  @nowarn
  private[io] def resolve(paths: String*): String = js.native

  @js.native
  @JSImport("path", "relative")
  @nowarn
  private[io] def relative(from: String, to: String): String = js.native

  @js.native
  @JSImport("path", "normalize")
  @nowarn
  private[io] def normalize(path: String): String = js.native

  @js.native
  @JSImport("path", "isAbsolute")
  @nowarn
  private[io] def isAbsolute(path: String): Boolean = js.native

  @js.native
  @JSImport("path", "basename")
  @nowarn
  private[io] def basename(path: String): String = js.native

  @js.native
  @JSImport("path", "extname")
  @nowarn
  private[io] def extname(path: String): String = js.native

  @js.native
  @JSImport("path", "parse")
  @nowarn
  private[io] def parse(path: String): ParsedPath = js.native

}

package path {
  @js.native
  private[io] trait ParsedPath extends js.Object {
    def dir: String = js.native
    def root: String = js.native
    def base: String = js.native
    def name: String = js.native
    def ext: String = js.native
  }
}
