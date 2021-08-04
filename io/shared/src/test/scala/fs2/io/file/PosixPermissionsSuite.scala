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

import scala.collection.immutable.SeqMap

class PosixPermissionsSuite extends Fs2Suite {
  test("construction") {
    val cases = SeqMap(
      "777" -> "rwxrwxrwx",
      "775" -> "rwxrwxr-x",
      "770" -> "rwxrwx---",
      "510" -> "r-x--x---",
      "640" -> "rw-r-----",
    )
    cases.foreach { case (octal, str) =>
      assertEquals(PosixPermissions.fromOctal(octal), PosixPermissions.fromString(str))
      assertEquals(PosixPermissions.fromOctal(octal).getOrElse("").toString, str)
      assertEquals(PosixPermissions.fromOctal(octal).map(_.value), PosixPermissions.fromString(str).map(_.value))
    }
  }

  test("fromOctal validates") {
    assertEquals(PosixPermissions.fromOctal("1000"), None)
    assertEquals(PosixPermissions.fromOctal("-1"), None)
    assertEquals(PosixPermissions.fromOctal("asdf"), None)
  }

  test("fromInt validates") {
    assertEquals(PosixPermissions.fromInt(511), PosixPermissions.fromOctal("777"))
    assertEquals(PosixPermissions.fromInt(512), None)
    assertEquals(PosixPermissions.fromInt(-1), None)
  }

  test("fromString validates") {
    assertEquals(PosixPermissions.fromString(""), None)
    assertEquals(PosixPermissions.fromString("rwx"), None)
    assertEquals(PosixPermissions.fromString("rwxrwxrw?"), None)
  }
}