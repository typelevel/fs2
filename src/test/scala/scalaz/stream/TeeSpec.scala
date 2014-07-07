package scalaz.stream

import org.scalacheck.Properties
import scalaz.concurrent.{Task, Strategy}

import org.scalacheck._
import org.scalacheck.Prop._
import scalaz.{Equal, Monoid}
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.vector._
import scalaz.std.string._
import scalaz.syntax.equal._
import scalaz.syntax.foldable._

import Process._
import process1._

import TestInstances._
import scala.concurrent.SyncVar

/**
 * Created by pach on 09/04/14.
 */
object TeeSpec extends Properties("Tee") {

  import TestInstances._

  case object Err extends RuntimeException("Error")
  case object Err2 extends RuntimeException("Error 2")

  implicit val S = Strategy.DefaultStrategy

  property("basic") = forAll { (pi: Process0[Int], ps: Process0[String], n: Int) =>
    val li = pi.toList
    val ls = ps.toList

    val g = (x: Int) => x % 7 === 0
    val pf: PartialFunction[Int, Int] = {case x: Int if x % 2 === 0 => x }
    val sm = Monoid[String]


//    println("##########"*10 )
//    println("li" + li)
//    println("ls" + ls)
//    println("P1 " + li.toList.zip(ls.toList))
//    println("P2 " + pi.zip(ps).toList )


  /*
   val a = Process.range(0,l.length).map(l(_))
    val b = Process.range(0,l2.length).map(l2(_))
    val r = a.tee(b)(tee.zipAll(-1, 1)).runLog.run.toList
    r.toString |: (r == l.zipAll(l2, -1, 1).toList)

   */

    try {

      val examples = Seq(
       s"zip: $li | $ls " |: li.toList.zip(ls.toList) === pi.zip(ps).toList
       , "zipAll " |: {
          val a = Process.range(0,li.length).map(li(_)).liftIO
          val b = Process.range(0,math.abs(n % 100)).liftIO
          val r = a.tee(b)(tee.zipAll(-1, 1)).runLog.run.toList
          (r === li.zipAll(b.runLog.run.toList, -1, 1).toList)
        }
      )

      examples.reduce(_ && _)
    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    }
  }

  property("cleanup called - both sides finite") = secure {
    val leftCleanup = new SyncVar[Int]
    val rightCleanup = new SyncVar[Int]
    val l = Process(1) onComplete(eval_(Task.delay { leftCleanup.put(1) }))
    val r = Process(2, 3, 4) onComplete(eval_( Task.delay { rightCleanup.put(1) }))
    l.zip(r).run.run
    leftCleanup.get(500).get == rightCleanup.get(500).get
  }

  property("cleanup called after exception different from Kill") = secure {
    val leftCleanup = new SyncVar[Int]
    val rightCleanup = new SyncVar[Int]
    val l = Process(1, 2, 3) onComplete(eval_(Task.delay { leftCleanup.put(1) }))
    val r = fail(new java.lang.Exception()) onComplete(eval_( Task.delay { rightCleanup.put(1) }))
    l.zip(r).run.attemptRun
    leftCleanup.get(500).get == rightCleanup.get(500).get
  }

  // ensure that zipping terminates when the smaller stream runs out on left side
  property("zip left/right side infinite") = secure {
    val ones = Process.eval(Task.now(1)).repeat
    val p = Process(1,2,3)
    ones.zip(p).runLog.run == IndexedSeq(1 -> 1, 1 -> 2, 1 -> 3) &&
      p.zip(ones).runLog.run == IndexedSeq(1 -> 1, 2 -> 1, 3 -> 1)
  }

  // ensure that zipping terminates when  killed from the downstream
  property("zip both side infinite") = secure {
    val ones = Process.eval(Task.now(1)).repeat
    ones.zip(ones).take(3).runLog.run == IndexedSeq(1 -> 1, 1 -> 1, 1 -> 1)
  }

  property("passL/R") = secure {
    val a = Process.range(0,10)
    val b: Process[Task,Int] = halt
    a.tee(b)(tee.passL[Int]).runLog.run == List.range(0,10) &&
      b.tee(a)(tee.passR[Int]).runLog.run == List.range(0,10)
  }

  property("tee can await right side and emit when left side stops") = secure {
    import TestUtil._
    val t: Tee[Int, String, Any] = tee.passL[Int] onComplete emit(2) onComplete tee.passR[String] onComplete emit(true)
    val r = emit("a") ++ emit("b")
    val res = List(1, 2, "a", "b", true)
    ("normal termination" |: emit(1).tee(r)(t).toList == res) &&
      ("kill" |: (emit(1) ++ fail(Kill)).tee(r)(t).expectExn(_ == Kill).toList == res) &&
      ("failure" |: (emit(1) ++ fail(Err)).tee(r)(t).expectExn(_ == Err).toList == res)
  }

  property("tee can await left side and emit when right side stops") = secure {
    import TestUtil._
    val t: Tee[String, Int, Any] = tee.passR[Int] onComplete emit(2) onComplete tee.passL[String] onComplete emit(true)
    val l = emit("a") ++ emit("b")
    val res = List(1, 2, "a", "b", true)
    ("normal termination" |: l.tee(emit(1))(t).toList == res) &&
      ("kill" |: l.tee(emit(1) ++ fail(Kill))(t).expectExn(_ == Kill).toList == res) &&
      ("failure" |: l.tee(emit(1) ++ fail(Err))(t).expectExn(_ == Err).toList == res)
  }

  property("tee exceptions") = secure {
    import TestUtil._
    val leftFirst: Tee[Int, Int, Any] = tee.passL[Int] onComplete tee.passR[Int] onComplete emit(3)
    val rightFirst: Tee[Int, Int, Any] = tee.passR[Int] onComplete tee.passL[Int] onComplete emit(3)
    val l = emit(1) ++ fail(Err)
    val r = emit(2) ++ fail(Err2)
    ("both fail - left first" |: l.tee(r)(leftFirst).expectExn(_ == Err).toList == List(1, 2, 3)) &&
      ("both fail - right first" |: l.tee(r)(rightFirst).expectExn(_ == Err2).toList == List(2, 1, 3)) &&
      ("left fails - left first" |: l.tee(emit(2))(leftFirst).expectExn(_ == Err).toList == List(1, 2, 3)) &&
      ("right fails - right first" |: emit(1).tee(r)(rightFirst).expectExn(_ == Err2).toList == List(2, 1, 3))
      ("right fails - left first" |: emit(1).tee(r)(leftFirst).expectExn(_ == Err2).toList == List(1, 2, 3)) &&
      ("left fails - right first" |: l.tee(emit(2))(rightFirst).expectExn(_ == Err).toList == List(2, 1, 3))
  }
}
