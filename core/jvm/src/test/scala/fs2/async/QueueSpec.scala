package fs2
package async

import scala.concurrent.duration._
import cats.effect.IO
import cats.implicits._

import TestUtil._

class QueueSpec extends Fs2Spec {
  "Queue" - {
    "unbounded producer/consumer" in {
      forAll { (s: PureStream[Int]) =>
        withClue(s.tag) {
          runLog(Stream.eval(async.unboundedQueue[IO, Int]).flatMap { q =>
            q.dequeue
              .merge(s.get.evalMap(q.enqueue1).drain)
              .take(s.get.toVector.size)
          }) shouldBe s.get.toVector
        }
      }
    }
    "circularBuffer" in {
      forAll { (s: PureStream[Int], maxSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(
            Stream
              .eval(async.circularBuffer[IO, Option[Int]](maxSize.get + 1))
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
            runLog(Stream.eval(async.unboundedQueue[IO, Option[Int]]).flatMap { q =>
              s.get.noneTerminate
                .evalMap(q.enqueue1)
                .drain ++ q.dequeueAvailable.unNoneTerminate.segments
            })
          result.size should be < 2
          result.flatMap(_.force.toVector) shouldBe s.get.toVector
        }
      }
    }
    "dequeueBatch unbounded" in {
      forAll { (s: PureStream[Int], batchSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(Stream.eval(async.unboundedQueue[IO, Option[Int]]).flatMap { q =>
            s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream
              .constant(batchSize.get)
              .covary[IO]
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
              .eval(async.circularBuffer[IO, Option[Int]](maxSize.get + 1))
              .flatMap { q =>
                s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream
                  .constant(batchSize.get)
                  .covary[IO]
                  .through(q.dequeueBatch)
                  .unNoneTerminate
              }) shouldBe s.get.toVector.takeRight(maxSize.get)
        }
      }
    }
    "timedEnqueue1 on bounded queue" in {
      runLog(Scheduler[IO](1).flatMap { scheduler =>
        Stream.eval(
          for {
            q <- async.boundedQueue[IO, Int](1)
            _ <- q.enqueue1(0)
            first <- q.timedEnqueue1(1, 100.millis, scheduler)
            second <- q.dequeue1
            third <- q.timedEnqueue1(2, 100.millis, scheduler)
            fourth <- q.dequeue1
          } yield List(first, second, third, fourth)
        )
      }).flatten shouldBe Vector(false, 0, true, 2)
    }
    "timedDequeue1" in {
      runLog(Scheduler[IO](1).flatMap { scheduler =>
        Stream.eval(
          for {
            q <- async.unboundedQueue[IO, Int]
            _ <- q.enqueue1(0)
            first <- q.timedDequeue1(100.millis, scheduler)
            second <- q.timedDequeue1(100.millis, scheduler)
            _ <- q.enqueue1(1)
            third <- q.timedDequeue1(100.millis, scheduler)
          } yield List(first, second, third)
        )
      }).flatten shouldBe Vector(Some(0), None, Some(1))
    }
    "size signal is initialized to zero" in {
      runLog(
        Stream
          .eval(async.unboundedQueue[IO, Int])
          .flatMap(_.size.discrete)
          .take(1)) shouldBe Vector(0)
    }
    "peek1" in {
      runLog(
        Stream.eval(
          for {
            q <- async.unboundedQueue[IO, Int]
            f <- async.start(q.peek1)
            g <- async.start(q.peek1)
            _ <- q.enqueue1(42)
            x <- f
            y <- g
          } yield List(x, y)
        )).flatten shouldBe Vector(42, 42)
    }
    "peek1 with dequeue1" in {
      runLog(
        Stream.eval(
          for {
            q <- async.unboundedQueue[IO, Int]
            f <- async.start(q.peek1.product(q.dequeue1))
            _ <- q.enqueue1(42)
            x <- f
            g <- async.start((q.peek1.product(q.dequeue1)).product(q.peek1.product(q.dequeue1)))
            _ <- q.enqueue1(43)
            _ <- q.enqueue1(44)
            yz <- g
            (y, z) = yz
          } yield List(x, y, z)
        )).flatten shouldBe Vector((42, 42), (43, 43), (44, 44))
    }
    "timedPeek1" in {
      runLog(Scheduler[IO](1).flatMap { scheduler =>
        Stream.eval(
          for {
            q <- async.unboundedQueue[IO, Int]
            x <- q.timedPeek1(100.millis, scheduler)
            _ <- q.enqueue1(42)
            y <- q.timedPeek1(100.millis, scheduler)
            _ <- q.enqueue1(43)
            z <- q.timedPeek1(100.millis, scheduler)
          } yield List(x, y, z)
        )
      }).flatten shouldBe Vector(None, Some(42), Some(42))
    }
    "peek1 bounded queue" in {
      runLog(
        Stream.eval(
          for {
            q <- async.boundedQueue[IO, Int](maxSize = 1)
            f <- async.start(q.peek1)
            g <- async.start(q.peek1)
            _ <- q.enqueue1(42)
            b <- q.offer1(43)
            x <- f
            y <- g
            z <- q.dequeue1
          } yield List(b, x, y, z)
        )).flatten shouldBe Vector(false, 42, 42, 42)
    }
    "peek1 circular buffer" in {
      runLog(
        Stream.eval(
          for {
            q <- async.circularBuffer[IO, Int](maxSize = 1)
            f <- async.start(q.peek1)
            g <- async.start(q.peek1)
            _ <- q.enqueue1(42)
            x <- f
            y <- g
            b <- q.offer1(43)
            z <- q.peek1
          } yield List(b, x, y, z)
        )).flatten shouldBe Vector(true, 42, 42, 43)
    }
    "peek1 synchronous queue" in {
      runLog(
        Stream.eval(
          for {
            q <- async.synchronousQueue[IO, Int]
            f <- async.start(q.peek1)
            g <- async.start(q.peek1)
            _ <- async.start(q.enqueue1(42))
            x <- q.dequeue1
            y <- f
            z <- g
          } yield List(x, y, z)
        )).flatten shouldBe Vector(42, 42, 42)
    }
    "timedPeek1 synchronous queue" in {
      runLog(Scheduler[IO](1).flatMap { scheduler =>
        Stream.eval(
          for {
            q <- async.synchronousQueue[IO, Int]
            none1 <- q.timedPeek1(100.millis, scheduler)
            _ <- async.start(q.enqueue1(42))
            x <- q.dequeue1
            none2 <- q.timedPeek1(100.millis, scheduler)
          } yield List(none1, x, none2)
        )
      }).flatten shouldBe Vector(None, 42, None)
    }
    "peek1 synchronous None-terminated queue" in {
      runLog(
        Stream.eval(
          for {
            q <- async.mutable.Queue.synchronousNoneTerminated[IO, Int]
            f <- async.start(q.peek1)
            g <- async.start(q.peek1)
            _ <- q.enqueue1(None)
            y <- f
            z <- g
            x <- q.dequeue1
          } yield List(x, y, z)
        )).flatten shouldBe Vector(None, None, None)
    }
  }
}
