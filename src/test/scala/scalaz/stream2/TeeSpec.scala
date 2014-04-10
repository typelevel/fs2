package scalaz.stream2

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

/**
 * Created by pach on 09/04/14.
 */
object TeeSpec extends Properties("Tee") {

  import TestInstances._

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
          val a = Process.range(0,li.length).map(li(_))
          val b = Process.range(0,n)
          val r = a.tee(b)(tee.zipAll(-1, 1)).runLog.run.toList
          (r === li.zipAll(b.runLog.run.toList, -1, 1).toList)
        }
      )

      examples.reduce(_ && _)
    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    }
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

}
