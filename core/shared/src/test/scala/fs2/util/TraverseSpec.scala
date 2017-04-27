package fs2
package util

import cats.Traverse
import cats.effect.IO
import cats.implicits._

class TraverseSpec extends Fs2Spec {

  "Traverse" - {
    "evaluate effects in left-to-right order (List)" in {
      var acc = collection.mutable.ListBuffer[Int]()
      val result = Traverse[List].traverse((1 to 5).toList)(n => IO(acc += n))
      result.unsafeRunSync()
      acc.toList shouldBe List(1, 2, 3, 4, 5)
    }

    "evaluate effects in left-to-right order (Seq)" in {
      var acc = collection.mutable.ListBuffer[Int]()
      val result = Traverse[Vector].traverse((1 to 5).toVector)(n => IO(acc += n))
      result.unsafeRunSync()
      acc shouldBe Seq(1, 2, 3, 4, 5)
    }

    "evaluate effects in left-to-right order (Vector)" in {
      var acc = collection.mutable.ListBuffer[Int]()
      val result = Traverse[Vector].traverse((1 to 5).toVector)(n => IO(acc += n))
      result.unsafeRunSync()
      acc.toVector shouldBe Vector(1, 2, 3, 4, 5)
    }
  }
}
