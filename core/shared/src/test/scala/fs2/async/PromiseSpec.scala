package fs2
package async

import cats.effect.{Concurrent, IO, Timer}
import cats.implicits._

import scala.concurrent.duration._
import org.scalatest.EitherValues

class PromiseSpec extends AsyncFs2Spec with EitherValues {

  "Promise" - {
    "complete" in {
      promise[IO, Int].flatMap { p =>
        p.complete(0) *> p.get
      }.unsafeToFuture.map { _ shouldBe 0 }
    }

    "complete is only successful once" in {
      promise[IO, Int].flatMap { p =>
        p.complete(0) *> p.complete(1).attempt product p.get
      }.unsafeToFuture.map { case (err, value) =>
          err.left.value shouldBe a[Promise.AlreadyCompletedException]
          value shouldBe 0
      }
    }

    "get blocks until set" in {
      val op = for {
        state <- refOf[IO, Int](0)
        modifyGate <- promise[IO, Unit]
        readGate <- promise[IO, Unit]
        _ <- shiftStart {
         modifyGate.get *> state.modify(_ * 2) *> readGate.complete(())
        }
        _ <- shiftStart {
          state.setSync(1) *> modifyGate.complete(())
        }
        _ <- readGate.get
        res <- state.get
      } yield res

      op.unsafeToFuture.map(_ shouldBe 2)
    }

    "get - cancel before force" in {
      val t = for {
        r <- async.refOf[IO,Option[Int]](None)
          p <- async.promise[IO,Int]
          fiber <- Concurrent[IO].start(p.get)
          _ <- fiber.cancel
          _ <- async.shiftStart(fiber.join.flatMap(i => r.setSync(Some(i))))
          _ <- Timer[IO].sleep(100.millis)
          _ <- p.complete(42)
          _ <- Timer[IO].sleep(100.millis)
          result <- r.get
        } yield result
      t.unsafeToFuture.map(_ shouldBe None)
    }
  }

  "async.once" - {

    "effect is not evaluated if the inner `F[A]` isn't bound" in {
      val t = for {
        ref <- async.refOf[IO, Int](42)
        act = ref.modify(_ + 1)
        _ <- async.once(act)
        _ <- Timer[IO].sleep(100.millis)
        v <- ref.get
      } yield v
      t.unsafeToFuture.map(_ shouldBe 42)
    }

    "effect is evaluated once if the inner `F[A]` is bound twice" in {
      val tsk = for {
        ref <- async.refOf[IO, Int](42)
        act = ref.modify(_ + 1).map(_.now)
        memoized <- async.once(act)
        x <- memoized
        y <- memoized
        v <- ref.get
      } yield (x, y, v)
      tsk.unsafeToFuture.map(_ shouldBe ((43, 43, 43)))
    }

    "effect is evaluated once if the inner `F[A]` is bound twice (race)" in {
      val t = for {
        ref <- async.refOf[IO, Int](42)
        act = ref.modify(_ + 1).map(_.now)
        memoized <- async.once(act)
        _ <- async.shiftStart(memoized)
        x <- memoized
        _ <- Timer[IO].sleep(100.millis)
        v <- ref.get
      } yield (x, v)
      t.unsafeToFuture.map(_ shouldBe ((43, 43)))
    }

    "once andThen flatten is identity" in {
      val n = 10
      val t = for {
        ref <- async.refOf[IO, Int](42)
        act1 = ref.modify(_ + 1).map(_.now)
        act2 = async.once(act1).flatten
        _ <- async.shiftStart(Stream.repeatEval(act1).take(n).compile.drain)
        _ <- async.shiftStart(Stream.repeatEval(act2).take(n).compile.drain)
        _ <- Timer[IO].sleep(200.millis)
        v <- ref.get
      } yield v
      t.unsafeToFuture.map(_ shouldBe (42 + 2 * n))
    }
  }
}
