package fs2

import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.concurrent.{Deferred, Semaphore}
import cats.implicits._
import org.scalacheck.Gen
import TestUtil._

import fs2.concurrent.SignallingRef

class Pipe2Spec extends Fs2Spec {

  // number of interrupt tests to run successive
  private val interruptN = 250

  "Pipe2" - {

    "zipWith left/right side infinite" in {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      runLog(ones.zipWith(s)(_ + _)) shouldBe Vector("1A", "1B", "1C")
      runLog(s.zipWith(ones)(_ + _)) shouldBe Vector("A1", "B1", "C1")
    }

    "zipWith both side infinite" in {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      runLog(ones.zipWith(as)(_ + _).take(3)) shouldBe Vector("1A", "1A", "1A")
      runLog(as.zipWith(ones)(_ + _).take(3)) shouldBe Vector("A1", "A1", "A1")
    }

    "zipAllWith left/right side infinite" in {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      runLog(ones.zipAllWith(s)("2", "Z")(_ + _).take(5)) shouldBe
        Vector("1A", "1B", "1C", "1Z", "1Z")
      runLog(s.zipAllWith(ones)("Z", "2")(_ + _).take(5)) shouldBe
        Vector("A1", "B1", "C1", "Z1", "Z1")
    }

    "zipAllWith both side infinite" in {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      runLog(ones.zipAllWith(as)("2", "Z")(_ + _).take(3)) shouldBe
        Vector("1A", "1A", "1A")
      runLog(as.zipAllWith(ones)("Z", "2")(_ + _).take(3)) shouldBe
        Vector("A1", "A1", "A1")
    }

    "zip left/right side infinite" in {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      runLog(ones.zip(s)) shouldBe Vector("1" -> "A", "1" -> "B", "1" -> "C")
      runLog(s.zip(ones)) shouldBe Vector("A" -> "1", "B" -> "1", "C" -> "1")
    }

    "zip both side infinite" in {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      runLog(ones.zip(as).take(3)) shouldBe Vector("1" -> "A", "1" -> "A", "1" -> "A")
      runLog(as.zip(ones).take(3)) shouldBe Vector("A" -> "1", "A" -> "1", "A" -> "1")
    }

    "zipAll left/right side infinite" in {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      runLog(ones.zipAll(s)("2", "Z").take(5)) shouldBe Vector("1" -> "A",
                                                               "1" -> "B",
                                                               "1" -> "C",
                                                               "1" -> "Z",
                                                               "1" -> "Z")
      runLog(s.zipAll(ones)("Z", "2").take(5)) shouldBe Vector("A" -> "1",
                                                               "B" -> "1",
                                                               "C" -> "1",
                                                               "Z" -> "1",
                                                               "Z" -> "1")
    }

    "zipAll both side infinite" in {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      runLog(ones.zipAll(as)("2", "Z").take(3)) shouldBe Vector("1" -> "A", "1" -> "A", "1" -> "A")
      runLog(as.zipAll(ones)("Z", "2").take(3)) shouldBe Vector("A" -> "1", "A" -> "1", "A" -> "1")
    }

    "zip with scopes" in {
      // this tests that streams opening resources on each branch will close
      // scopes independently.
      val s = Stream(0).scope
      (s ++ s).zip(s).toList
    }

    "issue #1120 - zip with uncons" in {
      // this tests we can properly look up scopes for the zipped streams
      //
      val rangeStream = Stream.emits((0 to 3).toList).covary[IO]

      runLog(rangeStream.zip(rangeStream).attempt.map(identity)) shouldBe Vector(
        Right((0, 0)),
        Right((1, 1)),
        Right((2, 2)),
        Right((3, 3))
      )
    }

    "interleave left/right side infinite" in {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      runLog(ones.interleave(s)) shouldBe Vector("1", "A", "1", "B", "1", "C")
      runLog(s.interleave(ones)) shouldBe Vector("A", "1", "B", "1", "C", "1")
    }

    "interleave both side infinite" in {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      runLog(ones.interleave(as).take(3)) shouldBe Vector("1", "A", "1")
      runLog(as.interleave(ones).take(3)) shouldBe Vector("A", "1", "A")
    }

    "interleaveAll left/right side infinite" in {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      runLog(ones.interleaveAll(s).take(9)) shouldBe Vector("1",
                                                            "A",
                                                            "1",
                                                            "B",
                                                            "1",
                                                            "C",
                                                            "1",
                                                            "1",
                                                            "1")
      runLog(s.interleaveAll(ones).take(9)) shouldBe Vector("A",
                                                            "1",
                                                            "B",
                                                            "1",
                                                            "C",
                                                            "1",
                                                            "1",
                                                            "1",
                                                            "1")
    }

    "interleaveAll both side infinite" in {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      runLog(ones.interleaveAll(as).take(3)) shouldBe Vector("1", "A", "1")
      runLog(as.interleaveAll(ones).take(3)) shouldBe Vector("A", "1", "A")
    }

    // Uses a small scope to avoid using time to generate too large streams and not finishing
    "interleave is equal to interleaveAll on infinite streams (by step-indexing)" in {
      forAll(Gen.choose(0, 100)) { (n: Int) =>
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.interleaveAll(as).take(n).toVector shouldBe ones
          .interleave(as)
          .take(n)
          .toVector
      }
    }

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

    "merge (left/right failure)" in {
      forAll { (s1: PureStream[Int], f: Failure) =>
        an[Err] should be thrownBy {
          runLog(s1.get.covary[IO].merge(f.get))
        }
      }
    }

    "mergeHalt{L/R/Both}" in forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
      withClue(s1.tag + " " + s2.tag) {
        val outBoth = runLog {
          s1.get.covary[IO].map(Left(_)).mergeHaltBoth(s2.get.map(Right(_)))
        }
        val outL = runLog {
          s1.get.covary[IO].map(Left(_)).mergeHaltL(s2.get.map(Right(_)))
        }
        val outR = runLog {
          s1.get.covary[IO].map(Left(_)).mergeHaltR(s2.get.map(Right(_)))
        }
        // out should contain at least all the elements from one of the input streams
        val e1 = runLog(s1.get)
        val e2 = runLog(s2.get)
        assert {
          (outBoth.collect { case Left(a)  => a } == e1) ||
          (outBoth.collect { case Right(a) => a } == e2)
        }
        outL.collect { case Left(a)  => a } shouldBe e1
        outR.collect { case Right(a) => a } shouldBe e2
      }
    }

