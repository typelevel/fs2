package fs2

import org.scalacheck.Prop.forAll

class StreamBasicsSuite extends Fs2Suite {
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
}