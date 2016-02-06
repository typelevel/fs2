package fs2

import TestUtil._
import fs2.util.Task
import fs2.Stream.Handle
import java.util.concurrent.atomic.AtomicLong
import org.scalacheck.Prop._
import org.scalacheck._

object ConcurrentSpec extends Properties("concurrent") {

  property("either") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    val shouldCompile = s1.get.either(s2.get.covary[Task])
    val es = run { s1.get.covary[Task].pipe2(s2.get)(wye.either) }
    (es.collect { case Left(i) => i } ?= run(s1.get)) &&
    (es.collect { case Right(i) => i } ?= run(s2.get))
  }

  property("merge") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    run { s1.get.merge(s2.get.covary[Task]) }.toSet ?=
    (run(s1.get).toSet ++ run(s2.get).toSet)
  }

  property("merge (left/right identity)") = forAll { (s1: PureStream[Int]) =>
    (run { s1.get.merge(Stream.empty.covary[Task]) } ?= run(s1.get)) &&
    (run { Stream.empty.pipe2(s1.get.covary[Task])(wye.merge) } ?= run(s1.get))
  }

  property("merge/join consistency") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    run { s1.get.pipe2v(s2.get.covary[Task])(wye.merge) }.toSet ?=
    run { concurrent.join(2)(Stream(s1.get.covary[Task], s2.get.covary[Task])) }.toSet
  }

  property("join (1)") = forAll { (s1: PureStream[Int]) =>
    run { concurrent.join(1)(s1.get.covary[Task].map(Stream.emit)) } ?= run { s1.get }
  }

  property("join (2)") = forAll { (s1: PureStream[Int], n: SmallPositive) =>
    run { concurrent.join(n.get)(s1.get.covary[Task].map(Stream.emit)) }.toSet ?=
    run { s1.get }.toSet
  }

  property("join (3)") = forAll { (s1: PureStream[PureStream[Int]], n: SmallPositive) =>
    run { concurrent.join(n.get)(s1.get.map(_.get.covary[Task]).covary[Task]) }.toSet ?=
    run { s1.get.flatMap(_.get) }.toSet
  }

  property("merge (left/right failure)") = forAll { (s1: PureStream[Int], f: Failure) =>
    try { run (s1.get merge f.get); false }
    catch { case Err => true }
  }

  include { new Properties("hanging awaits") {
    val full = Stream.constant[Task,Int](42)
    val hang = Stream.repeatEval(Task.unforkedAsync[Unit] { cb => () }) // never call `cb`!
    val hang2: Stream[Task,Nothing] = full.drain
    val hang3: Stream[Task,Nothing] =
      Stream.repeatEval[Task,Unit](Task.async { cb => cb(Right(())) }).drain

    property("merge") = protect {
      println("starting hanging await.merge...")
      ( (full merge hang).take(1) === Vector(42) ) &&
      ( (full merge hang2).take(1) === Vector(42) ) &&
      ( (full merge hang3).take(1) === Vector(42) ) &&
      ( (hang merge full).take(1) === Vector(42) ) &&
      ( (hang2 merge full).take(1) === Vector(42) ) &&
      ( (hang3 merge full).take(1) === Vector(42) )
    }
    property("join") = protect {
      println("starting hanging await.join...")
      (concurrent.join(10)(Stream(full, hang)).take(1) === Vector(42)) &&
      (concurrent.join(10)(Stream(full, hang2)).take(1) === Vector(42)) &&
      (concurrent.join(10)(Stream(full, hang3)).take(1) === Vector(42)) &&
      (concurrent.join(10)(Stream(hang3,hang2,full)).take(1) === Vector(42))
    }

    def observe[A](msg: String, s: Stream[Task,A]) =
      s.map { a => println(msg + ": " + a); a }
  }}
}
