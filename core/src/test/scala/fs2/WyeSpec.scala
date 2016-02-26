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
}

