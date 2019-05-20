package fs2
package concurrent

import cats.effect.IO
import cats.implicits._
import org.scalactic.anyvals.PosInt
import scala.concurrent.duration._

class QueueSpec extends Fs2Spec {
  "Queue" - {
    "unbounded producer/consumer" in {
      forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList
        val n = expected.size
        Stream
          .eval(Queue.unbounded[IO, Int])
          .flatMap { q =>
            q.dequeue
              .merge(s.evalMap(q.enqueue1).drain)
              .take(n)
          }
          .compile
          .toList
          .asserting(_ shouldBe expected)
      }
    }
    "circularBuffer" in {
      forAll { (s: Stream[Pure, Int], maxSize0: PosInt) =>
        val maxSize = maxSize0 % 20 + 1
        val expected = s.toList.takeRight(maxSize)
        Stream
          .eval(Queue.circularBuffer[IO, Option[Int]](maxSize + 1))
          .flatMap { q =>
            s.noneTerminate
              .evalMap(q.enqueue1)
              .drain ++ q.dequeue.unNoneTerminate
          }
          .compile
          .toList
          .asserting(_ shouldBe expected)
      }
    }
    "dequeueAvailable" in {
      forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList
        Stream
          .eval(Queue.unbounded[IO, Option[Int]])
          .flatMap { q =>
            s.noneTerminate
              .evalMap(q.enqueue1)
              .drain ++ q.dequeueChunk(Int.MaxValue).unNoneTerminate.chunks
          }
          .compile
          .toList
          .asserting { result =>
            result.size should be < 2
            result.flatMap(_.toList) shouldBe expected
          }
      }
    }
    "dequeueBatch unbounded" in {
      forAll { (s: Stream[Pure, Int], batchSize0: PosInt) =>
        val batchSize = batchSize0 % 20 + 1
        val expected = s.toList
        Stream
          .eval(Queue.unbounded[IO, Option[Int]])
          .flatMap { q =>
            s.noneTerminate.evalMap(q.enqueue1).drain ++ Stream
              .constant(batchSize)
              .through(q.dequeueBatch)
              .unNoneTerminate
          }
          .compile
          .toList
          .asserting(_ shouldBe expected)
      }
    }
    "dequeueBatch circularBuffer" in {
      forAll { (s: Stream[Pure, Int], maxSize0: PosInt, batchSize0: PosInt) =>
        val maxSize = maxSize0 % 20 + 1
        val batchSize = batchSize0 % 20 + 1
        val expected = s.toList.takeRight(maxSize)
        Stream
          .eval(Queue.circularBuffer[IO, Option[Int]](maxSize + 1))
          .flatMap { q =>
            s.noneTerminate.evalMap(q.enqueue1).drain ++ Stream
              .constant(batchSize)
              .through(q.dequeueBatch)
              .unNoneTerminate
          }
          .compile
          .toList
          .asserting(_ shouldBe expected)
      }
    }

    "dequeue releases subscriber on " - {
      "interrupt" in {
        Queue
          .unbounded[IO, Int]
          .flatMap { q =>
            q.dequeue.interruptAfter(1.second).compile.drain >>
              q.enqueue1(1) >>
              q.enqueue1(2) >>
              q.dequeue1
          }
          .asserting(_ shouldBe 1)
      }

      "cancel" in {
        Queue
          .unbounded[IO, Int]
          .flatMap { q =>
            q.dequeue1.timeout(1.second).attempt >>
              q.enqueue1(1) >>
              q.enqueue1(2) >>
              q.dequeue1
          }
          .asserting(_ shouldBe 1)
      }
    }

    "size signal is initialized to zero" in {
      Stream
        .eval(InspectableQueue.unbounded[IO, Int])
        .flatMap(_.size)
        .take(1)
        .compile
        .toList
        .asserting(_ shouldBe List(0))
    }

    "size stream is discrete" in {
      Stream
        .eval(InspectableQueue.unbounded[IO, Int])
        .flatMap { q =>
          def changes =
            (Stream.range(1, 6).through(q.enqueue) ++ q.dequeue)
              .zip(Stream.fixedRate[IO](200.millis))

          q.size.concurrently(changes)
        }
        .interruptWhen(Stream.sleep[IO](2.seconds).as(true))
        .compile
        .toList
        .asserting(_.size shouldBe <=(11)) // if the stream won't be discrete we will get much more size notifications
    }

    "peek1" in {
      Stream
        .eval(
          for {
            q <- InspectableQueue.unbounded[IO, Int]
            f <- q.peek1.start
            g <- q.peek1.start
            _ <- q.enqueue1(42)
            x <- f.join
            y <- g.join
          } yield List(x, y)
        )
        .compile
        .toList
        .asserting(_.flatten shouldBe List(42, 42))
    }

    "peek1 with dequeue1" in {
      Stream
        .eval(
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
        )
        .compile
        .toList
        .asserting(_.flatten shouldBe List((42, 42), (43, 43), (44, 44)))
    }

    "peek1 bounded queue" in {
      Stream
        .eval(
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
        )
        .compile
        .toList
        .asserting(_.flatten shouldBe List(false, 42, 42, 42))
    }

    "peek1 circular buffer" in {
      Stream
        .eval(
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
        )
        .compile
        .toList
        .asserting(_.flatten shouldBe List(true, 42, 42, 43))
    }
  }
}
