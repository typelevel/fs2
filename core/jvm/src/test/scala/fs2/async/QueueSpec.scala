package fs2
package async

import scala.concurrent.duration._
import cats.effect.IO

class QueueSpec extends Fs2Spec {
  "Queue" - {
    "unbounded producer/consumer" in {
      forAll { (s: PureStream[Int]) =>
        withClue(s.tag) {
          runLog(Stream.eval(async.unboundedQueue[IO,Int]).flatMap { q =>
            q.dequeue.merge(s.get.evalMap(q.enqueue1).drain).take(s.get.toVector.size)
          }) shouldBe s.get.toVector
        }
      }
    }
    "circularBuffer" in {
      forAll { (s: PureStream[Int], maxSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(Stream.eval(async.circularBuffer[IO,Option[Int]](maxSize.get + 1)).flatMap { q =>
            s.get.noneTerminate.evalMap(q.enqueue1).drain ++ q.dequeue.unNoneTerminate
          }) shouldBe s.get.toVector.takeRight(maxSize.get)
        }
      }
    }
    "dequeueAvailable" in {
      forAll { (s: PureStream[Int]) =>
        withClue(s.tag) {
          val result = runLog(Stream.eval(async.unboundedQueue[IO,Option[Int]]).flatMap { q =>
            s.get.noneTerminate.evalMap(q.enqueue1).drain ++ q.dequeueAvailable.unNoneTerminate.segments
          })
          result.size should be < 2
          result.flatMap(_.toVector) shouldBe s.get.toVector
        }
      }
    }
    "dequeueBatch unbounded" in {
      forAll { (s: PureStream[Int], batchSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(Stream.eval(async.unboundedQueue[IO,Option[Int]]).flatMap { q =>
            s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream.constant(batchSize.get).covary[IO].through(q.dequeueBatch).unNoneTerminate
          }) shouldBe s.get.toVector
        }
      }
    }
    "dequeueBatch circularBuffer" in {
      forAll { (s: PureStream[Int], maxSize: SmallPositive, batchSize: SmallPositive) =>
        withClue(s.tag) {
          runLog(Stream.eval(async.circularBuffer[IO,Option[Int]](maxSize.get + 1)).flatMap { q =>
            s.get.noneTerminate.evalMap(q.enqueue1).drain ++ Stream.constant(batchSize.get).covary[IO].through(q.dequeueBatch).unNoneTerminate
          }) shouldBe s.get.toVector.takeRight(maxSize.get)
        }
      }
    }
    "timedEnqueue1 on bounded queue" in {
      runLog(Scheduler[IO](1).flatMap { scheduler =>
        Stream.eval(
          for {
            q <- async.boundedQueue[IO,Int](1)
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
            q <- async.unboundedQueue[IO,Int]
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
      runLog(Stream.eval(async.unboundedQueue[IO,Int]).flatMap(_.size.discrete).take(1)) shouldBe Vector(0)
    }
  }
}
