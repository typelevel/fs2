package scalaz.stream

import Cause._
import org.scalacheck.Properties
import org.scalacheck.Prop._

import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.Process._
import scalaz.stream.ReceiveY.{HaltR, HaltL, ReceiveR, ReceiveL}
import scalaz.{\/, -\/, \/-}
import scala.concurrent.duration._
import scala.concurrent.SyncVar


object WyeSpec extends  Properties("Wye"){

  implicit val S = Strategy.DefaultStrategy
  implicit val scheduler = scalaz.stream.DefaultScheduler


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

  property("detach1L") = secure {
    val w = wye.detach1L(wye.merge[Int])
    val x = Process.constant(1).wye(Process.range(10,20))(w).runLog.run
    x == List.range(10,20)
  }

  property("detach1R") = secure {
    val w = wye.detach1R(wye.merge[Int])
    val x = Process.range(0,10).wye(Process.constant(1))(w).runLog.run
    x == List.range(0,10)
  }

  property("disconnectL/R") = secure {
    val killL = wye.disconnectL(Kill)(wye.merge[Int])
    val fed = wye.feedR(Seq(0,1,2,3))(killL)
    val killed = wye.disconnectR(Kill)(fed)

    ("all values are unemited" |: (fed.unemit._1 == Seq(0,1,2,3))) &&
      (s"wye contains emitted values after right disconnect " |: (killed.unemit._1 ==  Seq(0,1,2,3))) &&
      (s"wye is killed after disconnected from right: $killed" |: (killed.unemit._2 == Halt(Kill)))

  }


  property("disconnectR/L") = secure {
    val killR = wye.disconnectR(Kill)(wye.merge[Int])
    val fed = wye.feedL(Seq(0,1,2,3))(killR)
    val killed = wye.disconnectL(Kill)(fed)

    ("all values are unEmitted" |: (fed.unemit._1 == Seq(0,1,2,3))) &&
      (s"wye contains emitted values after left disconnect " |: (killed.unemit._1 ==  Seq(0,1,2,3))) &&
      (s"wye is killed after disconnected from left: $killed" |: (killed.unemit._2 == Halt(Kill)))

  }

  // ensure that wye terminates when once side of it is infinite
  // and other side of wye is either empty, or one.
  property("infinite.one.side") = secure {
    import ReceiveY._
    def whileBoth[A,B]: Wye[A,B,Nothing] = {
      def go: Wye[A,B,Nothing] = wye.receiveBoth[A,B,Nothing] {
        case HaltOne(rsn) => Halt(rsn)
        case _ => go
      }
      go
    }
    val inf = Process.constant(0)
    val one = eval(Task.now(1))
    val empty = Process[Int]()
    inf.wye(empty)(whileBoth).run.timed(3000).attempt.run == \/-(()) &&
      empty.wye(inf)(whileBoth).run.timed(3000).attempt.run == \/-(()) &&
      inf.wye(one)(whileBoth).run.timed(3000).attempt.run == \/-(()) &&
      one.wye(inf)(whileBoth).run.timed(3000).attempt.run == \/-(())
  }

  property("either") = secure {
    val w = wye.either[Int,Int]
    val s = Process.constant(1).take(1)
    s.wye(s)(w).runLog.timed(3000).run.map(_.fold(identity, identity)).toList == List(1,1)
  }

