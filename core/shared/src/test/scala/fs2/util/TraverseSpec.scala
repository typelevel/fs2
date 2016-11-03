package fs2
package util

class TraverseSpec extends Fs2Spec {

  "Traverse" - {
    "evaluate effects in left-to-right order" in {
      var acc = collection.mutable.ListBuffer[Int]()
      val result = Traverse[List].traverse((1 to 5).toList)(n => Task.delay(acc += n))
      result.unsafeRunSync()
      acc.toList shouldBe List(1, 2, 3, 4, 5)
    }
  }
}