    "interrupt (1)" in forAll { (s1: PureStream[Int]) =>
      val s = Semaphore[IO](0).unsafeRunSync()
      val interrupt =
        Stream.sleep_[IO](50.millis).compile.drain.attempt
      // tests that termination is successful even if stream being interrupted is hung
      runLog {
        s1.get.covary[IO].evalMap(_ => s.acquire).interruptWhen(interrupt)
      } shouldBe Vector()

    }

    "interrupt (2)" in forAll { (s1: PureStream[Int]) =>
      val s = Semaphore[IO](0).unsafeRunSync()
      val interrupt = Stream.emit(true) ++ Stream.eval_(s.release)
      // tests that termination is successful even if stream being interrupted is hung
      runLog {
        s1.get.covary[IO].evalMap(_ => s.acquire).interruptWhen(interrupt)
      } shouldBe Vector()

    }

    "interrupt (3)" in {
      // tests the interruption of the constant stream
      (1 until interruptN).foreach { _ =>
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt
        runLog(
          Stream
            .constant(true)
            .covary[IO]
            .interruptWhen(interrupt)
            .drain)
      }
    }

    "interrupt (4)" in {
      // tests the interruption of the constant stream with flatMap combinator
      (1 until interruptN).foreach { _ =>
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt
        runLog(
          Stream
            .constant(true)
            .covary[IO]
            .interruptWhen(interrupt)
            .flatMap(_ => Stream.emit(1))
            .drain)
      }
    }

    "interrupt (5)" in {
      // tests the interruption of the stream that recurses infinitelly
      (1 until interruptN).foreach { _ =>
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt

        def loop(i: Int): Stream[IO, Int] = Stream.emit(i).covary[IO].flatMap { i =>
          Stream.emit(i) ++ loop(i + 1)
        }

        runLog(loop(0).interruptWhen(interrupt).drain)
      }
    }

    "interrupt (6)" in {
      // tests the interruption of the stream that recurse infinitely and never emits
      (1 until interruptN).foreach { _ =>
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt

        def loop: Stream[IO, Int] =
          Stream
            .eval(IO {
              ()
            })
            .flatMap { _ =>
              loop
            }

        runLog(loop.interruptWhen(interrupt).drain)
      }
    }

    "interrupt (7)" in {
      (1 until interruptN).foreach { _ =>
        // tests the interruption of the stream that recurse infinitely, is pure and never emits
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt

        def loop: Stream[IO, Int] = Stream.emit(()).covary[IO].flatMap { _ =>
          loop
        }

        runLog(loop.interruptWhen(interrupt).drain)
      }
    }

