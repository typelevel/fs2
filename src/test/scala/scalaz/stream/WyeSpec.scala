package scalaz.stream

import Process._
import org.scalacheck._
import scala.concurrent.SyncVar
import scala.concurrent.duration._
import scalaz._
import scalaz.concurrent.Task
import org.scalacheck.Prop._


object WyeSpec extends Properties("wye") {

  class Ex extends java.lang.Exception {
    override def fillInStackTrace(): Throwable = this
  }



  property("either.terminate-on-both") = secure {
    val e = (Process.range(0, 20) either Process.range(0, 20)).runLog.timed(1000).run
    (e.collect { case -\/(v) => v } == (0 until 20).toSeq) :| "Left side is merged ok" &&
      (e.collect { case \/-(v) => v } == (0 until 20).toSeq) :| "Right side is merged ok" &&
      (e.zipWithIndex.collect { case ((-\/(v), idx)) if idx % 2 != 0 => idx } == Nil) :| "Left side was first and fairly queued" &&
      (e.zipWithIndex.collect { case ((\/-(v), idx)) if idx % 2 == 0 => idx } == Nil) :| "Right side was second and fairly queued"
  }

  property("either.terminate-on-out-terminating") = secure {
    val e = (Process.range(0, 20) either Process.range(0, 20)).take(10).runLog.timed(1000).run
    e.size == 10
  }

  property("either.continue-when-left-done") = secure {
    val e = (Process.range(0, 20) either (awakeEvery(25 millis).take(20))).runLog.timed(5000).run
    (e.size == 40) :| "Both sides were emitted" &&
      (e.zipWithIndex.filter(_._1.isLeft).lastOption.exists(_._2 < 35)) :| "Left side terminated earlier" &&
      (e.zipWithIndex.filter(_._1.isRight).lastOption.exists(_._2 == 39)) :| "Right side was last"
  }

  property("either.continue-when-right-done") = secure {
    val e = ((awakeEvery(25 millis).take(20)) either Process.range(0, 20)).runLog.timed(5000).run
    (e.size == 40) :| "Both sides were emitted" &&
      (e.zipWithIndex.filter(_._1.isRight).lastOption.exists(_._2 < 35)) :| "Right side terminated earlier" &&
      (e.zipWithIndex.filter(_._1.isLeft).lastOption.exists(_._2 == 39)) :| "Left side was last"
  }

  property("either.cleanup-on-left") = secure {
    val e = (
      ((Process.range(0, 2) ++ eval(Task.fail(new Ex))) attempt (_ => Process.range(100, 102))) either
        Process.range(10, 20)
      ).runLog.timed(3000).run
    (e.collect { case -\/(\/-(v)) => v } == (0 until 2)) :| "Left side got collected before failure" &&
      (e.collect { case \/-(v) => v } == (10 until 20)) :| "Right side got collected after failure" &&
      (e.collect { case -\/(-\/(v)) => v } == (100 until 102)) :| "Left side cleanup was called"

  }

  property("either.cleanup-on-right") = secure {
    val e = (Process.range(10, 20) either
      ((Process.range(0, 2) ++ eval(Task.fail(new Ex))) attempt (_ => Process.range(100, 102)))
      ).runLog.timed(3000).run
    (e.collect { case \/-(\/-(v)) => v } == (0 until 2)) :| "Right side got collected before failure" &&
      (e.collect { case -\/(v) => v } == (10 until 20)) :| "Left side got collected after failure" &&
      (e.collect { case \/-(-\/(v)) => v } == (100 until 102)) :| "Right side cleanup was called"
  }

  property("either.fallback-on-left") = secure {
    val e = ((Process.range(0, 2) ++ Process.range(100, 102)) either (Process.range(10, 12))).runLog.timed(1000).run
    (e.collect { case -\/(v) => v } == (0 until 2) ++ (100 until 102)) :| "Left side collected with fallback" &&
      (e.collect { case \/-(v) => v } == (10 until 12)) :| "Right side collected"
  }

  property("either.fallback-on-right") = secure {
    val e = ((Process.range(10, 12)) either (Process.range(0, 2) ++ Process.range(100, 102))).runLog.timed(1000).run
    (e.collect { case \/-(v) => v } == (0 until 2) ++ (100 until 102)) :| "Right side collected with fallback" &&
      (e.collect { case -\/(v) => v } == (10 until 12)) :| "Left side collected"
  }

  property("either.cleanup-out-halts") = secure {
    val syncL = new SyncVar[Int]
    val syncR = new SyncVar[Int]

    val l = Process.awakeEvery(10 millis) onComplete (eval(Task.delay(syncL.put(100))).drain)
    val r = Process.awakeEvery(10 millis) onComplete (eval(Task.delay(syncR.put(200))).drain)

    val e = (l either r).take(10).runLog.timed(3000).run

    (e.size == 10) :| "10 first was taken" &&
      (syncL.get(1000) == Some(100)) :| "Left side was cleaned" &&
      (syncR.get(1000) == Some(200)) :| "Right side was cleaned"

  }



  // checks we are safe on thread stack even after emitting million values nondet from both sides
  property("merge.million") = secure {
    val count = 1000000
    val m =
      (Process.range(0,count ) merge Process.range(0, count)).flatMap {
        (v: Int) =>
          if (v % 1000 == 0) {
            val e = new java.lang.Exception
            emit(e.getStackTrace.length)
          } else {
            halt
          }
      }.fold(0)(_ max _)

    m.runLog.run.map(_ < 512) == Seq(true)

  }

  // checks we are able to handle reasonable number of deeply nested wye`s .
  property("merge.deep-nested") = secure {
    val count = 1000
    val deep = 100

    def src(of: Int) = Process.range(0, count).map((_, of))

    val merged =
      (1 until deep).foldLeft(src(0))({
        case (p, x) => p merge src(x)
      })

    val m =
      merged.flatMap {
        case (v, of) =>
          if (v % 1000 == 0) {
            val e = new java.lang.Exception
            emit(e.getStackTrace.length)
          } else {
            halt
          }
      }.fold(0)(_ max _)

    m.runLog.run.map(_ < 512) == Seq(true)

  }


}
