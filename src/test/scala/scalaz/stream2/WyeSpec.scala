package scalaz.stream2

import org.scalacheck.Properties
import org.scalacheck.Prop._

import Process._
import scalaz.concurrent.Task
import scalaz.stream2.Process._
import scalaz.-\/
import scalaz.\/-


object WyeSpec extends  Properties("Wye"){

  property("feedL") = secure {
    val w = wye.feedL(List.fill(10)(1))(process1.id)
    val x = Process.range(0,100).wye(halt)(w).runLog.run
    x.toList == (List.fill(10)(1) ++ List.range(0,100))
  }

  property("feedR") = secure {
    val w = wye.feedR(List.fill(10)(1))(wye.merge[Int])
    val x = Process.range(0,100).wye(halt)(w).runLog.run
    x.toList == (List.fill(10)(1) ++ List.range(0,100))
  }

  property("either") = secure {
    val w = wye.either[Int,Int]
    val s = Process.constant(1).take(1)
    s.wye(s)(w).runLog.run.map(_.fold(identity, identity)).toList == List(1,1)
  }

  property("interrupt.source.halt") = secure {
    val p1 = Process(1,2,3,4,6).toSource
    val i1 = repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).runLog.run.toList
    v == List(1,2,3,4,6)
  }

  property("interrupt.signal.halt") = secure {
    val p1 = Process.range(1,1000)
    val i1 = Process(1,2,3,4).map(_=>false).toSource
    val v = i1.wye(p1)(wye.interrupt).runLog.run.toList
    v == List(1,2,3)
  }

  property("interrupt.signal.true") = secure {
    val p1 = Process.range(1,1000)
    val i1 = Process(1,2,3,4).map(_=>false).toSource ++ emit(true) ++ repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).runLog.run.toList
    v == List(1,2,3,4)
  }

  property("either.terminate-on-both") = secure {
    val e = (Process.range(0, 20) either Process.range(0, 20)).runLog.timed(1000).run
    (e.collect { case -\/(v) => v } == (0 until 20).toSeq) :| "Left side is merged ok" &&
      (e.collect { case \/-(v) => v } == (0 until 20).toSeq) :| "Right side is merged ok"
  }

  property("either.terminate-on-downstream") = secure {
    val e = (Process.range(0, 20) either Process.range(0, 20)).take(10).runLog.timed(1000).run
    e.size == 10
  }



}
