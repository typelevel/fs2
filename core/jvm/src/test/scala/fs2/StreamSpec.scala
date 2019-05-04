package fs2

import cats.{Eq, ~>}
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import cats.implicits._
import cats.laws.discipline._
import org.scalacheck.{Arbitrary, Cogen, Gen}
import Arbitrary.arbitrary
import org.scalatest.Inside

import scala.concurrent.duration._
import TestUtil._

class StreamSpec extends Fs2Spec with Inside {

  "Stream" - {

    "chunk" in forAll { (c: Vector[Int]) =>
      runLog(Stream.chunk(Chunk.seq(c))) shouldBe c
    }

    "fail (1)" in forAll { (f: Failure) =>
      an[Err] should be thrownBy f.get.compile.drain.unsafeRunSync()
    }

    "fail (2)" in {
      assertThrows[Err] { Stream.raiseError[IO](new Err).compile.drain.unsafeRunSync }
    }

    "fail (3)" in {
      assertThrows[Err] {
        (Stream.emit(1) ++ Stream.raiseError[IO](new Err))
          .covary[IO]
          .compile
          .drain
          .unsafeRunSync
      }
    }

    "eval" in {
      runLog(Stream.eval(IO(23))) shouldBe Vector(23)
    }

    "++" in forAll { (s: PureStream[Int], s2: PureStream[Int]) =>
      runLog(s.get ++ s2.get) shouldBe { runLog(s.get) ++ runLog(s2.get) }
    }

    "flatMap" in forAll { (s: PureStream[PureStream[Int]]) =>
      runLog(s.get.flatMap(inner => inner.get)) shouldBe {
        runLog(s.get).flatMap(inner => runLog(inner.get))
      }
    }

    ">>" in forAll { (s: PureStream[Int], s2: PureStream[Int]) =>
      runLog(s.get >> s2.get) shouldBe { runLog(s.get.flatMap(_ => s2.get)) }
    }

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val stream: Stream[IO, Int] = Stream.fromEither[IO](either)

      either match {
        case Left(_) => stream.compile.toList.attempt.unsafeRunSync() shouldBe either
        case Right(_) => stream.compile.toList.unsafeRunSync() shouldBe either.toList
      }
    }

    "fromIterator" in forAll { vec: Vector[Int] =>
      val iterator = vec.iterator
      val stream = Stream.fromIterator[IO, Int](iterator)
      val example = stream.compile.toVector.unsafeRunSync
      example shouldBe vec
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
        .unsafeRunSync() shouldBe List.iterate(0, 100)(_ + 1)
    }