    "interrupt (8)" in {
      (1 until interruptN).foreach { _ =>
        // tests the interruption of the stream that repeatedly evaluates
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt
        runLog(
          Stream
            .repeatEval(IO.unit)
            .interruptWhen(interrupt)
            .drain)
      }
    }

    "interrupt (9)" in {
      // tests the interruption of the constant drained stream
      (1 until interruptN).foreach { _ =>
        val interrupt =
          Stream.sleep_[IO](1.millis).compile.drain.attempt
        runLog(
          Stream
            .constant(true)
            .dropWhile(!_)
            .covary[IO]
            .interruptWhen(interrupt)
            .drain)
      }
    }

    "interrupt (10)" in forAll { (s1: PureStream[Int]) =>
      // tests that termination is successful even if interruption stream is infinitely false
      runLog {
        s1.get.covary[IO].interruptWhen(Stream.constant(false))
      } shouldBe runLog(s1.get)

    }

    "interrupt (11)" in forAll { (s1: PureStream[Int]) =>
      val barrier = Semaphore[IO](0).unsafeRunSync()
      val enableInterrupt = Semaphore[IO](0).unsafeRunSync()
      val interruptedS1 = s1.get.covary[IO].evalMap { i =>
        // enable interruption and hang when hitting a value divisible by 7
        if (i % 7 == 0) enableInterrupt.release.flatMap { _ =>
          barrier.acquire.as(i)
        } else IO.pure(i)
      }
      val interrupt = Stream.eval(enableInterrupt.acquire).flatMap { _ =>
        Stream.emit(false)
      }
      val out = runLog {
        interruptedS1.interruptWhen(interrupt)
      }
      // as soon as we hit a value divisible by 7, we enable interruption then hang before emitting it,
      // so there should be no elements in the output that are divisible by 7
      // this also checks that interruption works fine even if one or both streams are in a hung state
      assert(out.forall(i => i % 7 != 0))
    }

    "interrupt (12)" in forAll { (s1: PureStream[Int]) =>
      // tests interruption of stream that never terminates in flatMap
      val s = Semaphore[IO](0).unsafeRunSync()
      val interrupt =
        Stream.sleep_[IO](50.millis).compile.drain.attempt
      // tests that termination is successful even when flatMapped stream is hung
      runLog {
        s1.get
          .covary[IO]
          .interruptWhen(interrupt)
          .flatMap(_ => Stream.eval_(s.acquire))
      } shouldBe Vector()

    }

    "interrupt (13)" in forAll { (s1: PureStream[Int], f: Failure) =>
      // tests that failure from the interrupt signal stream will be propagated to main stream
      // even when flatMap stream is hung
      val s = Semaphore[IO](0).unsafeRunSync()
      val interrupt =
        Stream.sleep_[IO](50.millis) ++ f.get.map { _ =>
          false
        }
      val prg = (Stream(1) ++ s1.get)
        .covary[IO]
        .interruptWhen(interrupt)
        .flatMap(_ => Stream.eval_(s.acquire))
      val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
      if (throws) an[Err] should be thrownBy runLog(prg)
      else runLog(prg)

    }

    "interrupt (14)" in forAll { s1: PureStream[Int] =>
      // tests that when interrupted, the interruption will resume with append.

      val interrupt =
        IO.sleep(50.millis).attempt
      val prg = (
        (s1.get.covary[IO].interruptWhen(interrupt).evalMap { _ =>
          IO.never.as(None)
        })
          ++ (s1.get.map(Some(_)))
      ).collect { case Some(v) => v }

      runLog(prg) shouldBe runLog(s1.get)

    }

    "interrupt (15)" in forAll { s1: PureStream[Int] =>
      // tests that interruption works even when flatMap is followed by `collect`
      // also tests scenario when interrupted stream is followed by other stream and both have map fusion defined

      val interrupt =
        Stream.sleep_[IO](20.millis).compile.drain.attempt

      val prg =
        ((s1.get.covary[IO] ++ Stream(1)).interruptWhen(interrupt).map { i =>
          None
        } ++ s1.get.map(Some(_)))
          .flatMap {
            case None    => Stream.eval(IO.never)
            case Some(i) => Stream.emit(Some(i))
          }
          .collect { case Some(i) => i }

      runLog(prg) shouldBe runLog(s1.get)

    }

