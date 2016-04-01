package fs2

import TestUtil._
import fs2.util.Task
import org.scalacheck.Prop._
import org.scalacheck._

object WyeSpec extends Properties("Wye") {

  property("either") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    val shouldCompile = s1.get.either(s2.get.covary[Task])
    val es = run { s1.get.covary[Task].pipe2(s2.get)(wye.either(_,_)) }
    (es.collect { case Left(i) => i } ?= run(s1.get)) &&
    (es.collect { case Right(i) => i } ?= run(s2.get))
  }

  property("merge") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    run { s1.get.merge(s2.get.covary[Task]) }.toSet ?=
    (run(s1.get).toSet ++ run(s2.get).toSet)
  }

  property("merge (left/right identity)") = forAll { (s1: PureStream[Int]) =>
    (run { s1.get.merge(Stream.empty.covary[Task]) } ?= run(s1.get)) &&
    (run { Stream.empty.pipe2(s1.get.covary[Task])(wye.merge(_,_)) } ?= run(s1.get))
  }

  property("merge (left/right failure)") = forAll { (s1: PureStream[Int], f: Failure) =>
    try { run (s1.get merge f.get); false }
    catch { case Err => true }
  }

  property("mergeHalt{L/R/Both}") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    (s1.tag + " " + s2.tag) |: {
      val outBoth = run { s1.get.covary[Task].map(Left(_)) mergeHaltBoth s2.get.map(Right(_)) }
      val outL = run { s1.get.covary[Task].map(Left(_)) mergeHaltL s2.get.map(Right(_)) }
      val outR = run { s1.get.covary[Task].map(Left(_)) mergeHaltR s2.get.map(Right(_)) }
      // out should contain at least all the elements from one of the input streams
      val e1 = run(s1.get)
      val e2 = run(s2.get)
      val bothOk =
        (outBoth.collect { case Left(a) => a } ?= e1) ||
        (outBoth.collect { case Right(a) => a } ?= e2)
      val haltLOk =
        outL.collect { case Left(a) => a } ?= e1
      val haltROk =
        outR.collect { case Right(a) => a } ?= e2
      bothOk && haltLOk && haltROk
    }
  }

  property("interrupt (1)") = forAll { (s1: PureStream[Int]) =>
    val s = async.mutable.Semaphore[Task](0).run
    val interrupt = Stream.emit(true) ++ Stream.eval_(s.increment)
    // tests that termination is successful even if stream being interrupted is hung
    (run { s1.get.evalMap(_ => s.decrement).interruptWhen(interrupt) } ?= Vector()) &&
    // tests that termination is successful even if interruption stream is infinitely false
    (run { s1.get.covary[Task].interruptWhen(Stream.constant(false)) } ?= run(s1.get))
  }

  property("interrupt (2)") = forAll { (s1: PureStream[Int]) =>
    val barrier = async.mutable.Semaphore[Task](0).run
    val enableInterrupt = async.mutable.Semaphore[Task](0).run
    val interruptedS1 = s1.get.evalMap { i =>
      // enable interruption and hang when hitting a value divisible by 7
      if (i % 7 == 0) enableInterrupt.increment.flatMap { _ => barrier.decrement.map(_ => i) }
      else Task.now(i)
    }
    val interrupt = Stream.eval(enableInterrupt.decrement) flatMap { _ => Stream.emit(false) }
    val out = run { interruptedS1.interruptWhen(interrupt) }
    // as soon as we hit a value divisible by 7, we enable interruption then hang before emitting it,
    // so there should be no elements in the output that are divisible by 7
    // this also checks that interruption works fine even if one or both streams are in a hung state
    out.forall(i => i % 7 != 0)
  }
}
