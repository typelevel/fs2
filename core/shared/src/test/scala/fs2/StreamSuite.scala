package fs2

import cats.data.Chain
import cats.effect.SyncIO
import cats.implicits._
import org.scalacheck.Prop.forAll

class StreamSuite extends Fs2Suite {

  property("append consistent with list concat") {
    forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      assertEquals((s1 ++ s2).toList, s1.toList ++ s2.toList)
    }
  }

  test("construction via apply") {
    assertEquals(Stream(1, 2, 3).toList, List(1, 2, 3))
  }

  property(">> consistent with list flatMap") {
    forAll { (s: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      assertEquals((s >> s2).toList, s.flatMap(_ => s2).toList)
    }
  }

  property("chunk") {
    forAll((c: Chunk[Int]) => assertEquals(Stream.chunk(c).compile.to(Chunk), c))
  }

  test("eval") {
    assertEquals(Stream.eval(SyncIO(23)).compile.toList.unsafeRunSync, List(23))
  }

  test("evals") {
    assertEquals(Stream.evals(SyncIO(List(1, 2, 3))).compile.toList.unsafeRunSync, List(1, 2, 3))
    assertEquals(Stream.evals(SyncIO(Chain(4, 5, 6))).compile.toList.unsafeRunSync, List(4, 5, 6))
    assertEquals(Stream.evals(SyncIO(Option(42))).compile.toList.unsafeRunSync, List(42))
  }

  property("collect consistent with list collect") {
    forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      assertEquals(s.collect(pf).toList, s.toList.collect(pf))
    }
  }

  property("collectFirst consistent with list collectFirst") {
    forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      assertEquals(s.collectFirst(pf).toVector, s.collectFirst(pf).toVector)
    }
  }
}
