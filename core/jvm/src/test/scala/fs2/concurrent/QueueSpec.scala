package fs2
package concurrent

import cats.effect.IO
import cats.implicits._
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
                .drain ++ q.dequeueChunk(Int.MaxValue).unNoneTerminate.chunks
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
          .unsafeRunSync shouldBe 1

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
          .unsafeRunSync shouldBe 1

      }
    }

    "size signal is initialized to zero" in {
      runLog(
        Stream
          .eval(InspectableQueue.unbounded[IO, Int])
          .flatMap(_.size)
          .take(1)) shouldBe Vector(0)
    }

    "size stream is discrete" in {
      def p =
        Stream
          .eval(InspectableQueue.unbounded[IO, Int])
          .flatMap { q =>
            def changes =
              (Stream.range(1, 6).through(q.enqueue) ++ q.dequeue)
                .zip(Stream.fixedRate[IO](200.millis))

            q.size.concurrently(changes)
          }
          .interruptWhen(Stream.sleep[IO](2.seconds).as(true))

      p.compile.toList.unsafeRunSync.size shouldBe <=(11) // if the stream won't be discrete we will get much more size notifications
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
