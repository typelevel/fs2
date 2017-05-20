package fs2

import scala.concurrent.duration._
import cats.effect.IO

class MergeJoinSpec extends Fs2Spec {

  "concurrent" - {

    "either" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      val shouldCompile = s1.get.either(s2.get.covary[IO])
      val es = runLog { s1.get.covary[IO].either(s2.get) }
      es.collect { case Left(i) => i } shouldBe runLog(s1.get)
      es.collect { case Right(i) => i } shouldBe runLog(s2.get)
    }

    "merge" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      runLog { s1.get.merge(s2.get.covary[IO]) }.toSet shouldBe
      (runLog(s1.get).toSet ++ runLog(s2.get).toSet)
    }

    "merge (left/right identity)" in forAll { (s1: PureStream[Int]) =>
      runLog { s1.get.covary[IO].merge(Stream.empty) } shouldBe runLog(s1.get)
      runLog { Stream.empty.merge(s1.get.covary[IO]) } shouldBe runLog(s1.get)
    }

    "merge/join consistency" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      runLog { s1.get.covary[IO].merge(s2.get) }.toSet shouldBe
      runLog { Stream.join(2)(Stream(s1.get.covary[IO], s2.get.covary[IO])) }.toSet
    }

    "join (1)" in forAll { (s1: PureStream[Int]) =>
      runLog { Stream.join(1)(s1.get.covary[IO].map(Stream.emit(_).covary[IO])) }.toSet shouldBe runLog { s1.get }.toSet
    }

    "join (2)" in forAll { (s1: PureStream[Int], n: SmallPositive) =>
      runLog { Stream.join(n.get)(s1.get.covary[IO].map(Stream.emit(_).covary[IO])) }.toSet shouldBe
      runLog { s1.get }.toSet
    }

    "join (3)" in forAll { (s1: PureStream[PureStream[Int]], n: SmallPositive) =>
      runLog { Stream.join(n.get)(s1.get.map(_.get.covary[IO]).covary[IO]) }.toSet shouldBe
      runLog { s1.get.flatMap(_.get) }.toSet
    }

    "merge (left/right failure)" in forAll { (s1: PureStream[Int], f: Failure) =>
      an[Err.type] should be thrownBy {
        s1.get.merge(f.get).run.unsafeRunSync()
      }
    }

    "hanging awaits" - {

      val full = Stream.constant(42).covary[IO]
      val hang = Stream.repeatEval(IO.async[Unit] { cb => () }) // never call `cb`!
      val hang2: Stream[IO,Nothing] = full.drain
      val hang3: Stream[IO,Nothing] =
        Stream.repeatEval[IO,Unit](IO.async { (cb: Either[Throwable,Unit] => Unit) => cb(Right(())) }.shift).drain

      "merge" in {
        runLog((full merge hang).take(1)) shouldBe Vector(42)
        runLog((full merge hang2).take(1)) shouldBe Vector(42)
        runLog((full merge hang3).take(1)) shouldBe Vector(42)
        runLog((hang merge full).take(1)) shouldBe Vector(42)
        runLog((hang2 merge full).take(1)) shouldBe Vector(42)
        runLog((hang3 merge full).take(1)) shouldBe Vector(42)
      }

      "join" in {
        runLog(Stream(full, hang).join(10).take(1)) shouldBe Vector(42)
        runLog(Stream(full, hang2).join(10).take(1)) shouldBe Vector(42)
        runLog(Stream(full, hang3).join(10).take(1)) shouldBe Vector(42)
        runLog(Stream(hang3,hang2,full).join(10).take(1)) shouldBe Vector(42)
      }
    }

    "join - outer-failed" in {
      an[Err.type] should be thrownBy { runLog(Stream(time.sleep_[IO](1 minute), Stream.fail(Err).covary[IO]).joinUnbounded) }
    }
  }
}
