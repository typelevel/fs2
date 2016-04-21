package fs2

import fs2.util.Task

import TestUtil._

import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

object Pipe2Spec extends Properties("pipe2") {

  property("zipWith left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.zipWith(p)(_ + _) ==? Vector("1A", "1B", "1C") &&
      p.zipWith(ones)(_ + _) ==? Vector("A1", "B1", "C1")
  }

  property("zipWith both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.zipWith(as)(_ + _).take(3) ==? Vector("1A", "1A", "1A") &&
      as.zipWith(ones)(_ + _).take(3) ==? Vector("A1", "A1", "A1")
  }

  property("zipAllWith left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.through2p(p)(pipe2.zipAllWith("2","Z")(_ + _)).take(5) ==?
        Vector("1A", "1B", "1C", "1Z", "1Z") &&
      p.through2p(ones)(pipe2.zipAllWith("Z","2")(_ + _)).take(5) ==?
        Vector("A1", "B1", "C1", "Z1", "Z1")
  }

  property("zipAllWith both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.through2p(as)(pipe2.zipAllWith("2", "Z")(_ + _)).take(3) ==?
     Vector("1A", "1A", "1A") &&
    as.through2p(ones)(pipe2.zipAllWith("Z", "2")(_ + _)).take(3) ==?
     Vector("A1", "A1", "A1")
  }

  property("zip left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.zip(p) ==? Vector("1" -> "A", "1" -> "B", "1" -> "C") &&
      p.zip(ones) ==? Vector("A" -> "1", "B" -> "1", "C" -> "1")
  }

  property("zip both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.zip(as).take(3) ==? Vector("1" -> "A", "1" -> "A", "1" -> "A") &&
      as.zip(ones).take(3) ==? Vector("A" -> "1", "A" -> "1", "A" -> "1")
  }

  property("zipAll left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.through2p(p)(pipe2.zipAll("2","Z")).take(5) ==? Vector("1" -> "A", "1" -> "B", "1" -> "C", "1" -> "Z", "1" -> "Z") &&
      p.through2p(ones)(pipe2.zipAll("Z","2")).take(5) ==? Vector("A" -> "1", "B" -> "1", "C" -> "1", "Z" -> "1", "Z" -> "1")
  }

  property("zipAll both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.through2p(as)(pipe2.zipAll("2", "Z")).take(3) ==? Vector("1" -> "A", "1" -> "A", "1" -> "A") &&
      as.through2p(ones)(pipe2.zipAll("Z", "2")).take(3) ==? Vector("A" -> "1", "A" -> "1", "A" -> "1")
  }

  property("interleave left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.interleave(p) ==? Vector("1", "A", "1", "B", "1", "C") &&
      p.interleave(ones) ==? Vector("A", "1", "B", "1", "C", "1")
  }

  property("interleave both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.interleave(as).take(3) ==? Vector("1", "A", "1") &&
      as.interleave(ones).take(3) ==? Vector("A", "1", "A")
  }

  property("interleaveAll left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.interleaveAll(p).take(9) ==? Vector("1", "A", "1", "B", "1", "C", "1", "1", "1") &&
      p.interleaveAll(ones).take(9) ==? Vector("A", "1", "B", "1", "C", "1", "1", "1", "1")
  }

  property("interleaveAll both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.interleaveAll(as).take(3) ==? Vector("1", "A", "1") &&
      as.interleaveAll(ones).take(3) ==? Vector("A", "1", "A")
  }

  // Uses a small scope to avoid using time to generate too large streams and not finishing
  property("interleave is equal to interleaveAll on infinite streams (by step-indexing)") = protect {
    forAll(Gen.choose(0,100)) { (n : Int) =>
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      ones.interleaveAll(as).take(n).toVector == ones.interleave(as).take(n).toVector
    }
  }

  property("either") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    val shouldCompile = s1.get.either(s2.get.covary[Task])
    val es = run { s1.get.covary[Task].through2(s2.get)(pipe2.either) }
    (es.collect { case Left(i) => i } ?= run(s1.get)) &&
    (es.collect { case Right(i) => i } ?= run(s2.get))
  }

  property("merge") = forAll { (s1: PureStream[Int], s2: PureStream[Int]) =>
    run { s1.get.merge(s2.get.covary[Task]) }.toSet ?=
    (run(s1.get).toSet ++ run(s2.get).toSet)
  }

  property("merge (left/right identity)") = forAll { (s1: PureStream[Int]) =>
    (run { s1.get.merge(Stream.empty.covary[Task]) } ?= run(s1.get)) &&
    (run { Stream.empty.through2(s1.get.covary[Task])(pipe2.merge) } ?= run(s1.get))
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
    val s = async.mutable.Semaphore[Task](0).unsafeRun
    val interrupt = Stream.emit(true) ++ Stream.eval_(s.increment)
    // tests that termination is successful even if stream being interrupted is hung
    (run { s1.get.evalMap(_ => s.decrement).interruptWhen(interrupt) } ?= Vector()) &&
    // tests that termination is successful even if interruption stream is infinitely false
    (run { s1.get.covary[Task].interruptWhen(Stream.constant(false)) } ?= run(s1.get))
  }

  property("interrupt (2)") = forAll { (s1: PureStream[Int]) =>
    val barrier = async.mutable.Semaphore[Task](0).unsafeRun
    val enableInterrupt = async.mutable.Semaphore[Task](0).unsafeRun
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
