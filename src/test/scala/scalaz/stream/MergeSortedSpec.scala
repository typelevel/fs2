package scalaz.stream

import org.scalacheck.Arbitrary._
import org.scalacheck.{Gen, Arbitrary, Properties}
import org.scalacheck.Prop._
import scalaz.\&/
import scalaz.std.anyVal._
import scalaz.syntax.std.list._

class MergeSortedSpec extends Properties("mergeSorted") {

  case class Foo(i: Int, j: Int, s: String)
  case class Bar(i: Int, j: Int, s: String)

  implicit val arbitraryFoo = Arbitrary {
    for {
      (i, j, s) <- arbitrary[(Int, Int, String)]
    } yield Foo(i, j, s)
  }
  implicit val arbitraryBar = Arbitrary {
    for {
      (i, j, s) <- arbitrary[(Int, Int, String)]
    } yield Bar(i, j, s)
  }

  implicit val arbitraryFooInt: Arbitrary[Foo => Int] = Arbitrary { Gen.oneOf((_: Foo).i, (_: Foo).j) }
  implicit val arbitraryBarInt: Arbitrary[Bar => Int] = Arbitrary { Gen.oneOf((_: Bar).i, (_: Bar).j) }

  private def distinctOn[A, B](on: A => B): List[A] => List[A] = as => as.groupBy1(on).values.map(_.head).toList

  property("") = forAll { (unsortedFoos: List[Foo], unsortedBars: List[Bar], f: Foo => Int, g: Bar => Int) =>
      val foos = distinctOn(f)(unsortedFoos).sortBy(f)
      val bars = distinctOn(g)(unsortedBars).sortBy(g)

      val merged: Process0[Foo \&/ Bar] = merge.mergeSorted(Process.emitAll(foos), f)(Process.emitAll(bars), g)

      val result = merged.toSource.runLog.unsafePerformSync.toList

    ("Output stream contains all values from left source" |: result.collect(Function.unlift(_.a)) == foos)  &&
      ("Output stream contain all values from right source" |: result.collect(Function.unlift(_.b)) == bars)  &&
      ("Values from both side with the same key are matched" |: result.collect(Function.unlift(_.onlyBoth)).forall(t => f(t._1) == g(t._2))) &&
      ("Output stream is sorted" |: result.map(_.fold(f, g, (a, _) => f(a))) == (foos.map(f) ++ bars.map(g)).distinct.sorted)
  }
}
