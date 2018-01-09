package fs2
package async

import cats.effect.IO
import cats.implicits._

import scala.concurrent.duration._
import org.scalatest.EitherValues

import TestUtil._

class PromiseSpec extends AsyncFs2Spec with EitherValues {

  "Promise" - {
    "setSync" in {
      promise[IO, Int].flatMap { p =>
        p.complete(0) *> p.get
      }.unsafeToFuture.map { _ shouldBe 0 }
    }

    "setSync is only successful once" in {
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
        _ <- fork {
         modifyGate.get *> state.modify(_ * 2) *> readGate.complete(())
        }
        _ <- fork {
          state.setSync(1) *> modifyGate.complete(())
        }
        _ <- readGate.get
        res <- state.get
      } yield res

      op.unsafeToFuture.map(_ shouldBe 2)
    }

    "timedGet" in {
      mkScheduler.evalMap { scheduler =>
          for {
            p <- async.promise[IO,Int]
            first <- p.timedGet(100.millis, scheduler)
            _ <- p.complete(42)
            second <- p.timedGet(100.millis, scheduler)
          } yield List(first, second)
      }.compile.toVector.unsafeToFuture.map(_.flatten shouldBe Vector(None, Some(42)))
    }

    "cancellableGet - cancel before force" in {
      mkScheduler.evalMap { scheduler =>
        for {
          r <- async.refOf[IO,Option[Int]](None)
          p <- async.promise[IO,Int]
          t <- p.cancellableGet
          (force, cancel) = t
          _ <- cancel
          _ <- async.fork(force.flatMap(i => r.setSync(Some(i))))
          _ <- scheduler.effect.sleep[IO](100.millis)
          _ <- p.complete(42)
          _ <- scheduler.effect.sleep[IO](100.millis)
          result <- r.get
        } yield result
      }.compile.last.unsafeToFuture.map(_ shouldBe Some(None))
    }
  }
}
