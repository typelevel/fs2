package fs2
package concurrent

import cats.effect.IO
import cats.implicits._
import scala.concurrent.duration._
import org.scalacheck.effect.PropF.forAllF

class QueueSuite extends Fs2Suite {
  test("unbounded producer/consumer") {
    forAllF { (s: Stream[Pure, Int]) =>
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
        .map(it => assert(it == expected))
    }
  }
  test("circularBuffer") {
    forAllF { (s: Stream[Pure, Int], maxSize0: Int) =>
      val maxSize = (maxSize0 % 20).abs + 1
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
        .map(it => assert(it == expected))
    }
  }

  test("circularBufferNoneTerminated") {
    forAllF { (s: Stream[Pure, Int], maxSize0: Int) =>
      val maxSize = (maxSize0 % 20).abs + 1
      val expected = s.toList.takeRight(maxSize)
      Stream
        .eval(Queue.circularBufferNoneTerminated[IO, Int](maxSize))
        .flatMap { q =>
          s.noneTerminate
            .evalMap(x => q.enqueue1(x))
            .drain ++ q.dequeue
        }
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }
  test("dequeueAvailable") {
    forAllF { (s: Stream[Pure, Int]) =>
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
        .map { result =>
          assert(result.size < 2)
          assert(result.flatMap(_.toList) == expected)
        }
    }
  }
  test("dequeueBatch unbounded") {
    forAllF { (s: Stream[Pure, Int], batchSize0: Int) =>
      val batchSize = (batchSize0 % 20).abs + 1
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
        .map(it => assert(it == expected))
    }
  }
  test("dequeueBatch circularBuffer") {
    forAllF { (s: Stream[Pure, Int], maxSize0: Int, batchSize0: Int) =>
      val maxSize = (maxSize0 % 20).abs + 1
      val batchSize = (batchSize0 % 20).abs + 1
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
        .map(it => assert(it == expected))
    }
  }

  group("dequeue releases subscriber on") {
    test("interrupt") {
      Queue
        .unbounded[IO, Int]
        .flatMap { q =>
          q.dequeue.interruptAfter(1.second).compile.drain >>
            q.enqueue1(1) >>
            q.enqueue1(2) >>
            q.dequeue1
        }
        .map(it => assert(it == 1))
    }

    test("cancel") {
      Queue
        .unbounded[IO, Int]
        .flatMap { q =>
          q.dequeue1.timeout(1.second).attempt >>
            q.enqueue1(1) >>
            q.enqueue1(2) >>
            q.dequeue1
        }
        .map(it => assert(it == 1))
    }
  }

  test("size signal is initialized to zero") {
    Stream
      .eval(InspectableQueue.unbounded[IO, Int])
      .flatMap(_.size)
      .take(1)
      .compile
      .toList
      .map(it => assert(it == List(0)))
  }

  test("size stream is discrete") {
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
      .map(it =>
        assert(it.size <= 11)
      ) // if the stream won't be discrete we will get much more size notifications
  }

  test("peek1") {
    Stream
      .eval(
        for {
          q <- InspectableQueue.unbounded[IO, Int]
          f <- q.peek1.start
          g <- q.peek1.start
          _ <- q.enqueue1(42)
          x <- f.joinAndEmbedNever
          y <- g.joinAndEmbedNever
        } yield List(x, y)
      )
      .compile
      .toList
      .map(it => assertEquals(it.flatten, List(42, 42)))
  }

  test("peek1 with dequeue1") {
    Stream
      .eval(
        for {
          q <- InspectableQueue.unbounded[IO, Int]
          f <- q.peek1.product(q.dequeue1).start
          _ <- q.enqueue1(42)
          x <- f.joinAndEmbedNever
          g <- q.peek1.product(q.dequeue1).product(q.peek1.product(q.dequeue1)).start
          _ <- q.enqueue1(43)
          _ <- q.enqueue1(44)
          yz <- g.joinAndEmbedNever
          (y, z) = yz
        } yield List(x, y, z)
      )
      .compile
      .toList
      .map(it => assertEquals(it.flatten, List((42, 42), (43, 43), (44, 44))))
  }

  test("peek1 bounded queue") {
    Stream
      .eval(
        for {
          q <- InspectableQueue.bounded[IO, Int](maxSize = 1)
          f <- q.peek1.start
          g <- q.peek1.start
          _ <- q.enqueue1(42)
          b <- q.offer1(43)
          x <- f.joinAndEmbedNever
          y <- g.joinAndEmbedNever
          z <- q.dequeue1
        } yield List(b, x, y, z)
      )
      .compile
      .toList
      .map(it => assertEquals(it.flatten, List[AnyVal](false, 42, 42, 42)))
  }

  test("peek1 circular buffer") {
    Stream
      .eval(
        for {
          q <- InspectableQueue.circularBuffer[IO, Int](maxSize = 1)
          f <- q.peek1.start
          g <- q.peek1.start
          _ <- q.enqueue1(42)
          x <- f.joinAndEmbedNever
          y <- g.joinAndEmbedNever
          b <- q.offer1(43)
          z <- q.peek1
        } yield List(b, x, y, z)
      )
      .compile
      .toList
      .map(it => assertEquals(it.flatten, List[AnyVal](true, 42, 42, 43)))
  }
}
