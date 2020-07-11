package fs2

import cats.data.Chain
import cats.effect.{IO, Sync, SyncIO}
import cats.effect.concurrent.Ref
import cats.implicits._
import org.scalacheck.Prop.forAll
import scala.concurrent.duration._

class StreamCombinatorsSuite extends Fs2Suite {

  group("awakeEvery") {
    test("basic") {
      Stream
        .awakeEvery[IO](500.millis)
        .map(_.toMillis)
        .take(5)
        .compile
        .toVector
        .map { r =>
          r.sliding(2)
            .map(s => (s.head, s.tail.head))
            .map { case (prev, next) => next - prev }
            .foreach(delta => assert(delta >= 350L && delta <= 650L))
        }
    }

    test("liveness") {
      val s = Stream
        .awakeEvery[IO](1.milli)
        .evalMap(_ => IO.async[Unit](cb => realExecutionContext.execute(() => cb(Right(())))))
        .take(200)
      Stream(s, s, s, s, s).parJoin(5).compile.drain
    }
  }

  group("buffer") {
    property("identity") {
      forAll { (s: Stream[Pure, Int], n: Int) =>
        assert(s.buffer(n).toVector == s.toVector)
      }
    }

    test("buffer results of evalMap") {
      forAllAsync { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        IO.suspend {
          var counter = 0
          val s2 = s.append(Stream.emits(List.fill(n + 1)(0))).repeat
          s2.evalMap(i => IO { counter += 1; i })
            .buffer(n)
            .take(n + 1)
            .compile
            .drain
            .map(_ => assert(counter == (n * 2)))
        }
      }
    }
  }

  group("bufferAll") {
    property("identity") {
      forAll((s: Stream[Pure, Int]) => assert(s.bufferAll.toVector == s.toVector))
    }

    test("buffer results of evalMap") {
      forAllAsync { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2
        IO.suspend {
          var counter = 0
          s.append(s)
            .evalMap(i => IO { counter += 1; i })
            .bufferAll
            .take(s.toList.size + 1)
            .compile
            .drain
            .map(_ => assert(counter == expected))
        }
      }
    }
  }

  group("bufferBy") {
    property("identity") {
      forAll { (s: Stream[Pure, Int]) =>
        assert(s.bufferBy(_ >= 0).toVector == s.toVector)
      }
    }

    test("buffer results of evalMap") {
      forAllAsync { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2 + 1
        IO.suspend {
          var counter = 0
          val s2 = s.map(x => if (x == Int.MinValue) x + 1 else x).map(_.abs)
          val s3 = s2.append(Stream.emit(-1)).append(s2).evalMap(i => IO { counter += 1; i })
          s3.bufferBy(_ >= 0)
            .take(s.toList.size + 2)
            .compile
            .drain
            .map(_ => assert(counter == expected))
        }
      }
    }
  }

}
