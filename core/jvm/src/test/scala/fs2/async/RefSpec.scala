package fs2
package async

import cats.effect.IO
import cats.implicits._

import TestUtil._

class RefSpec extends Fs2Spec with EventuallySupport {

  "Ref" - {

    "concurrent modifications" in {
      val finalValue = 10
      // Cannot use streams, parallelSequence or Promise since they are implemented with Ref
      val r = refOf[IO, Int](0).unsafeRunSync

      List
        .fill(finalValue) {
          fork(r.modify(_ + 1))
        }
        .sequence
        .unsafeRunSync

      eventually { r.get.unsafeRunSync shouldBe finalValue }
    }

    "successful access" in {
      val op = for {
        r <- refOf[IO, Int](0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        success <- setter(value + 1)
        result <- r.get
      } yield success && result == 1

      op.unsafeRunSync shouldBe true
    }

    "failed access if modified before access" in {
      val op = for {
        r <- refOf[IO, Int](0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        _ <- r.setSync(5)
        success <- setter(value + 1)
        result <- r.get
      } yield !success && result == 5

      op.unsafeRunSync shouldBe true
    }

    "failed access if used once" in {
      val op = for {
        r <- refOf[IO, Int](0)
        valueAndSetter <- r.access
        (value, setter) = valueAndSetter
        cond1 <- setter(value + 1)
        _ <- r.setSync(value)
        cond2 <- setter(value + 1)
        result <- r.get
      } yield cond1 && !cond2 && result == 0

      op.unsafeRunSync shouldBe true
    }

  }
}