    "map" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.map(identity)) shouldBe runLog(s.get)
    }

    "handleErrorWith (1)" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        val s2 = s.get ++ f.get
        runLog(s2.handleErrorWith(_ => Stream.empty)) shouldBe runLog(s.get)
      }
    }

    "handleErrorWith (2)" in {
      runLog(Stream.raiseError[IO](new Err).handleErrorWith { _ =>
        Stream.emit(1)
      }) shouldBe Vector(1)
    }

    "handleErrorWith (3)" in {
      runLog((Stream.emit(1) ++ Stream.raiseError[IO](new Err)).handleErrorWith { _ =>
        Stream.emit(1)
      }) shouldBe Vector(1, 1)
    }

    "handleErrorWith (4)" in {
      val r = Stream
        .eval(IO(throw new Err))
        .map(Right(_): Either[Throwable, Int])
        .handleErrorWith(t => Stream.emit(Left(t)).covary[IO])
        .take(1)
        .compile
        .toVector
        .unsafeRunSync()
      r.foreach(_.swap.toOption.get shouldBe an[Err])
    }

    "handleErrorWith (5)" in {
      val r = Stream
        .raiseError[IO](new Err)
        .covary[IO]
        .handleErrorWith(e => Stream.emit(e))
        .flatMap(Stream.emit(_))
        .compile
        .toVector
        .unsafeRunSync()
      val r2 = Stream
        .raiseError[IO](new Err)
        .covary[IO]
        .handleErrorWith(e => Stream.emit(e))
        .map(identity)
        .compile
        .toVector
        .unsafeRunSync()
      val r3 =
        Stream(Stream.emit(1).covary[IO], Stream.raiseError[IO](new Err), Stream.emit(2).covary[IO])
          .covary[IO]
          .parJoin(4)
          .attempt
          .compile
          .toVector
          .unsafeRunSync()
      r should have size (1)
      r.head shouldBe an[Err]
      r2 should have size (1)
      r2.head shouldBe an[Err]
      r3.collect { case Left(t) => t }.find(_.isInstanceOf[Err]).isDefined shouldBe true
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

    "ranges" in forAll(Gen.choose(1, 101)) { size =>
      Stream
        .ranges(0, 100, size)
        .covary[IO]
        .flatMap { case (i, j) => Stream.emits(i until j) }
        .compile
        .toVector
        .unsafeRunSync() shouldBe
        IndexedSeq.range(0, 100)
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

    "translate" in forAll { (s: PureStream[Int]) =>
      runLog(
        s.get
          .covary[IO]
          .flatMap(i => Stream.eval(IO.pure(i)))
          .translate(cats.arrow.FunctionK.id[IO])) shouldBe
        runLog(s.get)
    }

    "translate (2)" in forAll { (s: PureStream[Int]) =>
      runLog(
        s.get
          .covary[Function0]
          .flatMap(i => Stream.eval(() => i))
          .flatMap(i => Stream.eval(() => i))
          .translate(new (Function0 ~> IO) {
            def apply[A](thunk: Function0[A]) = IO(thunk())
          })
      ) shouldBe runLog(s.get)
    }

    "translate (3)" in forAll { (s: PureStream[Int]) =>
      // tests that it is ok to have multiple successive translate
      runLog(
        s.get
          .covary[Function0]
          .flatMap(i => Stream.eval(() => i))
          .flatMap(i => Stream.eval(() => i))
          .translate(new (Function0 ~> Some) {
            def apply[A](thunk: Function0[A]) = Some(thunk())
          })
          .flatMap(i => Stream.eval(Some(i)))
          .flatMap(i => Stream.eval(Some(i)))
          .translate(new (Some ~> IO) {
            def apply[A](some: Some[A]) = IO(some.get)
          })
      ) shouldBe runLog(s.get)
    }

    "translate (4)" in {
      // tests that it is ok to have translate after zip with effects

      val stream: Stream[Function0, Int] =
        Stream.eval(() => 1)

      stream
        .zip(stream)
        .translate(new (Function0 ~> IO) {
          def apply[A](thunk: Function0[A]) = IO(thunk())
        })
        .compile
        .toList
        .unsafeRunSync shouldBe List((1, 1))
    }

    "translate (5)" in {
      // tests that it is ok to have translate step leg that emits multiple chunks

      def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
        step match {
          case None       => Pull.done
          case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
        }

      (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
        .flatMap(goStep)
        .stream
        .translate(new (Function0 ~> IO) {
          def apply[A](thunk: Function0[A]) = IO(thunk())
        })
        .compile
        .toList
        .unsafeRunSync shouldBe List(1, 2)
    }

    "translate (6)" in {
      // tests that it is ok to have translate step leg that has uncons in its structure.

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
        .translate(new (Function0 ~> IO) {
          def apply[A](thunk: Function0[A]) = IO(thunk())
        })
        .compile
        .toList
        .unsafeRunSync shouldBe List(2, 3, 3, 4)
    }

    "translate (7)" in {
      // tests that it is ok to have translate step leg that is later forced back into stream

      def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
        step match {
          case None => Pull.done
          case Some(step) =>
            Pull.output(step.head) >> step.stream.pull.echo
        }

      (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
        .flatMap(goStep)
        .stream
        .translate(new (Function0 ~> IO) {
          def apply[A](thunk: Function0[A]) = IO(thunk())
        })
        .compile
        .toList
        .unsafeRunSync shouldBe List(1, 2)
    }

    "toList" in forAll { (s: PureStream[Int]) =>
      s.get.toList shouldBe runLog(s.get).toList
    }

    "toVector" in forAll { (s: PureStream[Int]) =>
      s.get.toVector shouldBe runLog(s.get)
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
        .toVector
        .unsafeRunSync()
        .toList shouldBe List.range(10, 0, -1)
    }

    "unfoldChunkEval" in {
      Stream
        .unfoldChunkEval(true)(s =>
          IO.pure(if (s) Some((Chunk.booleans(Array[Boolean](s)), false)) else None))
        .compile
        .toVector
        .unsafeRunSync()
        .toList shouldBe List(true)
    }

    "translate stack safety" in {
      Stream
        .repeatEval(IO(0))
        .translate(new (IO ~> IO) { def apply[X](x: IO[X]) = IO.suspend(x) })
        .take(1000000)
        .compile
        .drain
        .unsafeRunSync()
    }

    "duration" in {
      val delay = 200.millis

      val blockingSleep = IO { Thread.sleep(delay.toMillis) }

      val emitAndSleep = Stream.emit(()) ++ Stream.eval(blockingSleep)
      val t =
        emitAndSleep.zip(Stream.duration[IO]).drop(1).map(_._2).compile.toVector

      (IO.shift >> t).unsafeToFuture.collect {
        case Vector(d) => assert(d.toMillis >= delay.toMillis - 5)
      }
    }

    "every" in {
      pending // Too finicky on Travis
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

        test.unsafeToFuture
      }

      "termination" - {

        "left" in {
          assert(runLog(s.observeEither[Int, String](_.take(0).void, _.void)).length == 0)
        }

        "right" in {
          assert(runLog(s.observeEither[Int, String](_.void, _.take(0).void)).length == 0)
        }
      }
    }

    "issue #941 - scope closure issue" in {
      Stream(1, 2, 3)
        .map(_ + 1)
        .repeat
        .zip(Stream(4, 5, 6).map(_ + 1).repeat)
        .take(4)
        .toList
    }

    "scope" in {
      // TODO This test should be replaced with one that shows proper usecase for .scope
      val c = new java.util.concurrent.atomic.AtomicLong(0)
      val s1 = Stream.emit("a").covary[IO]
      val s2 = Stream
        .bracket(IO { c.incrementAndGet() shouldBe 1L; () }) { _ =>
          IO { c.decrementAndGet(); () }
        }
        .flatMap(_ => Stream.emit("b"))
      runLog {
        (s1.scope ++ s2).take(2).scope.repeat.take(4).merge(Stream.eval_(IO.unit))
      }
    }

    "random" in {
      val x = runLog(Stream.random[IO].take(100))
      val y = runLog(Stream.random[IO].take(100))
      x should not be y
    }

    "randomSeeded" in {
      val x = Stream.randomSeeded(1L).take(100).toList
      val y = Stream.randomSeeded(1L).take(100).toList
      x shouldBe y
    }

    "regression #1089" in {
      (Stream.chunk(Chunk.bytes(Array.fill(2000)(1.toByte))) ++ Stream.eval(
        IO.async[Byte](_ => ())))
        .take(2000)
        .chunks
        .compile
        .toVector
        .unsafeRunSync()
    }

    "regression #1107 - scope" in {
      Stream(0)
        .covary[IO]
        .scope // Create a source that opens/closes a new scope for every element emitted
        .repeat
        .take(10000)
        .flatMap(_ => Stream.empty) // Never emit an element downstream
        .mapChunks(identity) // Use a combinator that calls Stream#pull.uncons
        .compile
        .drain
        .unsafeRunSync()
    }

    "regression #1107 - queue" in {
      Stream
        .range(0, 10000)
        .covary[IO]
        .unchunk
        .prefetch
        .flatMap(_ => Stream.empty)
        .mapChunks(identity)
        .compile
        .drain
        .unsafeRunSync()
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

    "rechunkRandomlyWithSeed" - {

      "is deterministic" in forAll { (s: PureStream[Int], seed: Long) =>
        val x = runLog(s.get.rechunkRandomlyWithSeed[IO](minFactor = 0.1, maxFactor = 2.0)(seed))
        val y = runLog(s.get.rechunkRandomlyWithSeed[IO](minFactor = 0.1, maxFactor = 2.0)(seed))
        x shouldBe y
      }

      "does not drop elements" in forAll { (s: PureStream[Int], seed: Long) =>
        runLog(s.get.rechunkRandomlyWithSeed[IO](minFactor = 0.1, maxFactor = 2.0)(seed)) shouldBe s.get.toVector
      }

      "chunk size in interval [inputChunk.size * minFactor, inputChunk.size * maxFactor]" in forAll {
        (s: PureStream[Int], seed: Long) =>
          val c = s.get.chunks.toVector
          if (c.nonEmpty) {
            val (min, max) = c.tail.foldLeft(c.head.size -> c.head.size) {
              case ((min, max), c) => Math.min(min, c.size) -> Math.max(max, c.size)
            }
            val (minChunkSize, maxChunkSize) = (min * 0.1, max * 2.0)
            // Last element is drop as it may not fulfill size constraint
            all(
              runLog(
                s.get
                  .rechunkRandomlyWithSeed[IO](minFactor = 0.1, maxFactor = 2.0)(seed)
                  .chunks
                  .map(_.size)).dropRight(1)
            ) should ((be >= minChunkSize.toInt).and(be <= maxChunkSize.toInt))
          }
      }

    }

    "rechunkRandomly" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.rechunkRandomly[IO]()) shouldBe s.get.toVector
    }

    {
      implicit val ec: TestContext = TestContext()

      implicit def arbStream[F[_], O](implicit arbO: Arbitrary[O],
                                      arbFo: Arbitrary[F[O]]): Arbitrary[Stream[F, O]] =
        Arbitrary(
          Gen.frequency(8 -> arbitrary[PureStream[O]].map(_.get.take(10).covary[F]),
                        2 -> arbitrary[F[O]].map(fo => Stream.eval(fo))))

      // borrowed from ScalaCheck 1.14
      // TODO remove when the project upgrades to ScalaCheck 1.14
      implicit def arbPartialFunction[A: Cogen, B: Arbitrary]: Arbitrary[PartialFunction[A, B]] =
        Arbitrary(implicitly[Arbitrary[A => Option[B]]].arbitrary.map(Function.unlift))

      implicit def eqStream[O: Eq]: Eq[Stream[IO, O]] =
        Eq.instance(
          (x, y) =>
            Eq[IO[Vector[Either[Throwable, O]]]]
              .eqv(x.attempt.compile.toVector, y.attempt.compile.toVector))

      checkAll("MonadError[Stream[F, ?], Throwable]",
               MonadErrorTests[Stream[IO, ?], Throwable].monadError[Int, Int, Int])
      checkAll("FunctorFilter[Stream[F, ?]]",
               FunctorFilterTests[Stream[IO, ?]].functorFilter[String, Int, Int])
      checkAll("MonoidK[Stream[F, ?]]", MonoidKTests[Stream[IO, ?]].monoidK[Int])
    }
  }
}
