package fs2

import cats.~>
import cats.effect.IO
import cats.implicits._
import org.scalacheck.Gen
import org.scalatest.Inside
import scala.concurrent.duration._

class StreamSpec extends Fs2Spec with Inside {

  "Stream" - {

    "chunk" in forAll { (c: Vector[Int]) =>
      runLog(Stream.chunk(Chunk.seq(c))) shouldBe c
    }

    "fail (1)" in forAll { (f: Failure) =>
      an[Err.type] should be thrownBy f.get.run.unsafeRunSync()
    }

    "fail (2)" in {
      assert(throws (Err) { Stream.raiseError(Err) })
    }

    "fail (3)" in {
      assert(throws (Err) { Stream.emit(1) ++ Stream.raiseError(Err) })
    }

    "eval" in {
      runLog(Stream.eval(IO(23))) shouldBe Vector(23)
    }

    "++" in forAll { (s: PureStream[Int], s2: PureStream[Int]) =>
      runLog(s.get ++ s2.get) shouldBe { runLog(s.get) ++ runLog(s2.get) }
    }

    "flatMap" in forAll { (s: PureStream[PureStream[Int]]) =>
      runLog(s.get.flatMap(inner => inner.get)) shouldBe { runLog(s.get).flatMap(inner => runLog(inner.get)) }
    }

    "*>" in forAll { (s: PureStream[Int], s2: PureStream[Int] ) =>
      runLog(s.get *> s2.get) shouldBe { runLog(s.get.flatMap(_ => s2.get)) }
    }

    "iterate" in {
      Stream.iterate(0)(_ + 1).take(100).toList shouldBe List.iterate(0, 100)(_ + 1)
    }

    "iterateEval" in {
      Stream.iterateEval(0)(i => IO(i + 1)).take(100).runLog.unsafeRunSync() shouldBe List.iterate(0, 100)(_ + 1)
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
      runLog(Stream.raiseError(Err) handleErrorWith { _ => Stream.emit(1) }) shouldBe Vector(1)
    }

    "handleErrorWith (3)" in {
      runLog(Stream.emit(1) ++ Stream.raiseError(Err) handleErrorWith { _ => Stream.emit(1) }) shouldBe Vector(1,1)
    }

    "handleErrorWith (4)" in {
      Stream.eval(IO(throw Err)).map(Right(_): Either[Throwable,Int]).handleErrorWith(t => Stream.emit(Left(t)).covary[IO])
            .take(1)
            .runLog.unsafeRunSync() shouldBe Vector(Left(Err))
    }

    "handleErrorWith (5)" in {
      val r = Stream.raiseError(Err).covary[IO].handleErrorWith(e => Stream.emit(e)).flatMap(Stream.emit(_)).runLog.unsafeRunSync()
      val r2 = Stream.raiseError(Err).covary[IO].handleErrorWith(e => Stream.emit(e)).map(identity).runLog.unsafeRunSync()
      val r3 = Stream(Stream.emit(1).covary[IO], Stream.raiseError(Err).covary[IO], Stream.emit(2).covary[IO]).covary[IO].join(4).attempt.runLog.unsafeRunSync()
      r shouldBe Vector(Err)
      r2 shouldBe Vector(Err)
      r3.contains(Left(Err)) shouldBe true
    }

    "range" in {
      Stream.range(0, 100).toList shouldBe List.range(0, 100)
      Stream.range(0, 1).toList shouldBe List.range(0, 1)
      Stream.range(0, 0).toList shouldBe List.range(0, 0)
      Stream.range(0, 101, 2).toList shouldBe List.range(0, 101, 2)
      Stream.range(5,0, -1).toList shouldBe List.range(5,0,-1)
      Stream.range(5,0, 1).toList shouldBe Nil
      Stream.range(10, 50, 0).toList shouldBe Nil
    }

    "ranges" in forAll(Gen.choose(1, 101)) { size =>
      Stream.ranges(0, 100, size).covary[IO].flatMap { case (i,j) => Stream.emits(i until j) }.runLog.unsafeRunSync() shouldBe
        IndexedSeq.range(0, 100)
    }

    "translate" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.covary[IO].flatMap(i => Stream.eval(IO.pure(i))).translate(cats.arrow.FunctionK.id[IO])) shouldBe
      runLog(s.get)
    }

    "toList" in forAll { (s: PureStream[Int]) =>
      s.get.toList shouldBe runLog(s.get).toList
    }

    "toVector" in forAll { (s: PureStream[Int]) =>
      s.get.toVector shouldBe runLog(s.get)
    }

    "unfold" in {
      Stream.unfold((0, 1)) {
        case (f1, f2) => if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
      }.map(_._1).toList shouldBe List(0, 1, 1, 2, 3, 5, 8, 13)
    }

    "unfoldSegment" in {
      Stream.unfoldSegment(4L) { s =>
        if(s > 0) Some((Chunk.longs(Array[Long](s,s)), s-1)) else None
      }.toList shouldBe List[Long](4,4,3,3,2,2,1,1)
    }

    "unfoldEval" in {
      Stream.unfoldEval(10)(s => IO.pure(if (s > 0) Some((s, s - 1)) else None))
        .runLog.unsafeRunSync().toList shouldBe List.range(10, 0, -1)
    }

    "unfoldChunkEval" in {
      Stream.unfoldChunkEval(true)(s => IO.pure(if(s) Some((Chunk.booleans(Array[Boolean](s)),false)) else None))
        .runLog.unsafeRunSync().toList shouldBe List(true)
    }

    "translate stack safety" in {
      Stream.repeatEval(IO(0)).translate(new (IO ~> IO) { def apply[X](x: IO[X]) = IO.suspend(x) }).take(1000000).run.unsafeRunSync()
    }

    "duration" in {
      val delay = 200 millis

      val blockingSleep = IO { Thread.sleep(delay.toMillis) }

      val emitAndSleep = Stream.emit(()) ++ Stream.eval(blockingSleep)
      val t = emitAndSleep zip Stream.duration[IO] drop 1 map { _._2 } runLog

      (IO.shift *> t).unsafeToFuture collect {
        case Vector(d) => assert(d.toMillis >= delay.toMillis - 5)
      }
    }

    "every" in {
      pending // Too finicky on Travis
      type BD = (Boolean, FiniteDuration)
      val durationSinceLastTrue: Pipe[Pure,BD,BD] = {
        def go(lastTrue: FiniteDuration, s: Stream[Pure,BD]): Pull[Pure,BD,Unit] = {
          s.pull.uncons1.flatMap {
            case None => Pull.done
            case Some((pair, tl)) =>
              pair match {
                case (true , d) => Pull.output1((true , d - lastTrue)) *> go(d,tl)
                case (false, d) => Pull.output1((false, d - lastTrue)) *> go(lastTrue,tl)
              }
          }
        }
        s => go(0.seconds, s).stream
      }

      val delay = 20.millis
      val draws = (600.millis / delay) min 50 // don't take forever

      val durationsSinceSpike = Stream.every[IO](delay).
        map(d => (d, System.nanoTime.nanos)).
        take(draws.toInt).
        through(durationSinceLastTrue)

      (IO.shift *> durationsSinceSpike.runLog).unsafeToFuture().map { result =>
        val (head :: tail) = result.toList
        withClue("every always emits true first") { assert(head._1) }
        withClue("true means the delay has passed: " + tail) { assert(tail.filter(_._1).map(_._2).forall { _ >= delay }) }
        withClue("false means the delay has not passed: " + tail) { assert(tail.filterNot(_._1).map(_._2).forall { _ <= delay }) }
      }
    }

    "issue #941 - scope closure issue" in {
      Stream(1,2,3).map(_ + 1).repeat.zip(Stream(4,5,6).map(_ + 1).repeat).take(4).toList
    }

    "scope" in {
       val c = new java.util.concurrent.atomic.AtomicLong(0)
       val s1 = Stream.emit("a").covary[IO]
       val s2 = Stream.bracket(IO { c.incrementAndGet() shouldBe 1L; () })(
         _ => Stream.emit("b"),
         _ => IO { c.decrementAndGet(); ()}
       )
       runLog { (s1.scope ++ s2).take(2).repeat.take(4).merge(Stream.eval_(IO.unit)) }
     }
  }
}
