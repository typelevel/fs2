package fs2

import cats.~>
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._

import scala.concurrent.duration._

class StreamSpec extends Fs2Spec {
  "Stream" - {
    "++" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      (s1 ++ s2).toList shouldBe (s1.toList ++ s2.toList)
    }

    ">>" in forAll { (s: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      (s >> s2).toList shouldBe s.flatMap(_ => s2).toList
    }

    "apply" in { Stream(1, 2, 3).toList shouldBe List(1, 2, 3) }

    "bracket" - {
      sealed trait BracketEvent
      final case object Acquired extends BracketEvent
      final case object Released extends BracketEvent

      def recordBracketEvents[F[_]](events: Ref[F, Vector[BracketEvent]]): Stream[F, Unit] =
        Stream.bracket(events.update(evts => evts :+ Acquired))(_ =>
          events.update(evts => evts :+ Released))

      "single bracket" - {
        def singleBracketTest[F[_]: Sync, A](use: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events)
              .evalMap(_ =>
                events.get.asserting { events =>
                  events shouldBe Vector(Acquired)
              })
              .flatMap(_ => use)
              .compile
              .drain
              .handleErrorWith { case t: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { _ shouldBe Vector(Acquired, Released) }
          } yield ()

        "normal termination" in { singleBracketTest[SyncIO, Unit](Stream.empty) }
        "failure" in { singleBracketTest[SyncIO, Unit](Stream.raiseError[SyncIO](new Err)) }
        "throw from append" in {
          singleBracketTest(Stream(1, 2, 3) ++ ((throw new Err): Stream[SyncIO, Int]))
        }
      }

      "bracket.scope ++ bracket" - {
        def appendBracketTest[F[_]: Sync, A](use1: Stream[F, A], use2: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events).scope
              .flatMap(_ => use1)
              .append(recordBracketEvents(events).flatMap(_ => use2))
              .compile
              .drain
              .handleErrorWith { case t: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { _ shouldBe Vector(Acquired, Released, Acquired, Released) }
          } yield ()

        "normal termination" in { appendBracketTest[SyncIO, Unit](Stream.empty, Stream.empty) }
        "failure" in {
          appendBracketTest[SyncIO, Unit](Stream.empty, Stream.raiseError[SyncIO](new Err))
        }
      }
    }

    "chunk" in {
      forAll { (c: Chunk[Int]) =>
        Stream.chunk(c).toChunk shouldBe c
      }
    }

    "duration" in {
      val delay = 200.millis
      Stream
        .emit(())
        .append(Stream.eval(IO(Thread.sleep(delay.toMillis))))
        .zip(Stream.duration[IO])
        .drop(1)
        .map(_._2)
        .compile
        .toVector
        .asserting { result =>
          result should have size (1)
          val head = result.head
          head.toMillis should be >= (delay.toMillis - 5)
        }
    }

    "eval" in { Stream.eval(SyncIO(23)).compile.toList.asserting(_ shouldBe List(23)) }

    "every" in {
      flickersOnTravis
      type BD = (Boolean, FiniteDuration)
      val durationSinceLastTrue: Pipe[Pure, BD, BD] = {
        def go(lastTrue: FiniteDuration, s: Stream[Pure, BD]): Pull[Pure, BD, Unit] =
          s.pull.uncons1.flatMap {
            case None => Pull.done
            case Some((pair, tl)) =>
              pair match {
                case (true, d) =>
                  Pull.output1((true, d - lastTrue)) >> go(d, tl)
                case (false, d) =>
                  Pull.output1((false, d - lastTrue)) >> go(lastTrue, tl)
              }
          }
        s =>
          go(0.seconds, s).stream
      }

      val delay = 20.millis
      val draws = (600.millis / delay).min(50) // don't take forever

      val durationsSinceSpike = Stream
        .every[IO](delay)
        .map(d => (d, System.nanoTime.nanos))
        .take(draws.toInt)
        .through(durationSinceLastTrue)

      (IO.shift >> durationsSinceSpike.compile.toVector).unsafeToFuture().map { result =>
        val (head :: tail) = result.toList
        withClue("every always emits true first") { assert(head._1) }
        withClue("true means the delay has passed: " + tail) {
          assert(tail.filter(_._1).map(_._2).forall { _ >= delay })
        }
        withClue("false means the delay has not passed: " + tail) {
          assert(tail.filterNot(_._1).map(_._2).forall { _ <= delay })
        }
      }
    }

    "flatMap" in forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
      s.flatMap(inner => inner).toList shouldBe s.toList.flatMap(inner => inner.toList)
    }

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val stream: Stream[Fallible, Int] = Stream.fromEither[Fallible](either)
      either match {
        case Left(t)  => stream.toList shouldBe Left(t)
        case Right(i) => stream.toList shouldBe Right(List(i))
      }
    }

    "fromIterator" in forAll { x: List[Int] =>
      Stream
        .fromIterator[SyncIO, Int](x.iterator)
        .compile
        .toList
        .asserting(_ shouldBe x)
    }

    "handleErrorWith" - {

      "1" in forAll { (s: Stream[Pure, Int]) =>
        val s2 = s.covary[Fallible] ++ Stream.raiseError[Fallible](new Err)
        s2.handleErrorWith(_ => Stream.empty).toList shouldBe Right(s.toList)
      }

      "2" in {
        Stream.raiseError[Fallible](new Err).handleErrorWith(_ => Stream(1)).toList shouldBe Right(
          List(1))
      }

      "3" in {
        Stream(1)
          .append(Stream.raiseError[Fallible](new Err))
          .handleErrorWith(_ => Stream(1))
          .toList shouldBe Right(List(1, 1))
      }

      "4 - error in eval" in {
        Stream
          .eval(SyncIO(throw new Err))
          .map(Right(_): Either[Throwable, Int])
          .handleErrorWith(t => Stream.emit(Left(t)).covary[SyncIO])
          .take(1)
          .compile
          .toVector
          .asserting(_.head.swap.toOption.get shouldBe an[Err])
      }

      "5" in {
        Stream
          .raiseError[SyncIO](new Err)
          .handleErrorWith(e => Stream(e))
          .flatMap(Stream.emit)
          .compile
          .toVector
          .asserting { v =>
            v should have size (1)
            v.head shouldBe an[Err]
          }
      }

      "6" in {
        Stream
          .raiseError[IO](new Err)
          .handleErrorWith(Stream.emit)
          .map(identity)
          .compile
          .toVector
          .asserting { v =>
            v should have size (1)
            v.head shouldBe an[Err]
          }
      }

      "7 - parJoin" in {
        Stream(Stream.emit(1).covary[IO], Stream.raiseError[IO](new Err), Stream.emit(2).covary[IO])
          .covary[IO]
          .parJoin(4)
          .attempt
          .compile
          .toVector
          .asserting(_.collect { case Left(t) => t }
            .find(_.isInstanceOf[Err])
            .isDefined shouldBe true)
      }
    }

    "interleave" - {
      "interleave left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.interleave(s).toList shouldBe List("1", "A", "1", "B", "1", "C")
        s.interleave(ones).toList shouldBe List("A", "1", "B", "1", "C", "1")
      }

      "interleave both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.interleave(as).take(3).toList shouldBe List("1", "A", "1")
        as.interleave(ones).take(3).toList shouldBe List("A", "1", "A")
      }

      "interleaveAll left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.interleaveAll(s).take(9).toList shouldBe List("1",
                                                           "A",
                                                           "1",
                                                           "B",
                                                           "1",
                                                           "C",
                                                           "1",
                                                           "1",
                                                           "1")
        s.interleaveAll(ones).take(9).toList shouldBe List("A",
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
        ones.interleaveAll(as).take(3).toList shouldBe List("1", "A", "1")
        as.interleaveAll(ones).take(3).toList shouldBe List("A", "1", "A")
      }

      // Uses a small scope to avoid using time to generate too large streams and not finishing
      "interleave is equal to interleaveAll on infinite streams (by step-indexing)" in {
        forAll(intsBetween(0, 100)) { (n: Int) =>
          val ones = Stream.constant("1")
          val as = Stream.constant("A")
          ones.interleaveAll(as).take(n).toVector shouldBe ones
            .interleave(as)
            .take(n)
            .toVector
        }
      }
    }

    "interrupt" - {
      val interruptRepeatCount = if (isJVM) 25 else 1

      "1 - can interrupt a hung eval" in {
        forAll { (s: Stream[Pure, Int]) =>
          val interruptSoon = Stream.sleep_[IO](50.millis).compile.drain.attempt
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              s.covary[IO].evalMap(_ => semaphore.acquire).interruptWhen(interruptSoon)
            }
            .compile
            .toList
            .asserting(_ shouldBe Nil)
        }
      }

      "2 - termination successful when stream doing interruption is hung" in {
        forAll { (s: Stream[Pure, Int]) =>
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              val interrupt = Stream.emit(true) ++ Stream.eval_(semaphore.release)
              s.covary[IO].evalMap(_ => semaphore.acquire).interruptWhen(interrupt)
            }
            .compile
            .toList
            .asserting(_ shouldBe Nil)
        }
      }

      "3 - constant stream" in {
        val interruptSoon = Stream.sleep_[IO](20.millis).compile.drain.attempt
        Stream
          .constant(true)
          .covary[IO]
          .interruptWhen(interruptSoon)
          .compile
          .drain
          .assertNoException
          .repeatTest(interruptRepeatCount)
      }

      "4 - interruption of constant stream with a flatMap" in {
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt
        Stream
          .constant(true)
          .covary[IO]
          .interruptWhen(interrupt)
          .flatMap(_ => Stream.emit(1))
          .compile
          .drain
          .assertNoException
          .repeatTest(interruptRepeatCount)
      }

      "5 - interruption of an infinitely recursive stream" in {
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt

        def loop(i: Int): Stream[IO, Int] = Stream.emit(i).covary[IO].flatMap { i =>
          Stream.emit(i) ++ loop(i + 1)
        }

        loop(0)
          .interruptWhen(interrupt)
          .compile
          .drain
          .assertNoException
          .repeatTest(interruptRepeatCount)
      }

      "6 - interruption of an infinitely recursive stream that never emits" in {
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt

        def loop: Stream[IO, Int] =
          Stream.eval(IO.unit) >> loop

        loop
          .interruptWhen(interrupt)
          .compile
          .drain
          .assertNoException
          .repeatTest(interruptRepeatCount)
      }

      "7 - interruption of an infinitely recursive stream that never emits and has no eval" in {
        val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
        def loop: Stream[IO, Int] = Stream.emit(()).covary[IO] >> loop
        loop
          .interruptWhen(interrupt)
          .compile
          .drain
          .assertNoException
          .repeatTest(interruptRepeatCount)
      }

      "8 - interruption of a stream that repeatedly evaluates" in {
        val interrupt =
          Stream.sleep_[IO](20.millis).compile.drain.attempt
        Stream
          .repeatEval(IO.unit)
          .interruptWhen(interrupt)
          .compile
          .drain
          .assertNoException
          .repeatTest(interruptRepeatCount)
      }

      "9 - interruption of the constant drained stream" in {
        val interrupt =
          Stream.sleep_[IO](1.millis).compile.drain.attempt
        Stream
          .constant(true)
          .dropWhile(!_)
          .covary[IO]
          .interruptWhen(interrupt)
          .compile
          .drain
          .assertNoException
          .repeatTest(interruptRepeatCount)
      }

      "10 - terminates when interruption stream is infinitely false" in forAll {
        (s: Stream[Pure, Int]) =>
          s.covary[IO]
            .interruptWhen(Stream.constant(false))
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
      }

      "11 - both streams hung" in forAll { (s: Stream[Pure, Int]) =>
        Stream
          .eval(Semaphore[IO](0))
          .flatMap { barrier =>
            Stream.eval(Semaphore[IO](0)).flatMap { enableInterrupt =>
              val interrupt = Stream.eval(enableInterrupt.acquire) >> Stream.emit(false)
              s.covary[IO]
                .evalMap { i =>
                  // enable interruption and hang when hitting a value divisible by 7
                  if (i % 7 == 0) enableInterrupt.release.flatMap { _ =>
                    barrier.acquire.as(i)
                  } else IO.pure(i)
                }
                .interruptWhen(interrupt)
            }
          }
          .compile
          .toList
          .asserting { result =>
            // as soon as we hit a value divisible by 7, we enable interruption then hang before emitting it,
            // so there should be no elements in the output that are divisible by 7
            // this also checks that interruption works fine even if one or both streams are in a hung state
            result.forall(i => i % 7 != 0) shouldBe true
          }
      }

      "12 - interruption of stream that never terminates in flatMap" in {
        forAll { (s: Stream[Pure, Int]) =>
          val interrupt = Stream.sleep_[IO](50.millis).compile.drain.attempt
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              s.covary[IO].interruptWhen(interrupt) >> Stream.eval_(semaphore.acquire)
            }
            .compile
            .toList
            .asserting(_ shouldBe Nil)
        }
      }

      "12a - minimal interruption of stream that never terminates in flatMap" in {
        Stream(1)
          .covary[IO]
          .interruptWhen(IO.sleep(10.millis).attempt)
          .flatMap(_ => Stream.eval(IO.never))
          .compile
          .drain
          .assertNoException
      }

      "13 - failure from interruption signal will be propagated to main stream even when flatMap stream is hung" in {
        forAll { (s: Stream[Pure, Int]) =>
          val interrupt = Stream.sleep_[IO](50.millis) ++ Stream.raiseError[IO](new Err)
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              Stream(1)
                .append(s)
                .covary[IO]
                .interruptWhen(interrupt)
                .flatMap(_ => Stream.eval_(semaphore.acquire))
            }
            .compile
            .toList
            .assertThrows[Err]
        }
      }

      "14 - minimal resume on append" in {
        Stream
          .eval(IO.never)
          .interruptWhen(IO.sleep(10.millis).attempt)
          .append(Stream(5))
          .compile
          .toList
          .asserting(_ shouldBe List(5))
      }

      "14a - interrupt evalMap and then resume on append" in {
        pending // Broken
        forAll { s: Stream[Pure, Int] =>
          val interrupt = IO.sleep(50.millis).attempt
          s.covary[IO]
            .interruptWhen(interrupt)
            .evalMap(_ => IO.never)
            .drain
            .append(s)
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
        }
      }

      "14b - interrupt evalMap+collect and then resume on append" in {
        pending // Broken
        forAll { s: Stream[Pure, Int] =>
          val interrupt = IO.sleep(50.millis).attempt
          s.covary[IO]
            .interruptWhen(interrupt)
            .evalMap(_ => IO.never.as(None))
            .append(s.map(Some(_)))
            .collect { case Some(v) => v }
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
        }
      }

      "15 - interruption works when flatMap is followed by collect" in {
        pending // Broken - results in an empty list instead of s.toList
        forAll { s: Stream[Pure, Int] =>
          val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
          s.covary[IO]
            .append(Stream(1))
            .interruptWhen(interrupt)
            .map(i => None)
            .append(s.map(Some(_)))
            .flatMap {
              case None    => Stream.eval(IO.never)
              case Some(i) => Stream.emit(Some(i))
            }
            .collect { case Some(i) => i }
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
        }
      }

      "16 - if a pipe is interrupted, it will not restart evaluation" in {
        def p: Pipe[IO, Int, Int] = {
          def loop(acc: Int, s: Stream[IO, Int]): Pull[IO, Int, Unit] =
            s.pull.uncons1.flatMap {
              case None           => Pull.output1[IO, Int](acc)
              case Some((hd, tl)) => Pull.output1[IO, Int](hd) >> loop(acc + hd, tl)
            }
          in =>
            loop(0, in).stream
        }
        Stream
          .unfold(0)(i => Some((i, i + 1)))
          .flatMap(Stream.emit(_).delayBy[IO](10.millis))
          .interruptWhen(Stream.emit(true).delayBy[IO](150.millis))
          .through(p)
          .compile
          .toList
          .asserting(result =>
            result shouldBe (result.headOption.toList ++ result.tail.filter(_ != 0)))
      }

      "17 - minimal resume on append with pull" in {
        pending // Broken
        val interrupt = IO.sleep(100.millis).attempt
        Stream(1)
          .covary[IO]
          .unchunk
          .interruptWhen(interrupt)
          .pull
          .uncons
          .flatMap {
            case None           => Pull.done
            case Some((hd, tl)) => Pull.eval(IO.never)
          }
          .stream
          .append(Stream(5))
          .compile
          .toList
          .asserting(_ shouldBe List(5))
      }

      "18 - resume with append after evalMap interruption" in {
        pending // Broken
        Stream(1)
          .covary[IO]
          .interruptWhen(IO.sleep(50.millis).attempt)
          .evalMap(_ => IO.never)
          .append(Stream(5))
          .compile
          .toList
          .asserting(_ shouldBe List(5))
      }

      "19 - interrupted eval is cancelled" in {
        Deferred[IO, Unit]
          .flatMap { latch =>
            Stream
              .eval(latch.get.guarantee(latch.complete(())))
              .interruptAfter(200.millis)
              .compile
              .drain >> latch.get.as(true)
          }
          .timeout(3.seconds)
          .assertNoException
      }

      "20 - nested-interrupt" in {
        pending // Results in an empty list
        forAll { s: Stream[Pure, Int] =>
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              val interrupt = IO.sleep(50.millis).attempt
              val neverInterrupt = (IO.never: IO[Unit]).attempt
              s.covary[IO]
                .interruptWhen(interrupt)
                .as(None)
                .append(s.map(Option(_)))
                .interruptWhen(neverInterrupt)
                .flatMap {
                  case None    => Stream.eval(semaphore.acquire.as(None))
                  case Some(i) => Stream(Some(i))
                }
                .collect { case Some(i) => i }
            }
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
        }
      }

      "21 - nested-interrupt - interrupt in outer scope interrupts the inner scope" in {
        Stream
          .eval(IO.async[Unit](_ => ()))
          .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
          .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
          .compile
          .toList
          .asserting(_ shouldBe Nil)
      }

      "22 - nested-interrupt - interrupt in enclosing scope recovers" in {
        Stream
          .eval(IO.async[Unit](_ => ()))
          .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
          .append(Stream(1).delayBy[IO](10.millis))
          .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
          .append(Stream(2))
          .compile
          .toList
          .asserting(_ shouldBe List(2))
      }
    }

    "iterate" in {
      Stream.iterate(0)(_ + 1).take(100).toList shouldBe List.iterate(0, 100)(_ + 1)
    }

    "iterateEval" in {
      Stream
        .iterateEval(0)(i => IO(i + 1))
        .take(100)
        .compile
        .toVector
        .asserting(_ shouldBe List.iterate(0, 100)(_ + 1))
    }

    "map" - {
      "map.toList == toList.map" in forAll { (s: Stream[Pure, Int], f: Int => Int) =>
        s.map(f).toList shouldBe s.toList.map(f)
      }

      "regression #1335 - stack safety of map" in {

        case class Tree[A](label: A, subForest: Stream[Pure, Tree[A]]) {
          def flatten: Stream[Pure, A] =
            Stream(this.label) ++ this.subForest.flatMap(_.flatten)
        }

        def unfoldTree(seed: Int): Tree[Int] =
          Tree(seed, Stream(seed + 1).map(unfoldTree))

        unfoldTree(1).flatten.take(10).toList shouldBe List.tabulate(10)(_ + 1)
      }
    }

    "observeEither" - {
      val s = Stream.emits(Seq(Left(1), Right("a"))).repeat.covary[IO]

      "does not drop elements" in {
        val is = Ref.of[IO, Vector[Int]](Vector.empty)
        val as = Ref.of[IO, Vector[String]](Vector.empty)
        val test = for {
          iref <- is
          aref <- as
          iSink = (_: Stream[IO, Int]).evalMap(i => iref.update(_ :+ i))
          aSink = (_: Stream[IO, String]).evalMap(a => aref.update(_ :+ a))
          _ <- s.take(10).observeEither(iSink, aSink).compile.drain
          iResult <- iref.get
          aResult <- aref.get
        } yield {
          assert(iResult.length == 5)
          assert(aResult.length == 5)
        }
        test.assertNoException
      }

      "termination" - {

        "left" in {
          s.observeEither[Int, String](_.take(0).void, _.void)
            .compile
            .toList
            .asserting(r => (r should have).length(0))
        }

        "right" in {
          s.observeEither[Int, String](_.void, _.take(0).void)
            .compile
            .toList
            .asserting(r => (r should have).length(0))
        }
      }
    }

    "raiseError" - {
      "compiled stream fails with an error raised in stream" in {
        Stream.raiseError[SyncIO](new Err).compile.drain.assertThrows[Err]
      }

      "compiled stream fails with an error if error raised after an append" in {
        Stream
          .emit(1)
          .append(Stream.raiseError[IO](new Err))
          .covary[IO]
          .compile
          .drain
          .assertThrows[Err]
      }

      "compiled stream does not fail if stream is termianted before raiseError" in {
        Stream
          .emit(1)
          .append(Stream.raiseError[IO](new Err))
          .take(1)
          .covary[IO]
          .compile
          .drain
          .assertNoException
      }
    }

    "random" in {
      val x = Stream.random[SyncIO].take(100).compile.toList
      (x, x).tupled.asserting {
        case (first, second) =>
          first should not be second
      }
    }

    "randomSeeded" in {
      val x = Stream.randomSeeded(1L).take(100).toList
      val y = Stream.randomSeeded(1L).take(100).toList
      x shouldBe y
    }

    "range" in {
      Stream.range(0, 100).toList shouldBe List.range(0, 100)
      Stream.range(0, 1).toList shouldBe List.range(0, 1)
      Stream.range(0, 0).toList shouldBe List.range(0, 0)
      Stream.range(0, 101, 2).toList shouldBe List.range(0, 101, 2)
      Stream.range(5, 0, -1).toList shouldBe List.range(5, 0, -1)
      Stream.range(5, 0, 1).toList shouldBe Nil
      Stream.range(10, 50, 0).toList shouldBe Nil
    }

    "ranges" in forAll(intsBetween(1, 101)) { size =>
      Stream
        .ranges(0, 100, size)
        .flatMap { case (i, j) => Stream.emits(i until j) }
        .toVector shouldBe IndexedSeq.range(0, 100)
    }

    "repartition" in {
      Stream("Lore", "m ip", "sum dolo", "r sit amet")
        .repartition(s => Chunk.array(s.split(" ")))
        .toList shouldBe
        List("Lorem", "ipsum", "dolor", "sit", "amet")
      Stream("hel", "l", "o Wor", "ld")
        .repartition(s => Chunk.indexedSeq(s.grouped(2).toVector))
        .toList shouldBe
        List("he", "ll", "o ", "Wo", "rl", "d")
      Stream.empty
        .covaryOutput[String]
        .repartition(_ => Chunk.empty)
        .toList shouldBe List()
      Stream("hello").repartition(_ => Chunk.empty).toList shouldBe List()

      def input = Stream("ab").repeat
      def ones(s: String) = Chunk.vector(s.grouped(1).toVector)
      input.take(2).repartition(ones).toVector shouldBe Vector("a", "b", "a", "b")
      input.take(4).repartition(ones).toVector shouldBe Vector("a",
                                                               "b",
                                                               "a",
                                                               "b",
                                                               "a",
                                                               "b",
                                                               "a",
                                                               "b")
      input.repartition(ones).take(2).toVector shouldBe Vector("a", "b")
      input.repartition(ones).take(4).toVector shouldBe Vector("a", "b", "a", "b")
      Stream
        .emits(input.take(4).toVector)
        .repartition(ones)
        .toVector shouldBe Vector("a", "b", "a", "b", "a", "b", "a", "b")

      Stream(1, 2, 3, 4, 5).repartition(i => Chunk(i, i)).toList shouldBe List(1, 3, 6, 10, 15, 15)

      Stream(1, 10, 100)
        .repartition(i => Chunk.seq(1 to 1000))
        .take(4)
        .toList shouldBe List(1, 2, 3, 4)
    }

    "repeat" in {
      forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
        (n: Int, testValues: List[Int]) =>
          Stream.emits(testValues).repeat.take(n).toList shouldBe List
            .fill(n / testValues.size + 1)(testValues)
            .flatten
            .take(n)
      }
    }

    "repeatN" in {
      forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
        (n: Int, testValues: List[Int]) =>
          Stream.emits(testValues).repeatN(n).toList shouldBe List.fill(n)(testValues).flatten
      }
    }

    "scope" - {
      "1" in {
        val c = new java.util.concurrent.atomic.AtomicLong(0)
        val s1 = Stream.emit("a").covary[IO]
        val s2 = Stream
          .bracket(IO { c.incrementAndGet() shouldBe 1L; () }) { _ =>
            IO { c.decrementAndGet(); () }
          }
          .flatMap(_ => Stream.emit("b"))
        (s1.scope ++ s2)
          .take(2)
          .scope
          .repeat
          .take(4)
          .merge(Stream.eval_(IO.unit))
          .compile
          .drain
          .asserting(_ => c.get shouldBe 0L)
      }

      "2" in {
        Stream
          .eval(Ref.of[IO, Int](0))
          .flatMap { ref =>
            Stream(1).flatMap { i =>
              Stream
                .bracket(ref.update(_ + 1))(_ => ref.update(_ - 1))
                .flatMap(_ => Stream.eval(ref.get)) ++ Stream.eval(ref.get)
            }.scope ++ Stream.eval(ref.get)
          }
          .compile
          .toList
          .asserting(_ shouldBe List(1, 1, 0))
      }
    }

    "translate" - {
      "1 - id" in forAll { (s: Stream[Pure, Int]) =>
        s.covary[SyncIO]
          .flatMap(i => Stream.eval(SyncIO.pure(i)))
          .translate(cats.arrow.FunctionK.id[SyncIO])
          .compile
          .toList
          .asserting(_ shouldBe s.toList)
      }

      "2" in forAll { (s: Stream[Pure, Int]) =>
        s.covary[Function0]
          .flatMap(i => Stream.eval(() => i))
          .flatMap(i => Stream.eval(() => i))
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(_ shouldBe s.toList)
      }

      "3 - ok to have multiple translates" in forAll { (s: Stream[Pure, Int]) =>
        s.covary[Function0]
          .flatMap(i => Stream.eval(() => i))
          .flatMap(i => Stream.eval(() => i))
          .translate(new (Function0 ~> Some) {
            def apply[A](thunk: Function0[A]) = Some(thunk())
          })
          .flatMap(i => Stream.eval(Some(i)))
          .flatMap(i => Stream.eval(Some(i)))
          .translate(new (Some ~> SyncIO) {
            def apply[A](some: Some[A]) = SyncIO(some.get)
          })
          .compile
          .toList
          .asserting(_ shouldBe s.toList)
      }

      "4 - ok to translate after zip with effects" in {
        val stream: Stream[Function0, Int] =
          Stream.eval(() => 1)
        stream
          .zip(stream)
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(_ shouldBe List((1, 1)))
      }

      "5 - ok to translate a step leg that emits multiple chunks" in {
        def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
          step match {
            case None       => Pull.done
            case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
          }
        (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
          .flatMap(goStep)
          .stream
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(_ shouldBe List(1, 2))
      }

      "6 - ok to translate step leg that has uncons in its structure" in {
        def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
          step match {
            case None       => Pull.done
            case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
          }
        (Stream.eval(() => 1) ++ Stream.eval(() => 2))
          .flatMap { a =>
            Stream.emit(a)
          }
          .flatMap { a =>
            Stream.eval(() => a + 1) ++ Stream.eval(() => a + 2)
          }
          .pull
          .stepLeg
          .flatMap(goStep)
          .stream
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(_ shouldBe List(2, 3, 3, 4))
      }

      "7 - ok to translate step leg that is forced back in to a stream" in {
        def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
          step match {
            case None => Pull.done
            case Some(step) =>
              Pull.output(step.head) >> step.stream.pull.echo
          }
        (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
          .flatMap(goStep)
          .stream
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(_ shouldBe List(1, 2))
      }

      "stack safety" in {
        Stream
          .repeatEval(SyncIO(0))
          .translate(new (SyncIO ~> SyncIO) { def apply[X](x: SyncIO[X]) = SyncIO.suspend(x) })
          .take(1000000)
          .compile
          .drain
          .assertNoException
      }
    }

    "unfold" in {
      Stream
        .unfold((0, 1)) {
          case (f1, f2) =>
            if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
        }
        .map(_._1)
        .toList shouldBe List(0, 1, 1, 2, 3, 5, 8, 13)
    }

    "unfoldChunk" in {
      Stream
        .unfoldChunk(4L) { s =>
          if (s > 0) Some((Chunk.longs(Array[Long](s, s)), s - 1))
          else None
        }
        .toList shouldBe List[Long](4, 4, 3, 3, 2, 2, 1, 1)
    }

    "unfoldEval" in {
      Stream
        .unfoldEval(10)(s => IO.pure(if (s > 0) Some((s, s - 1)) else None))
        .compile
        .toList
        .asserting(_ shouldBe List.range(10, 0, -1))
    }

    "unfoldChunkEval" in {
      Stream
        .unfoldChunkEval(true)(s =>
          SyncIO.pure(if (s) Some((Chunk.booleans(Array[Boolean](s)), false)) else None))
        .compile
        .toList
        .asserting(_ shouldBe List(true))
    }

    "zip" - {
      "propagate error from closing the root scope" in {
        val s1 = Stream.bracket(IO(1))(_ => IO.unit)
        val s2 = Stream.bracket(IO("a"))(_ => IO.raiseError(new Err))

        val r1 = s1.zip(s2).compile.drain.attempt.unsafeRunSync()
        r1.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
        val r2 = s2.zip(s1).compile.drain.attempt.unsafeRunSync()
        r2.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
      }

      "issue #941 - scope closure issue" in {
        Stream(1, 2, 3)
          .map(_ + 1)
          .repeat
          .zip(Stream(4, 5, 6).map(_ + 1).repeat)
          .take(4)
          .toList shouldBe List((2, 5), (3, 6), (4, 7), (2, 5))
      }

      "zipWith left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zipWith(s)(_ + _).toList shouldBe List("1A", "1B", "1C")
        s.zipWith(ones)(_ + _).toList shouldBe List("A1", "B1", "C1")
      }

      "zipWith both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zipWith(as)(_ + _).take(3).toList shouldBe List("1A", "1A", "1A")
        as.zipWith(ones)(_ + _).take(3).toList shouldBe List("A1", "A1", "A1")
      }

      "zipAllWith left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zipAllWith(s)("2", "Z")(_ + _).take(5).toList shouldBe
          List("1A", "1B", "1C", "1Z", "1Z")
        s.zipAllWith(ones)("Z", "2")(_ + _).take(5).toList shouldBe
          List("A1", "B1", "C1", "Z1", "Z1")
      }

      "zipAllWith both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zipAllWith(as)("2", "Z")(_ + _).take(3).toList shouldBe
          List("1A", "1A", "1A")
        as.zipAllWith(ones)("Z", "2")(_ + _).take(3).toList shouldBe
          List("A1", "A1", "A1")
      }

      "zip left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zip(s).toList shouldBe List("1" -> "A", "1" -> "B", "1" -> "C")
        s.zip(ones).toList shouldBe List("A" -> "1", "B" -> "1", "C" -> "1")
      }

      "zip both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zip(as).take(3).toList shouldBe List("1" -> "A", "1" -> "A", "1" -> "A")
        as.zip(ones).take(3).toList shouldBe List("A" -> "1", "A" -> "1", "A" -> "1")
      }

      "zipAll left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zipAll(s)("2", "Z").take(5).toList shouldBe List("1" -> "A",
                                                              "1" -> "B",
                                                              "1" -> "C",
                                                              "1" -> "Z",
                                                              "1" -> "Z")
        s.zipAll(ones)("Z", "2").take(5).toList shouldBe List("A" -> "1",
                                                              "B" -> "1",
                                                              "C" -> "1",
                                                              "Z" -> "1",
                                                              "Z" -> "1")
      }

      "zipAll both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zipAll(as)("2", "Z").take(3).toList shouldBe List("1" -> "A", "1" -> "A", "1" -> "A")
        as.zipAll(ones)("Z", "2").take(3).toList shouldBe List("A" -> "1", "A" -> "1", "A" -> "1")
      }

      "zip with scopes" in {
        // this tests that streams opening resources on each branch will close
        // scopes independently.
        val s = Stream(0).scope
        (s ++ s).zip(s).toList shouldBe List((0, 0))
      }

      "issue #1120 - zip with uncons" in {
        // this tests we can properly look up scopes for the zipped streams
        val rangeStream = Stream.emits((0 to 3).toList)
        rangeStream.zip(rangeStream).attempt.map(identity).toVector shouldBe Vector(
          Right((0, 0)),
          Right((1, 1)),
          Right((2, 2)),
          Right((3, 3))
        )
      }
    }

    "regressions" - {

      "#1089" in {
        (Stream.chunk(Chunk.bytes(Array.fill(2000)(1.toByte))) ++ Stream.eval(
          IO.async[Byte](_ => ())))
          .take(2000)
          .chunks
          .compile
          .toVector
          .assertNoException
      }

      "#1107 - scope" in {
        Stream(0)
          .covary[IO]
          .scope // Create a source that opens/closes a new scope for every element emitted
          .repeat
          .take(10000)
          .flatMap(_ => Stream.empty) // Never emit an element downstream
          .mapChunks(identity) // Use a combinator that calls Stream#pull.uncons
          .compile
          .drain
          .assertNoException
      }

      "#1107 - queue" in {
        Stream
          .range(0, 10000)
          .covary[IO]
          .unchunk
          .prefetch
          .flatMap(_ => Stream.empty)
          .mapChunks(identity)
          .compile
          .drain
          .assertNoException
      }
    }
  }
}
