package fs2
package concurrent

import cats.effect._
import cats.syntax.all._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import TestUtil._

class QueueSpec extends Fs2Spec {
  "Queue" - {
    "unbounded producer/consumer" in {
      forAll { (s: PureStream[Int]) =>
        withClue(s.tag) {
          runLog(Stream.eval(Queue.unbounded[IO, Int]).flatMap { q =>
            q.dequeue
              .merge(s.get.evalMap(q.enqueue1).drain)
              .take(s.get.toVector.size)
          }) shouldBe s.get.toVector
        }
      }
    }
    "timedDequeue1 returns some value" in {
      runLog(
        Stream.eval(
          for {
            q <- Queue.unbounded[IO, Int]
            t = IO.timer(global)
            _ <- (t.sleep(500.millisecond) *> q.enqueue1(5)).start
            e <- q.timedDequeue1(1.second, t)
          } yield e
        )
      ).head shouldBe Some(5)
    }
    "timedDequeue1 returns none" in {
      runLog(
        Stream.eval(
          for {
            q <- Queue.unbounded[IO, Int]
            t = IO.timer(global)
            _ <- (t.sleep(1500.millisecond) *> q.enqueue1(5)).start
            e <- q.timedDequeue1(1.second, t)
          } yield e
        )
      ).head shouldBe None
    }
    "timedEnqueue1 returns true" in {
      runLog(
        Stream.eval(
          for {
            q <- Queue.bounded[IO, Int](1)
            t = IO.timer(global)
            _ <- q.enqueue1(1)
            f1 <- (t.sleep(500.millisecond) *> q.dequeue1).start
            result <- q.timedEnqueue1(5, 1.second, t)
            e1 <- f1.join
            e2 <- q.dequeue1
          } yield (result, e1, e2)
        )
      ) shouldBe Vector[(Boolean, Int, Int)]((true, 1, 5))
    }
    "timedEnqueue1 returns false" in {
      runLog(
        Stream.eval(
          for {
            q <- InspectableQueue.bounded[IO, Int](1)
            t = IO.timer(global)
            _ <- q.enqueue1(1)
            _ <- (t.sleep(500.millisecond) *> q.dequeue1).start
            result1 <- q.timedEnqueue1(5, 200.millisecond, t)
            result2 <- q.timedEnqueue1(5, 200.millisecond, t)
            _ <- t.sleep(200.millisecond)
            size <- q.size.get
          } yield (result1, result2, size)
        )
      ) shouldBe Vector[(Boolean, Boolean, Int)]((false, false, 0))
    }
    "synchronous timedEnqueue1 and timedDequeue1 successful case" in {
      runLog(
        Stream.eval(
          for {
            q <- Queue.synchronous[IO, Int]
            t = IO.timer(global)
            f1 <- (t.sleep(500.millisecond) *> q.timedDequeue1(1.second, t)).start
            _ <- q.timedEnqueue1(1, 1.second, t)
            res <- f1.join
          } yield res
        )
      ).head shouldBe Some(1)
    }
    "synchronous timedEnqueue1 and timedDequeue1 failing case" in {
      runLog(
        Stream.eval(
          for {
            q <- Queue.synchronous[IO, Int]
            t = IO.timer(global)
            f1 <- (t.sleep(1.second) *> q.timedDequeue1(1.second, t)).start
            _ <- q.timedEnqueue1(1, 500.millisecond, t)
            res <- f1.join
          } yield res
        )
      ).head shouldBe None
    }
    "circularBuffer" in {
      forAll { (s: PureStream[Int], maxSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(
            Stream
              .eval(Queue.circularBuffer[IO, Option[Int]](maxSize.get + 1))
              .flatMap { q =>
                s.get.noneTerminate
                  .evalMap(q.enqueue1)
                  .drain ++ q.dequeue.unNoneTerminate
              }) shouldBe s.get.toVector.takeRight(maxSize.get)
        }
      }
    }
    "dequeueAvailable" in {
      forAll { (s: PureStream[Int]) =>
        withClue(s.tag) {
          val result =
            runLog(Stream.eval(Queue.unbounded[IO, Option[Int]]).flatMap { q =>
              s.get.noneTerminate
                .evalMap(q.enqueue1)
                .drain ++ q.dequeueAvailable.unNoneTerminate.chunks
            })
          result.size should be < 2
          result.flatMap(_.toVector) shouldBe s.get.toVector
        }
      }
    }
    "dequeueBatch unbounded" in {
      forAll { (s: PureStream[Int], batchSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(Stream.eval(Queue.unbounded[IO, Option[Int]]).flatMap { q =>
            s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream
              .constant(batchSize.get)
              .through(q.dequeueBatch)
              .unNoneTerminate
          }) shouldBe s.get.toVector
        }
      }
    }
    "dequeueBatch circularBuffer" in {
      forAll { (s: PureStream[Int], maxSize: SmallPositive, batchSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(
            Stream
              .eval(Queue.circularBuffer[IO, Option[Int]](maxSize.get + 1))
              .flatMap { q =>
                s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream
                  .constant(batchSize.get)
                  .through(q.dequeueBatch)
                  .unNoneTerminate
              }) shouldBe s.get.toVector.takeRight(maxSize.get)
        }
      }
    }
    "size signal is initialized to zero" in {
      runLog(
        Stream
          .eval(InspectableQueue.unbounded[IO, Int])
          .flatMap(_.size.discrete)
          .take(1)) shouldBe Vector(0)
    }
    "peek1" in {
      runLog(
        Stream.eval(
          for {
            q <- InspectableQueue.unbounded[IO, Int]
            f <- q.peek1.start
            g <- q.peek1.start
            _ <- q.enqueue1(42)
            x <- f.join
            y <- g.join
          } yield List(x, y)
        )).flatten shouldBe Vector(42, 42)
    }
    "peek1 with dequeue1" in {
      runLog(
        Stream.eval(
          for {
            q <- InspectableQueue.unbounded[IO, Int]
            f <- q.peek1.product(q.dequeue1).start
            _ <- q.enqueue1(42)
            x <- f.join
            g <- q.peek1.product(q.dequeue1).product(q.peek1.product(q.dequeue1)).start
            _ <- q.enqueue1(43)
            _ <- q.enqueue1(44)
            yz <- g.join
            (y, z) = yz
          } yield List(x, y, z)
        )).flatten shouldBe Vector((42, 42), (43, 43), (44, 44))
    }
    "peek1 bounded queue" in {
      runLog(
        Stream.eval(
          for {
            q <- InspectableQueue.bounded[IO, Int](maxSize = 1)
            f <- q.peek1.start
            g <- q.peek1.start
            _ <- q.enqueue1(42)
            b <- q.offer1(43)
            x <- f.join
            y <- g.join
            z <- q.dequeue1
          } yield List(b, x, y, z)
        )).flatten shouldBe Vector(false, 42, 42, 42)
    }
    "peek1 circular buffer" in {
      runLog(
        Stream.eval(
          for {
            q <- InspectableQueue.circularBuffer[IO, Int](maxSize = 1)
            f <- q.peek1.start
            g <- q.peek1.start
            _ <- q.enqueue1(42)
            x <- f.join
            y <- g.join
            b <- q.offer1(43)
            z <- q.peek1
          } yield List(b, x, y, z)
        )).flatten shouldBe Vector(true, 42, 42, 43)
    }
  }
}
