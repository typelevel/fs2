package fs2

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._

class StreamParJoinSuite extends Fs2Suite {
  test("no concurrency") {
    forAllAsync { (s: Stream[Pure, Int]) =>
      val expected = s.toList.toSet
      s.covary[IO]
        .map(Stream.emit(_).covary[IO])
        .parJoin(1)
        .compile
        .toList
        .map(it => assert(it.toSet == expected))
    }
  }

  test("concurrency") {
    forAllAsync { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val expected = s.toList.toSet
      s.covary[IO]
        .map(Stream.emit(_).covary[IO])
        .parJoin(n)
        .compile
        .toList
        .map(it => assert(it.toSet == expected))
    }
  }

  test("concurrent flattening") {
    forAllAsync { (s: Stream[Pure, Stream[Pure, Int]], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val expected = s.flatten.toList.toSet
      s.map(_.covary[IO])
        .covary[IO]
        .parJoin(n)
        .compile
        .toList
        .map(it => assert(it.toSet == expected))
    }
  }

  test("merge consistency") {
    forAllAsync { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val parJoined = Stream(s1.covary[IO], s2).parJoin(2).compile.toList.map(_.toSet)
      val merged = s1.covary[IO].merge(s2).compile.toList.map(_.toSet)
      (parJoined, merged).tupled.map { case (pj, m) => assert(pj == m) }
    }
  }

  test("resources acquired in outer stream are released after inner streams complete") {
    val bracketed =
      Stream.bracket(IO(new java.util.concurrent.atomic.AtomicBoolean(true)))(b => IO(b.set(false)))
    // Starts an inner stream which fails if the resource b is finalized
    val s: Stream[IO, Stream[IO, Unit]] = bracketed.map { b =>
      Stream
        .eval(IO(b.get))
        .flatMap(b => if (b) Stream(()) else Stream.raiseError[IO](new Err))
        .repeat
        .take(10000)
    }
    s.parJoinUnbounded.compile.drain
  }

  test("run finalizers of inner streams first") {
    forAllAsync { (s1: Stream[Pure, Int], bias: Boolean) =>
      val err = new Err
      val biasIdx = if (bias) 1 else 0
      Ref
        .of[IO, List[String]](Nil)
        .flatMap { finalizerRef =>
          Ref.of[IO, List[Int]](Nil).flatMap { runEvidenceRef =>
            Deferred[IO, Unit].flatMap { halt =>
              def bracketed =
                Stream.bracket(IO.unit)(_ => finalizerRef.update(_ :+ "Outer"))

              def registerRun(idx: Int): IO[Unit] =
                runEvidenceRef.update(_ :+ idx)

              def finalizer(idx: Int): IO[Unit] =
                // this introduces delay and failure based on bias of the test
                if (idx == biasIdx)
                  IO.sleep(100.millis) >>
                    finalizerRef.update(_ :+ s"Inner $idx") >>
                    IO.raiseError(err)
                else
                  finalizerRef.update(_ :+ s"Inner $idx")

              val prg0 =
                bracketed.flatMap { _ =>
                  Stream(
                    Stream.bracket(registerRun(0))(_ => finalizer(0)) >> s1,
                    Stream.bracket(registerRun(1))(_ => finalizer(1)) >> Stream
                      .exec(halt.complete(()))
                  )
                }

              prg0.parJoinUnbounded.compile.drain.attempt.flatMap { r =>
                finalizerRef.get.flatMap { finalizers =>
                  runEvidenceRef.get.flatMap { streamRunned =>
                    IO {
                      val expectedFinalizers = streamRunned.map { idx =>
                        s"Inner $idx"
                      } :+ "Outer"
                      assert(finalizers.toSet == expectedFinalizers.toSet)
                      assert(finalizers.lastOption == Some("Outer"))
                      if (streamRunned.contains(biasIdx)) assert(r == Left(err))
                      else assert(r == Right(()))
                    }
                  }
                }
              }
            }
          }
        }
    }
  }

  group("hangs") {
    val full = if (isJVM) Stream.constant(42) else Stream.constant(42).evalTap(_ => IO.shift)
    val hang = Stream.repeatEval(IO.async[Unit](_ => ()))
    val hang2: Stream[IO, Nothing] = full.drain
    val hang3: Stream[IO, Nothing] =
      Stream
        .repeatEval[IO, Unit](IO.async[Unit](cb => cb(Right(()))) >> IO.shift)
        .drain

    test("1") {
      Stream(full, hang)
        .parJoin(10)
        .take(1)
        .compile
        .toList
        .map(it => assert(it == List(42)))
    }
    test("2") {
      Stream(full, hang2)
        .parJoin(10)
        .take(1)
        .compile
        .toList
        .map(it => assert(it == List(42)))
    }
    test("3") {
      Stream(full, hang3)
        .parJoin(10)
        .take(1)
        .compile
        .toList
        .map(it => assert(it == List(42)))
    }
    test("4") {
      Stream(hang3, hang2, full)
        .parJoin(10)
        .take(1)
        .compile
        .toList
        .map(it => assert(it == List(42)))
    }
  }

  test("outer failed") {
    Stream(
      Stream.sleep_[IO](1.minute),
      Stream.raiseError[IO](new Err)
    ).parJoinUnbounded.compile.drain
      .assertThrows[Err]
  }

  test("propagate error from inner stream before ++") {
    val err = new Err

    (Stream
      .emit(Stream.raiseError[IO](err))
      .parJoinUnbounded ++ Stream.emit(1)).compile.toList.attempt
      .map(it => assert(it == Left(err)))
  }
}
