package fs2

import cats.~>
import cats.effect.IO
import org.scalacheck.Gen
import org.scalatest.Inside

class StreamSpec extends Fs2Spec with Inside {

  "Stream" - {

    "chunk" in forAll { (c: Vector[Int]) =>
      runLog(Stream.chunk(Chunk.seq(c))) shouldBe c
    }

    "fail (1)" in forAll { (f: Failure) =>
      an[Err.type] should be thrownBy f.get.run.unsafeRunSync()
    }

    "fail (2)" in {
      assert(throws (Err) { Stream.fail(Err) })
    }

    "fail (3)" in {
      assert(throws (Err) { Stream.emit(1) ++ Stream.fail(Err) })
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

    ">>" in forAll { (s: PureStream[Int], s2: PureStream[Int] ) =>
      runLog(s.get >> s2.get) shouldBe { runLog(s.get.flatMap(_ => s2.get)) }
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

    "onError (1)" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        val s2 = s.get ++ f.get
        runLog(s2.onError(_ => Stream.empty)) shouldBe runLog(s.get)
      }
    }

    "onError (2)" in {
      runLog(Stream.fail(Err) onError { _ => Stream.emit(1) }) shouldBe Vector(1)
    }

    "onError (3)" in {
      runLog(Stream.emit(1) ++ Stream.fail(Err) onError { _ => Stream.emit(1) }) shouldBe Vector(1,1)
    }

    "onError (4)" in {
      Stream.eval(IO(throw Err)).map(Right(_)).onError(t => Stream.emit(Left(t)))
            .take(1)
            .runLog.unsafeRunSync() shouldBe Vector(Left(Err))
    }

    "onError (5)" in {
      val r = Stream.fail(Err).covary[IO].onError(e => Stream.emit(e)).flatMap(Stream.emit(_)).runLog.unsafeRunSync()
      val r2 = Stream.fail(Err).covary[IO].onError(e => Stream.emit(e)).map(identity).runLog.unsafeRunSync()
      val r3 = Stream(Stream.emit(1).covary[IO], Stream.fail(Err).covary[IO], Stream.emit(2).covary[IO]).covary[IO].join(4).attempt.runLog.unsafeRunSync()
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

    "translate (1)" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.flatMap(i => Stream.eval(IO.pure(i))).translate(cats.arrow.FunctionK.id[IO])) shouldBe
      runLog(s.get)
    }

    "translate (2)" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.translateSync(cats.arrow.FunctionK.id[Pure]).covary[IO]) shouldBe runLog(s.get)
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

    "unfoldChunk" in {
      Stream.unfoldChunk(4L) { s =>
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
  }
}
