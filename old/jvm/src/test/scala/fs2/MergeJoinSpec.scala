package fs2

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import TestUtil._

class MergeJoinSpec extends Fs2Spec {

  "concurrent" - {

    "either" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      val _ = s1.get.either(s2.get.covary[IO])
      val es = runLog { s1.get.covary[IO].either(s2.get) }
      es.collect { case Left(i)  => i } shouldBe runLog(s1.get)
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

    "merge/parJoin consistency" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      runLog { s1.get.covary[IO].merge(s2.get) }.toSet shouldBe
        runLog { Stream(s1.get.covary[IO], s2.get).parJoin(2) }.toSet
    }

    "parJoin (1)" in forAll { (s1: PureStream[Int]) =>
      runLog { s1.get.covary[IO].map(Stream.emit(_).covary[IO]).parJoin(1) }.toSet shouldBe runLog {
        s1.get
      }.toSet
    }

    "parJoin (2)" in forAll { (s1: PureStream[Int], n: SmallPositive) =>
      runLog { s1.get.covary[IO].map(Stream.emit(_).covary[IO]).parJoin(n.get) }.toSet shouldBe
        runLog { s1.get }.toSet
    }

    "parJoin (3)" in forAll { (s1: PureStream[PureStream[Int]], n: SmallPositive) =>
      runLog { s1.get.map(_.get.covary[IO]).covary[IO].parJoin(n.get) }.toSet shouldBe
        runLog { s1.get.flatMap(_.get) }.toSet
    }

    "parJoin - resources acquired in outer stream are released after inner streams complete" in {
      val bracketed =
        Stream.bracket(IO(new java.util.concurrent.atomic.AtomicBoolean(true)))(b =>
          IO(b.set(false)))
      // Starts an inner stream which fails if the resource b is finalized
      val s: Stream[IO, Stream[IO, Unit]] = bracketed.map { b =>
        Stream
          .eval(IO(b.get))
          .flatMap(b => if (b) Stream(()) else Stream.raiseError[IO](new Err))
          .repeat
          .take(10000)
      }
      s.parJoinUnbounded.compile.drain.unsafeRunSync()
    }

    "parJoin - run finalizers of inner streams first" in forAll {
      (s1: PureStream[Int], bias: Boolean) =>
        val Boom = new Err
        val biasIdx = if (bias) 1 else 0

        val prg =
          Ref.of[IO, List[String]](Nil).flatMap { finalizerRef =>
            Ref.of[IO, List[Int]](Nil).flatMap { runEvidenceRef =>
              Deferred[IO, Unit].flatMap { halt =>
                def bracketed =
                  Stream.bracket(IO.unit)(
                    _ => finalizerRef.update(_ :+ "Outer")
                  )

                def registerRun(idx: Int): IO[Unit] =
                  runEvidenceRef.update(_ :+ idx)

                def finalizer(idx: Int): IO[Unit] =
                  // this introduces delay and failure based on bias of the test
                  if (idx == biasIdx) {
                    IO.sleep(100.millis) >>
                      finalizerRef.update(_ :+ s"Inner $idx") >>
                      IO.raiseError(Boom)
                  } else {
                    finalizerRef.update(_ :+ s"Inner $idx")
                  }

                val prg0 =
                  bracketed.flatMap { _ =>
                    Stream(
                      Stream.bracket(registerRun(0))(_ => finalizer(0)) >> s1.get,
                      Stream.bracket(registerRun(1))(_ => finalizer(1)) >> Stream.eval_(
                        halt.complete(()))
                    )
                  }

                prg0.parJoinUnbounded.compile.drain.attempt.flatMap { r =>
                  finalizerRef.get.flatMap { finalizers =>
                    runEvidenceRef.get.flatMap { streamRunned =>
                      IO {
                        val expectedFinalizers = (streamRunned.map { idx =>
                          s"Inner $idx"
                        }) :+ "Outer"
                        (finalizers should contain).theSameElementsAs(expectedFinalizers)
                        finalizers.lastOption shouldBe Some("Outer")
                        if (streamRunned.contains(biasIdx)) r shouldBe Left(Boom)
                        else r shouldBe Right(())
                      }
                    }
                  }
                }

              }
            }
          }

        prg.unsafeRunSync()
    }

    "merge (left/right failure)" in {
      forAll { (s1: PureStream[Int], f: Failure) =>
        an[Err] should be thrownBy {
          s1.get.merge(f.get).compile.drain.unsafeRunSync()
        }
      }
    }

    "merge (left/right failure) never-ending flatMap, failure after emit" in {
      forAll { (s1: PureStream[Int], f: Failure) =>
        an[Err] should be thrownBy {
          s1.get
            .merge(f.get)
            .flatMap { _ =>
              Stream.eval(IO.async[Unit](_ => ()))
            }
            .compile
            .drain
            .unsafeRunSync()
        }
      }
    }

