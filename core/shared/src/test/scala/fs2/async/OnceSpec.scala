package fs2
package async

import cats.effect.{IO, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._

import scala.concurrent.duration._
import org.scalatest.EitherValues

class OnceSpec extends AsyncFs2Spec with EitherValues {

  "async.once" - {

    "effect is not evaluated if the inner `F[A]` isn't bound" in {
      val t = for {
        ref <- Ref.of[IO, Int](42)
        act = ref.update(_ + 1)
        _ <- async.once(act)
        _ <- Timer[IO].sleep(100.millis)
        v <- ref.get
      } yield v
      t.unsafeToFuture.map(_ shouldBe 42)
    }

    "effect is evaluated once if the inner `F[A]` is bound twice" in {
      val tsk = for {
        ref <- Ref.of[IO, Int](42)
        act = ref.modify { s =>
          val ns = s + 1
          ns -> ns
        }
        memoized <- async.once(act)
        x <- memoized
        y <- memoized
        v <- ref.get
      } yield (x, y, v)
      tsk.unsafeToFuture.map(_ shouldBe ((43, 43, 43)))
    }

    "effect is evaluated once if the inner `F[A]` is bound twice (race)" in {
      val t = for {
        ref <- Ref.of[IO, Int](42)
        act = ref.modify { s =>
          val ns = s + 1
          ns -> ns
        }
        memoized <- async.once(act)
        _ <- memoized.start
        x <- memoized
        _ <- Timer[IO].sleep(100.millis)
        v <- ref.get
      } yield (x, v)
      t.unsafeToFuture.map(_ shouldBe ((43, 43)))
    }

    "once andThen flatten is identity" in {
      val n = 10
      val t = for {
        ref <- Ref.of[IO, Int](42)
        act1 = ref.modify { s =>
          val ns = s + 1
          ns -> ns
        }
        act2 = async.once(act1).flatten
        _ <- Stream.repeatEval(act1).take(n).compile.drain.start
        _ <- Stream.repeatEval(act2).take(n).compile.drain.start
        _ <- Timer[IO].sleep(200.millis)
        v <- ref.get
      } yield v
      t.unsafeToFuture.map(_ shouldBe (42 + 2 * n))
    }
  }
}
