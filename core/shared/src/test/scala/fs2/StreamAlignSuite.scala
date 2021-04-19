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

import cats.data.Ior
import cats.syntax.all._
import org.scalacheck.Prop.forAll

class StreamAlignSuite extends Fs2Suite {
  property("align") {
    forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      assertEquals(s1.align(s2).toList, s1.toList.align(s2.toList))
    }
  }

  test("left side empty and right side populated") {
    val empty = Stream.empty
    val s = Stream("A", "B", "C")
    assertEquals(
      empty.align(s).take(3).toList,
      List(Ior.Right("A"), Ior.Right("B"), Ior.Right("C"))
    )
  }

  test("right side empty and left side populated") {
    val empty = Stream.empty
    val s = Stream("A", "B", "C")
    assertEquals(
      s.align(empty).take(3).toList,
      List(Ior.Left("A"), Ior.Left("B"), Ior.Left("C"))
    )
  }

  test("values in both sides") {
    val ones = Stream.constant("1")
    val s = Stream("A", "B", "C")
    assertEquals(
      s.align(ones).take(4).toList,
      List(
        Ior.Both("A", "1"),
        Ior.Both("B", "1"),
        Ior.Both("C", "1"),
        Ior.Right("1")
      )
    )
  }

  test("extra values in right") {
    val nums = Stream("1", "2", "3", "4", "5")
    val s = Stream("A", "B", "C")
    assertEquals(
      s.align(nums).take(5).toList,
      List(
        Ior.Both("A", "1"),
        Ior.Both("B", "2"),
        Ior.Both("C", "3"),
        Ior.Right("4"),
        Ior.Right("5")
      )
    )
  }

  test("extra values in left") {
    val nums = Stream("1", "2", "3", "4", "5")
    val s = Stream("A", "B", "C")
    assertEquals(
      nums.align(s).take(5).toList,
      List(
        Ior.Both("1", "A"),
        Ior.Both("2", "B"),
        Ior.Both("3", "C"),
        Ior.Left("4"),
        Ior.Left("5")
      )
    )
  }
}