    "merge (left/right failure) constant flatMap, failure after emit" in {
      forAll { (s1: PureStream[Int], f: Failure) =>
        an[Err] should be thrownBy {
          s1.get
            .merge(f.get)
            .flatMap { _ =>
              Stream.constant(true)
            }
            .compile
            .drain
            .unsafeRunSync()
        }
      }
    }

    "merge - run finalizers of inner streams first" in forAll {
      (s: PureStream[Int], leftBiased: Boolean) =>
        // tests that finalizers of inner stream are always run before outer finalizer
        // also this will test that when the either side throws an exception in finalizer it is caught

        val Boom = new Err

        val prg =
          Ref.of[IO, List[String]](Nil).flatMap { finalizerRef =>
            Ref.of[IO, (Boolean, Boolean)]((false, false)).flatMap { sideRunRef =>
              Deferred[IO, Unit].flatMap { halt =>
                def bracketed =
                  Stream.bracket(IO.unit)(
                    _ => finalizerRef.update(_ :+ "Outer")
                  )

                def register(side: String): IO[Unit] =
                  sideRunRef.update {
                    case (left, right) =>
                      if (side == "L") (true, right)
                      else (left, true)
                  }

                def finalizer(side: String): IO[Unit] =
                  // this introduces delay and failure based on bias of the test
                  if (leftBiased && side == "L")
                    IO.sleep(100.millis) >> finalizerRef.update(_ :+ s"Inner $side") >> IO
                      .raiseError(Boom)
                  else if (!leftBiased && side == "R")
                    IO.sleep(100.millis) >> finalizerRef.update(_ :+ s"Inner $side") >> IO
                      .raiseError(Boom)
                  else IO.sleep(50.millis) >> finalizerRef.update(_ :+ s"Inner $side")

                val prg0 =
                  bracketed
                    .flatMap { b =>
                      (Stream.bracket(register("L"))(_ => finalizer("L")) >> s.get)
                        .merge(
                          Stream.bracket(register("R"))(_ => finalizer("R")) >>
                            Stream
                              .eval(halt.complete(())) // immediately interrupt the outer stream
                        )
                    }
                    .interruptWhen(halt.get.attempt)

                prg0.compile.drain.attempt.flatMap { r =>
                  finalizerRef.get.flatMap { finalizers =>
                    sideRunRef.get.flatMap {
                      case (left, right) =>
                        if (left && right) IO {
                          (finalizers should contain).allOf("Inner L", "Inner R", "Outer")
                          finalizers.lastOption shouldBe Some("Outer")
                          r shouldBe Left(Boom)
                        } else if (left) IO {
                          finalizers shouldBe List("Inner L", "Outer")
                          if (leftBiased) r shouldBe Left(Boom)
                          else r shouldBe Right(())
                        } else if (right) IO {
                          finalizers shouldBe List("Inner R", "Outer")
                          if (!leftBiased) r shouldBe Left(Boom)
                          else r shouldBe Right(())
                        } else
                          IO {
                            finalizers shouldBe List("Outer")
                            r shouldBe Right(())
                          }

                    }
                  }
                }
              }
            }
          }

        prg.unsafeRunSync

    }

    "hanging awaits" - {

      val full = Stream.constant(42)
      val hang = Stream.repeatEval(IO.async[Unit] { cb =>
        ()
      }) // never call `cb`!
      val hang2: Stream[IO, Nothing] = full.drain
      val hang3: Stream[IO, Nothing] =
        Stream
          .repeatEval[IO, Unit](IO.async[Unit] { cb =>
            cb(Right(()))
          } >> IO.shift)
          .drain

      "merge" in {
        runLog(full.merge(hang).take(1)) shouldBe Vector(42)
        runLog(full.merge(hang2).take(1)) shouldBe Vector(42)
        runLog(full.merge(hang3).take(1)) shouldBe Vector(42)
        runLog(hang.merge(full).take(1)) shouldBe Vector(42)
        runLog(hang2.merge(full).take(1)) shouldBe Vector(42)
        runLog(hang3.merge(full).take(1)) shouldBe Vector(42)
      }

      "parJoin" in {
        runLog(Stream(full, hang).parJoin(10).take(1)) shouldBe Vector(42)
        runLog(Stream(full, hang2).parJoin(10).take(1)) shouldBe Vector(42)
        runLog(Stream(full, hang3).parJoin(10).take(1)) shouldBe Vector(42)
        runLog(Stream(hang3, hang2, full).parJoin(10).take(1)) shouldBe Vector(42)
      }
    }

    "parJoin - outer-failed" in {
      an[Err] should be thrownBy {
        runLog(Stream(Stream.sleep_[IO](1.minute), Stream.raiseError[IO](new Err)).parJoinUnbounded)
      }
    }
  }
}
