package fs2
package concurrent

import cats.implicits._
import cats.effect.IO
import fs2._
import fs2.Stream._

import scala.concurrent.duration._

class TopicSpec extends Fs2Spec {

  "Topic" - {

    "subscribers see all elements published" in {

      val topic = Topic[IO, Int](-1).unsafeRunSync()
      val count = 100
      val subs = 10
      val publisher = Stream.sleep[IO](1.second) ++ Stream
        .range(0, count)
        .covary[IO]
        .through(topic.publish)
      val subscriber =
        topic.subscribe(Int.MaxValue).take(count + 1).fold(Vector.empty[Int]) {
          _ :+ _
        }

      val result = (Stream
        .range(0, subs)
        .map(idx => subscriber.map(idx -> _)) ++ publisher.drain)
        .parJoin(subs + 1)
        .compile
        .toVector
        .unsafeRunSync()

      val expected = (for { i <- 0 until subs } yield i).map { idx =>
        idx -> (for { i <- -1 until count } yield i).toVector
      }.toMap

      result.toMap.size shouldBe subs
      result.toMap shouldBe expected
    }

    "synchronous publish" in {
      pending // TODO I think there's a race condition on the signal in this test
      val topic = Topic[IO, Int](-1).unsafeRunSync()
      val signal = SignallingRef[IO, Int](0).unsafeRunSync()
      val count = 100
      val subs = 10

      val publisher = Stream.sleep[IO](1.second) ++ Stream
        .range(0, count)
        .covary[IO]
        .flatMap(i => eval(signal.set(i)).map(_ => i))
        .through(topic.publish)
      val subscriber = topic
        .subscribe(1)
        .take(count + 1)
        .flatMap { is =>
          eval(signal.get).map(is -> _)
        }
        .fold(Vector.empty[(Int, Int)]) { _ :+ _ }

      val result = (Stream
        .range(0, subs)
        .map(idx => subscriber.map(idx -> _)) ++ publisher.drain)
        .parJoin(subs + 1)
        .compile
        .toVector
        .unsafeRunSync()

      result.toMap.size shouldBe subs

      result.foreach {
        case (idx, subResults) =>
          val diff: Set[Int] = subResults.map {
            case (read, state) => Math.abs(state - read)
          }.toSet
          assert(diff.min == 0 || diff.min == 1)
          assert(diff.max == 0 || diff.max == 1)
      }
    }

    "discrete-size-signal-is-discrete" in {

      def p =
        Stream
          .eval(Topic[IO, Int](0))
          .flatMap { topic =>
            def subscribers = topic.subscribers
            def subscribe = Stream.fixedDelay[IO](200.millis).evalMap { _ =>
              topic.subscribe(10).compile.drain.start.void
            }
            subscribers.concurrently(subscribe)
          }
          .interruptWhen(Stream.sleep[IO](2.seconds).as(true))

      p.compile.toList
        .unsafeRunSync()
        .size shouldBe <=(11) // if the stream won't be discrete we will get much more size notifications
    }

    "unregister subscribers under concurrent load" in {
      Topic[IO, Int](0)
        .flatMap { topic =>
          Stream
            .range(0, 500)
            .map(_ => topic.subscribe(1).interruptWhen(Stream(true)))
            .parJoinUnbounded
            .compile
            .drain >> topic.subscribers.take(1).compile.lastOrError
        }
        .unsafeRunSync() shouldBe 0
    }
  }
}
