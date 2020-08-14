package fs2

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import org.scalacheck.effect.PropF.forAllF

class StreamMergeSuite extends Fs2Suite {
  group("merge") {
    test("basic") {
      forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        val expected = s1.toList.toSet ++ s2.toList.toSet
        s1.merge(s2.covary[IO])
          .compile
          .toList
          .map(result => assert(result.toSet == expected))
      }
    }

    group("left/right identity") {
      test("1") {
        forAllF { (s1: Stream[Pure, Int]) =>
          val expected = s1.toList
          s1.covary[IO].merge(Stream.empty).compile.toList.map(it => assert(it == expected))
        }
      }
      test("2") {
        forAllF { (s1: Stream[Pure, Int]) =>
          val expected = s1.toList
          Stream.empty.merge(s1.covary[IO]).compile.toList.map(it => assert(it == expected))
        }
      }
    }

    group("left/right failure") {
      test("1") {
        forAllF { (s1: Stream[Pure, Int]) =>
          s1.covary[IO].merge(Stream.raiseError[IO](new Err)).compile.drain.assertThrows[Err]
        }
      }

      test("2 - never-ending flatMap, failure after emit") {
        forAllF { (s1: Stream[Pure, Int]) =>
          s1.merge(Stream.raiseError[IO](new Err))
            .evalMap(_ => IO.never)
            .compile
            .drain
            .assertThrows[Err]
        }
      }

      if (isJVM)
        test("3 - constant flatMap, failure after emit") {
          forAllF { (s1: Stream[Pure, Int]) =>
            s1.merge(Stream.raiseError[IO](new Err))
              .flatMap(_ => Stream.constant(true))
              .compile
              .drain
              .assertThrows[Err]
          }
        }
    }

    test("run finalizers of inner streams first") {
      forAllF { (s: Stream[Pure, Int], leftBiased: Boolean) =>
        // tests that finalizers of inner stream are always run before outer finalizer
        // also this will test that when the either side throws an exception in finalizer it is caught
        val err = new Err
        Ref.of[IO, List[String]](Nil).flatMap { finalizerRef =>
          Ref.of[IO, (Boolean, Boolean)]((false, false)).flatMap { sideRunRef =>
            Deferred[IO, Unit].flatMap { halt =>
              def bracketed =
                Stream.bracket(IO.unit)(_ => finalizerRef.update(_ :+ "Outer"))

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
                    .raiseError(err)
                else if (!leftBiased && side == "R")
                  IO.sleep(100.millis) >> finalizerRef.update(_ :+ s"Inner $side") >> IO
                    .raiseError(err)
                else IO.sleep(50.millis) >> finalizerRef.update(_ :+ s"Inner $side")

              val prg0 =
                bracketed
                  .flatMap { _ =>
                    (Stream.bracket(register("L"))(_ => finalizer("L")) >> s)
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
                        assert(
                          List("Inner L", "Inner R", "Outer").forall(finalizers.contains)
                        )
                        assert(finalizers.lastOption == Some("Outer"))
                        assert(r == Left(err))
                      }
                      else if (left) IO {
                        assert(finalizers == List("Inner L", "Outer"))
                        if (leftBiased) assert(r == Left(err))
                        else assert(r == Right(()))
                      }
                      else if (right) IO {
                        assert(finalizers == List("Inner R", "Outer"))
                        if (!leftBiased) assert(r == Left(err))
                        else assert(r == Right(()))
                      }
                      else
                        IO {
                          assert(finalizers == List("Outer"))
                          assert(r == Right(()))
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

      test("1")(full.merge(hang).take(1).compile.toList.map(it => assert(it == List(42))))
      test("2")(full.merge(hang2).take(1).compile.toList.map(it => assert(it == List(42))))
      test("3")(full.merge(hang3).take(1).compile.toList.map(it => assert(it == List(42))))
      test("4")(hang.merge(full).take(1).compile.toList.map(it => assert(it == List(42))))
      test("5")(hang2.merge(full).take(1).compile.toList.map(it => assert(it == List(42))))
      test("6")(hang3.merge(full).take(1).compile.toList.map(it => assert(it == List(42))))
    }
  }

  test("mergeHaltBoth") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      val s2List = s2.toList
      s1.covary[IO].map(Left(_)).mergeHaltBoth(s2.map(Right(_))).compile.toList.map { result =>
        assert(
          (result.collect { case Left(a) => a } == s1List) ||
            (result.collect { case Right(a) => a } == s2List)
        )
      }
    }
  }

  test("mergeHaltL") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      s1.covary[IO].map(Left(_)).mergeHaltL(s2.map(Right(_))).compile.toList.map { result =>
        assert(result.collect { case Left(a) => a } == s1List)
      }
    }
  }

  test("mergeHaltR") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s2List = s2.toList
      s1.covary[IO].map(Left(_)).mergeHaltR(s2.map(Right(_))).compile.toList.map { result =>
        assert(result.collect { case Right(a) => a } == s2List)
      }
    }
  }
}