    "interrupt (16)" in {
      // this tests that if the pipe1 accumulating results that is interrupted
      // will not restart evaluation ignoring the previous results
      def p: Pipe[IO, Int, Int] = {
        def loop(acc: Int, s: Stream[IO, Int]): Pull[IO, Int, Unit] =
          s.pull.uncons1.flatMap {
            case None           => Pull.output1[IO, Int](acc)
            case Some((hd, tl)) => Pull.output1[IO, Int](hd) >> loop(acc + hd, tl)
          }
        in =>
          loop(0, in).stream
      }

      val result: Vector[Int] =
        runLog(
          Stream
            .unfold(0)(i => Some((i, i + 1)))
            .flatMap(Stream.emit(_).delayBy[IO](10.millis))
            .interruptWhen(Stream.emit(true).delayBy[IO](150.millis))
            .through(p)
        )

      result shouldBe (result.headOption.toVector ++ result.tail.filter(_ != 0))

    }

    "interrupt (17)" in {
      // minimal test that when interrupted, the interruption will resume with append (pull.uncons case).

      def s1 = Stream(1).covary[IO].unchunk

      def interrupt = IO.sleep(100.millis).attempt

      def prg =
        s1.interruptWhen(interrupt)
          .pull
          .uncons
          .flatMap {
            case None           => Pull.done
            case Some((hd, tl)) => Pull.eval(IO.never)
          }
          .stream ++ Stream(5)

      runLog(prg) shouldBe Vector(5)

    }

    "interrupt (18)" in {
      // minimal tests that when interrupted, the interruption will resume with append (flatMap case).

      def s1 = Stream(1).covary[IO]

      def interrupt =
        IO.sleep(200.millis).attempt

      def prg =
        s1.interruptWhen(interrupt).evalMap[IO, Int](_ => IO.never) ++ Stream(5)

      runLog(prg) shouldBe Vector(5)

    }

    "interrupt (19)" in {
      // interruptible eval

      def prg =
        Deferred[IO, Unit]
          .flatMap { latch =>
            Stream
              .eval {
                latch.get.guarantee(latch.complete(()))
              }
              .interruptAfter(200.millis)
              .compile
              .drain >> latch.get.as(true)
          }
          .timeout(3.seconds)

      prg.unsafeRunSync shouldBe true
    }

    "nested-interrupt (1)" in forAll { s1: PureStream[Int] =>
      val s = Semaphore[IO](0).unsafeRunSync()
      val interrupt: IO[Either[Throwable, Unit]] =
        IO.sleep(50.millis).attempt
      val neverInterrupt = (IO.never: IO[Unit]).attempt

      val prg =
        (s1.get.covary[IO].interruptWhen(interrupt).map(_ => None) ++ s1.get
          .map(Some(_)))
          .interruptWhen(neverInterrupt)
          .flatMap {
            case None =>
              Stream.eval(s.acquire.map { _ =>
                None
              })
            case Some(i) => Stream.emit(Some(i))
          }
          .collect { case Some(i) => i }

      runLog(prg) shouldBe runLog(s1.get)

    }

    "nested-interrupt (2)" in {
      //Tests whether an interrupt in enclosing scope interrupts the inner scope.
      val prg =
        Stream
          .eval(IO.async[Unit](_ => ()))
          .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
          .interruptWhen(IO(Right(()): Either[Throwable, Unit]))

      runLog(prg) shouldBe Vector()
    }

    "nested-interrupt (3)" in {
      //Tests whether an interrupt in enclosing scope correctly recovers.
      val prg =
        (Stream
          .eval(IO.async[Unit](_ => ()))
          .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ())) ++ Stream(1))
          .interruptWhen(IO(Right(()): Either[Throwable, Unit])) ++ Stream(2)

      runLog(prg) shouldBe Vector(2)
    }

    "pause" in {
      forAll { (s1: PureStream[Int]) =>
        val pausedStream =
          Stream.eval(SignallingRef[IO, Boolean](false)).flatMap { pause =>
            Stream
              .awakeEvery[IO](10.millis)
              .scan(0)((acc, _) => acc + 1)
              .evalMap { n =>
                if (n % 2 != 0)
                  pause.set(true) >> ((Stream.sleep_[IO](10.millis) ++ Stream.eval(
                    pause.set(false))).compile.drain).start >> IO.pure(n)
                else IO.pure(n)
              }
              .take(5)
          }
        val out = runLog { pausedStream }
        assert(out == Vector(0, 1, 2, 3, 4))
      }
    }
  }
}
