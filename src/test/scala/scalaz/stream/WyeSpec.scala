package scalaz.stream

import Process._
import org.scalacheck._
import scala.concurrent.SyncVar
import scala.concurrent.duration._
import scalaz._
import scalaz.concurrent.{Strategy, Task}
import org.scalacheck.Prop._
import scalaz.stream.async.mutable.Queue


object WyeSpec extends Properties("wye") {

  class Ex extends java.lang.Exception {
    override def fillInStackTrace(): Throwable = this
  }

  implicit val S = Strategy.DefaultStrategy

  property("either.terminate-on-both") = secure {
    val e = (Process.range(0, 20) either Process.range(0, 20)).runLog.timed(1000).run
    (e.collect { case -\/(v) => v } == (0 until 20).toSeq) :| "Left side is merged ok" &&
      (e.collect { case \/-(v) => v } == (0 until 20).toSeq) :| "Right side is merged ok"
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
    val syncO = new SyncVar[Int]

    val l = Process.awakeEvery(10 millis) onComplete   (eval(Task.fork(Task.delay{ Thread.sleep(500);syncL.put(100)})).drain)
    val r = Process.awakeEvery(10 millis) onComplete  (eval(Task.fork(Task.delay{ Thread.sleep(500);syncR.put(200)})).drain)

    val e = ((l either r).take(10) onComplete (eval(Task.delay(syncO.put(1000))).drain)).runLog.timed(3000).run

    (e.size == 10) :| "10 first was taken" &&
      (syncO.get(3000) == Some(1000)) :| "Out side was cleaned" &&
      (syncL.get(0) == Some(100)) :| "Left side was cleaned" &&
      (syncR.get(0) == Some(200)) :| "Right side was cleaned"

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

    m.runLog.timed(180000).run.map(_ < 100) == Seq(true)

  }

  // checks we are able to handle reasonable number of deeply nested wye`s .
  property("merge.deep-nested") = secure {
    val count = 20
    val deep = 100

    def src(of: Int) = Process.range(0, count).map((_, of))

    val merged =
      (1 until deep).foldLeft(src(0))({
        case (p, x) => p merge src(x)
      })

    val m =
      merged.flatMap {
        case (v, of) =>
          if (v % 10 == 0) {
            val e = new java.lang.Exception
            emit(e.getStackTrace.length)
          } else {
            halt
          }
      }.fold(0)(_ max _)

    m.runLog.timed(300000).run.map(_ < 100) == Seq(true)

  }




  property("merged.queue-drain") = secure {
    val(q1,s1) = async.queue[Int]
    val(q2,s2) = async.queue[Int]

    val sync = new SyncVar[Throwable \/ IndexedSeq[Int]]
    (s1 merge s2).take(4).runLog.timed(3000).runAsync(sync.put)

    (Process.range(1,10) to q1.toSink()).run.runAsync(_=>())

    sync.get(3000) == Some(\/-(Vector(1, 2, 3, 4)))

  }


  property("merge.queue.both-cleanup") = secure {
    val(q1,s1) = async.queue[Int]
    val(q2,s2) = async.queue[Int]

    q1.enqueue(1)
    q2.enqueue(2)

    var cup1 = false
    var cup2 = false

    def clean(side:Int) = suspend(eval(Task.delay(
      side match {
        case 1 => cup1 = true
        case 2 => cup2 = true
      }
    ))).drain

    val sync = new SyncVar[Throwable \/ IndexedSeq[Int]]
    ((s1 onComplete clean(1)) merge (s2 onComplete clean(2))).take(2).runLog.timed(3000).runAsync(sync.put)

    ((sync.get(3000).isEmpty == false) :| "Process terminated") &&
    (sync.get.fold(_=>Nil,s=>s.sorted) == Vector(1,2)) :| "Values were collected" &&
    (cup1 && cup2 == true) :| "Cleanup was called on both sides"

  }


  // this tests the specific situation, where left side got killed by wye terminating earlier.
  // Left is switched to `cleanup` where it waits for q2 to be filled with value or closed.
  // Close of q2 is action of s3 (right side) and must be performed once the right side
  // is executing its cleanup
  property("merge.queue.left-cleanup-by-right-cleanup") = secure {
    val(q1,s1) = async.queue[Int]
    val(q2,s2) = async.queue[Int]
    def close[A]( q:Queue[A]) =  (suspend(eval(Task.delay{  q.close}))).drain

    q1.enqueue(1)

    val s3 = Process(2).toSource onComplete(close(q2))


    val sync = new SyncVar[Throwable \/ IndexedSeq[Int]]
    ((s1 onComplete s2) merge s3).take(2).runLog.timed(3000).runAsync(sync.put)

    ((sync.get(3000).isEmpty == false) :| "Process terminated") &&
    (sync.get.fold(_=>Nil,s=>s.sorted) == Vector(1,2)) :| "Values were collected"
  }

}
