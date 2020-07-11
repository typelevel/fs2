package fs2

import scala.concurrent.duration._

import cats.data.Ior
import cats.effect.IO
import cats.implicits._
import org.scalacheck.Prop.forAll

class StreamAlignSuite extends Fs2Suite {
  property("align") {
    forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      assert(s1.align(s2).toList == s1.toList.align(s2.toList))
    }
  }

  test("left side empty and right side populated") {
    val empty = Stream.empty
    val s = Stream("A", "B", "C")
    assert(
      empty.align(s).take(3).toList == List(Ior.Right("A"), Ior.Right("B"), Ior.Right("C"))
    )
  }

  test("right side empty and left side populated") {
    val empty = Stream.empty
    val s = Stream("A", "B", "C")
    assert(
      s.align(empty).take(3).toList == List(Ior.Left("A"), Ior.Left("B"), Ior.Left("C"))
    )
  }

  test("values in both sides") {
    val ones = Stream.constant("1")
    val s = Stream("A", "B", "C")
    assert(
      s.align(ones).take(4).toList == List(
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
    assert(
      s.align(nums).take(5).toList == List(
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
    assert(
      nums.align(s).take(5).toList == List(
        Ior.Both("1", "A"),
        Ior.Both("2", "B"),
        Ior.Both("3", "C"),
        Ior.Left("4"),
        Ior.Left("5")
      )
    )
  }
}
