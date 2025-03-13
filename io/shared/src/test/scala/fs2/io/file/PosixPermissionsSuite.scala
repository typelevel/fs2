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

class PosixPermissionsSuite extends Fs2Suite {
  test("construction") {
    val cases = Seq(
      "777" -> "rwxrwxrwx",
      "775" -> "rwxrwxr-x",
      "770" -> "rwxrwx---",
      "510" -> "r-x--x---",
      "640" -> "rw-r-----"
    )
    cases.foreach { case (octal, str) =>
      assertEquals(PosixPermissions.fromOctal(octal), PosixPermissions.fromString(str))
      assertEquals(PosixPermissions.fromOctal(octal).fold("")(_.toString), str)
      assertEquals(
        PosixPermissions.fromOctal(octal).map(_.value),
        PosixPermissions.fromString(str).map(_.value)
      )
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

  test("add/remove all") {
    case class TC(p: PosixPermission, adding: String, removing: String)
    val all = Seq(
      TC(PosixPermission.OwnerRead, "400", "377"),
      TC(PosixPermission.OwnerWrite, "600", "177"),
      TC(PosixPermission.OwnerExecute, "700", "077"),
      TC(PosixPermission.GroupRead, "740", "037"),
      TC(PosixPermission.GroupWrite, "760", "017"),
      TC(PosixPermission.GroupExecute, "770", "007"),
      TC(PosixPermission.OthersRead, "774", "003"),
      TC(PosixPermission.OthersWrite, "776", "001"),
      TC(PosixPermission.OthersExecute, "777", "000")
    )
    var goingUp = PosixPermissions.fromInt(0).get
    var goingDown = PosixPermissions.fromInt(511).get
    all.foreach { case TC(p, adding, removing) =>
      goingUp = goingUp.add(p)
      assertEquals(goingUp, PosixPermissions.fromOctal(adding).get)

      goingDown = goingDown.remove(p)
      assertEquals(goingDown, PosixPermissions.fromOctal(removing).get)
    }
  }
}
