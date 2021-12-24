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

import cats.kernel.laws.discipline.HashTests
import cats.kernel.laws.discipline.MonoidTests
import cats.kernel.laws.discipline.OrderTests
import org.scalacheck.Arbitrary
import org.scalacheck.Cogen
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class PathSuite extends Fs2Suite {

  implicit val arbitraryPath: Arbitrary[Path] = Arbitrary(for {
    names <- Gen.listOf(Gen.alphaNumStr)
    root <- Gen.oneOf("/", "")
  } yield names.foldLeft(Path(root))((p, n) => p / Path(n)))

  implicit val cogenPath: Cogen[Path] =
    Cogen.cogenList[String].contramap(_.names.map(_.toString).toList)

  test("construction") {
    assertEquals(Path("foo/bar"), Path("foo") / "bar")
    assertEquals(Path("/foo/bar"), Path("/foo") / "bar")
  }

  test("normalize") {
    assertEquals(Path("foo/bar/baz").normalize, Path("foo/bar/baz"))
    assertEquals(Path("./foo/bar/baz").normalize, Path("foo/bar/baz"))
    assertEquals(Path("./foo/../bar/baz").normalize, Path("bar/baz"))
  }

  test("fileName") {
    assertEquals(Path("/foo/bar/baz/asdf/quux.html").fileName, Path("quux.html"))
  }

  test("parent") {
    assertEquals(Path("/foo/bar/baz/asdf/quux").parent, Some(Path("/foo/bar/baz/asdf")))
    assertEquals(Path("foo").parent, None)
  }

  test("isAbsolute") {
    assert(Path("/foo/bar").isAbsolute)
    assert(Path("/baz/..").isAbsolute)
    assert(!Path("qux/").isAbsolute)
    assert(!Path(".").isAbsolute)
  }

  test("/") {
    assertEquals(Path("/foo") / "bar" / "baz/asdf" / "quux" / "..", Path("/foo/bar/baz/asdf"))
  }

  test("relativize") {
    assertEquals(
      Path("/data/orandea/test/aaa").relativize(Path("/data/orandea/impl/bbb")),
      Path("../../impl/bbb")
    )
  }

  test("resolve") {
    assertEquals(Path("/foo/bar").resolve("./baz"), Path("/foo/bar/baz"))
    assert(
      Path("wwwroot")
        .resolve("static_files/png/")
        .resolve("../gif/image.gif")
        .endsWith(Path("wwwroot/static_files/gif/image.gif"))
    )
  }

  test("extName") {
    assertEquals(Path("index.html").extName, ".html")
    assertEquals(Path("index.coffee.md").extName, ".md")
    assertEquals(Path("index.").extName, ".")
    assertEquals(Path("index").extName, "")
    assertEquals(Path(".index").extName, "")
    assertEquals(Path(".index.md").extName, ".md")
  }

  test("startsWith/endsWith") {
    forAll { (start: Path, end: Path) =>
      if (start.toString.nonEmpty && end.toString.nonEmpty) {
        val path = start.resolve(end)
        // TODO
        // assert(path.startsWith(start), s"$path doesn't start with $start")
        assert(path.endsWith(end), s"$path doesn't end with $end")
      }
    }
  }

  checkAll("Monoid[Path]", MonoidTests[Path].monoid)
  checkAll("Order[Path]", OrderTests[Path].order)
  checkAll("Hash[Path]", HashTests[Path].hash)
}
