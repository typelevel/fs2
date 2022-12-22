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

package fs2.io.internal

import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._

private[io] object sysun {
  import Nat._
  type _108 = Digit3[_1, _0, _8]

  type sockaddr_un = CStruct2[
    sa_family_t,
    CArray[CChar, _108]
  ]

}

private[io] object sysunOps {
  import sysun._

  implicit final class sockaddr_unOps(val sockaddr_un: Ptr[sockaddr_un]) extends AnyVal {
    def sun_family: sa_family_t = sockaddr_un._1
    def sun_family_=(sun_family: sa_family_t): Unit = sockaddr_un._1 = sun_family
    def sun_path: CArray[CChar, _108] = sockaddr_un._2
    def sun_path_=(sun_path: CArray[CChar, _108]): Unit = sockaddr_un._2 = sun_path
  }

}
