package fs2

import fs2.util.{Task,UF1}
import TestUtil._
import org.scalacheck._
import org.scalacheck.Prop.{throws => _, _}

object StreamSpec extends Properties("Stream") {

  property("chunk-formation (1)") = secure {
    Chunk.empty.toList == List() &&
    Chunk.singleton(23).toList == List(23)
  }

  property("chunk-formation (2)") = forAll { (c: Vector[Int]) =>
    Chunk.seq(c).toVector == c &&
    Chunk.seq(c).toList == c.toList &&
    Chunk.indexedSeq(c).toVector == c &&
    Chunk.indexedSeq(c).toList == c.toList &&
    Chunk.seq(c).iterator.toList == c.iterator.toList &&
    Chunk.indexedSeq(c).iterator.toList == c.iterator.toList
  }

  property("chunk") = forAll { (c: Vector[Int]) =>
    Stream.chunk(Chunk.seq(c)) ==? c
  }

  property("fail (1)") = forAll { (f: Failure) =>
    try { run(f.get); false }
    catch { case Err => true }
  }

  property("fail (2)") = secure {
    throws (Err) { Stream.fail(Err) }
  }

  property("fail (3)") = secure {
    throws (Err) { Stream.emit(1) ++ Stream.fail(Err) }
  }

  property("eval") = secure {
    Stream.eval(Task.delay(23)) ==? Vector(23)
  }

  property("++") = forAll { (s: PureStream[Int], s2: PureStream[Int]) =>
    (s.get ++ s2.get) ==? { run(s.get) ++ run(s2.get) }
  }

  property("flatMap") = forAll { (s: PureStream[PureStream[Int]]) =>
    s.get.flatMap(inner => inner.get) ==? { run(s.get).flatMap(inner => run(inner.get)) }
  }

  property("iterate") = protect {
    Stream.iterate(0)(_ + 1).take(100).toList == List.iterate(0, 100)(_ + 1)
  }

  property("iterateEval") = protect {
    Stream.iterateEval(0)(i => Task.delay(i + 1)).take(100).runLog.run.run == List.iterate(0, 100)(_ + 1)
  }

  property("map") = forAll { (s: PureStream[Int]) =>
    s.get.map(identity) ==? run(s.get)
  }

  property("onError (1)") = forAll { (s: PureStream[Int], f: Failure) =>
    val s2 = s.get ++ f.get
    s2.onError(_ => Stream.empty) ==? run(s.get)
  }

  property("onError (2)") = protect {
    (Stream.fail(Err) onError { _ => Stream.emit(1) }) === Vector(1)
  }

  property("onError (3)") = protect {
    (Stream.emit(1) ++ Stream.fail(Err) onError { _ => Stream.emit(1) }) === Vector(1,1)
  }

  property("onError (4)") = protect {
    Stream.eval(Task.delay(throw Err)).map(Right(_)).onError(t => Stream.emit(Left(t)))
          .take(1)
          .runLog.run.run ?= Vector(Left(Err))
  }

  property("range") = protect {
    Stream.range(0, 100).toList == List.range(0, 100) &&
      Stream.range(0, 1).toList == List.range(0, 1) &&
      Stream.range(0, 0).toList == List.range(0, 0)
  }

  property("ranges") = forAll(Gen.choose(1, 101)) { size =>
    Stream.ranges[Task](0, 100, size).flatMap { case (i,j) => Stream.emits(i until j) }.runLog.run.run ==
      IndexedSeq.range(0, 100)
  }

  property("translate (1)") = forAll { (s: PureStream[Int]) =>
    s.get.flatMap(i => Stream.eval(Task.now(i))).translate(UF1.id[Task]) ==?
    run(s.get)
  }

  property("translate (2)") = forAll { (s: PureStream[Int]) =>
    s.get.translate(UF1.id[Pure]) ==? run(s.get)
  }

  property("toList") = forAll { (s: PureStream[Int]) =>
    s.get.toList == run(s.get).toList
  }

  property("toVector") = forAll { (s: PureStream[Int]) =>
    s.get.toVector == run(s.get)
  }

  property("unfold") = protect {
    Stream.unfold((0, 1)) {
      case (f1, f2) => if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
    }.map(_._1).toList == List(0, 1, 1, 2, 3, 5, 8, 13)
  }

  property("unfoldEval") = protect {
    Stream.unfoldEval(10)(s => Task.now(if (s > 0) Some((s, s - 1)) else None))
      .runLog.run.run.toList == List.range(10, 0, -1)
  }

  property("translate stack safety") = protect {
    import fs2.util.{~>}
    Stream.repeatEval(Task.delay(0)).translate(new (Task ~> Task) { def apply[X](x: Task[X]) = Task.suspend(x) }).take(1000000).run.run.run
    true
  }
}