  property("interrupt.source.halt") = secure {
    val p1 = Process(1,2,3,4,6).toSource
    val i1 = repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).runLog.timed(3000).run.toList
    v == List(1,2,3,4,6)
  }

  property("interrupt.signal.halt") = secure {
    val p1 = Process.range(1,1000)
    val i1 = Process(1,2,3,4).map(_=>false).toSource
    val v = i1.wye(p1)(wye.interrupt).runLog.timed(3000).run.toList
    v.size < 1000
  }

  property("interrupt.signal.true") = secure {
    val p1 = Process.range(1,1000)
    val i1 = Process(1,2,3,4).map(_=>false).toSource ++ emit(true) ++ repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).runLog.timed(3000).run.toList
    v.size < 1000
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


  property("either.continue-when-left-done") = secure {
    val e = (Process.range(0, 20) either (awakeEvery(25 millis).take(20))).runLog.timed(5000).run
    ("Both sides were emitted" |: (e.size == 40))  &&
      ("Left side terminated earlier" |: e.zipWithIndex.filter(_._1.isLeft).lastOption.exists(_._2 < 35))   &&
      ("Right side was last" |:  e.zipWithIndex.filter(_._1.isRight).lastOption.exists(_._2 == 39))
  }

  property("either.continue-when-right-done") = secure {
    val e = ((awakeEvery(25 millis).take(20)) either Process.range(0, 20)).runLog.timed(5000).run
    ("Both sides were emitted" |: (e.size == 40)) &&
      ("Right side terminated earlier" |: e.zipWithIndex.filter(_._1.isRight).lastOption.exists(_._2 < 35))   &&
      ("Left side was last" |: e.zipWithIndex.filter(_._1.isLeft).lastOption.exists(_._2 == 39))
  }

  property("either.left.failed") = secure {
    val e =
      ((Process.range(0, 2) ++ eval(Task.fail(Bwahahaa))) either Process.range(10, 20))
       .attempt().runLog.timed(3000).run

    (e.collect { case \/-(-\/(v)) => v } == (0 until 2)) :| "Left side got collected" &&
      (e.collect { case -\/(rsn) => rsn }.nonEmpty) :| "exception was propagated"

  }

  property("either.right.failed") = secure {
    val e =
      (Process.range(0, 2) either (Process.range(10, 20) ++ eval(Task.fail(Bwahahaa))))
      .attempt().runLog.timed(3000).run

    (e.collect { case \/-(-\/(v)) => v } == (0 until 2)) :| "Left side got collected" &&
      (e.collect { case \/-(\/-(v)) => v } == (10 until 20)) :| "Right side got collected" &&
      (e.collect { case -\/(rsn) => rsn }.nonEmpty) :| "exception was propagated"

  }

  property("either.cleanup-out-halts") = secure {
    val syncL = new SyncVar[Int]
    val syncR = new SyncVar[Int]
    val syncO = new SyncVar[Int]

    // Left process terminates earlier.
    val l = Process.awakeEvery(10 millis) onComplete eval_(Task.delay{ Thread.sleep(500);syncL.put(100)})
    val r = Process.awakeEvery(10 millis) onComplete eval_(Task.delay{ Thread.sleep(600);syncR.put(200)})

    val e = ((l either r).take(10) onComplete eval_(Task.delay(syncO.put(1000)))).runLog.timed(3000).run

    (e.size == 10) :| "10 first was taken" &&
      (syncO.get(3000) == Some(1000)) :| "Out side was cleaned" &&
      (syncL.get(0) == Some(100)) :| "Left side was cleaned" &&
      (syncR.get(0) == Some(200)) :| "Right side was cleaned"

  }


  // checks we are safe on thread stack even after emitting million values
  // non-deterministically from both sides
  property("merge.million") = secure {
    val count = 1000000
    val m =
      (Process.range(0,count ) merge Process.range(0, count)).flatMap {
        (v: Int) =>
          if (v % 1000 == 0) {
            val e = new java.lang.Exception
            emit(Thread.currentThread().getStackTrace.size)
          } else {
            halt
          }
      }

    val result = m.runLog.timed(180000).run
    (result.exists(_ > 100) == false) &&
      (result.size >= count / 1000)

  }

  // checks we are able to handle reasonable number of deeply nested wye`s .
  property("merge.deep-nested") = secure {
    val count = 20
    val deep = 100

    def src(of: Int) = Process.range(0, count).map((_, of)).liftIO

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
      }

    val result = m.runLog.timed(300000).run

    (result.exists(_ > 100) == false) &&
      (result.size >= count*deep/10)

  }

  //tests that wye correctly terminates drained process
  property("merge-drain-halt") = secure {

    val effect:Process[Task,Int] = Process.repeatEval(Task.delay(())).drain

    val pm1 = effect.wye(Process(1000,2000).toSource)(wye.merge).take(2)
    val pm2 = Process(3000,4000).toSource.wye(effect)(wye.merge).take(2)

    (pm1 ++ pm2).runLog.timed(3000).run.size == 4
  }

  //tests specific case of termination with nested wyes and interrupt
  property("nested-interrupt") = secure {
    val sync = new SyncVar[Throwable \/ IndexedSeq[Unit]]
    val term1 = async.signal[Boolean]
    term1.set(false).run

    val p1: Process[Task,Unit] = (Process.sleep(10.hours) fby emit(true)).wye(Process.sleep(10 hours))(wye.interrupt)
    val p2:Process[Task,Unit] = repeatEval(Task.now(true)).flatMap(_ => p1)
    val toRun =  term1.discrete.wye(p2)(wye.interrupt)

    toRun.runLog.runAsync {  sync.put   }

    Task {
      Thread.sleep(1000)
      term1.set(true).run
    }.runAsync(_ => ())


    sync.get(3000).nonEmpty
  }

  property("liftY") = secure {
    import TestInstances._
    forAll { (pi: Process0[Int], ps: Process0[String]) =>
      "liftY" |:  pi.pipe(process1.sum).toList == (pi: Process[Task,Int]).wye(ps)(process1.liftY(process1.sum)).runLog.timed(3000).run.toList
    }
  }

  property("attachL/R") = secure {
    val p = wye.feedR(100 until 110)(
      wye.feedL(0 until 10)(
        wye.attachL(process1.id[Int])(
          wye.attachR(process1.id[Int])(
            wye.merge
          )
        )
      )
    )
    val(out, next) = p.unemit
    out.toList == ((0 until 10) ++ (100 until 110)).toList
  }

  property("attachL/R terminate") = secure {
    var doneL : Option[Cause] = None
    var doneR : Option[Cause] = None

    val merger = wye.receiveBoth[Int,Int,Int]({
      case ReceiveL(i) => emit(i)
      case ReceiveR(i) => emit(i)
      case HaltL(cause) => doneL = Some(cause); Halt(cause)
      case HaltR(cause) => doneR = Some(cause); Halt(cause)
    }).repeat

    val left = wye.feedL(0 until 10)(wye.attachL(process1.take[Int](2))(merger))
    val right = wye.feedR(100 until 110)(wye.attachR(process1.take[Int](2))(merger))

    val(outL, nextL) = left.unemit
    val(outR, nextR) = right.unemit

    (doneL == Some(End))
    .&& (outL == Vector(0,1))
    .&& (wye.AwaitR.is.unapply(nextL.asInstanceOf[wye.WyeAwaitR[Int,Int,Int]]))
    .&& (doneR == Some(End))
    .&& (outR == Vector(100,101))
    .&& (wye.AwaitL.is.unapply(nextR.asInstanceOf[wye.WyeAwaitL[Int,Int,Int]]))

  }

  property("attachL/R terminate R fail L") = secure {
    var doneL : Option[Cause] = None
    var doneR : Option[Cause] = None

    val merger = wye.receiveBoth[Int,Int,Int]({
      case ReceiveL(i) => emit(i)
      case ReceiveR(i) => emit(i)
      case HaltL(cause) => doneL = Some(cause); Halt(cause)
      case HaltR(cause) => doneR = Some(cause); Halt(cause)
    }).repeat

    val p =
      wye.feedL(0 until 10)(
        wye.attachL(process1.take[Int](2) ++ Halt(Error(Bwahahaa)))(
          wye.feedR(100 until 110)(
            wye.attachR(process1.take[Int](2))(
              merger
            )
          )
        )
      )

    val(out, next) = p.unemit

    (doneL == Some(Error(Bwahahaa)))
    .&& (doneR == Some(End))
    .&& (out == Vector(100,101,0,1))
    .&& (next == Halt(Error(Bwahahaa)))

  }


}
