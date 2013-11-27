package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.{Arbitrary, Gen, Properties}
import scala.concurrent.SyncVar
import scalaz._
import scalaz.stream.Process._
import scalaz.stream.async.mutable.Queue
import java.util.concurrent.{TimeUnit, Executors}
import scalaz.stream.process1._
import scalaz.std.string._
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.string._

import org.scalacheck._
import Prop._
import Arbitrary.arbitrary
import scala.concurrent.duration._
import scalaz.concurrent.Task
import scala.concurrent
import scalaz.\/-
import scalaz.-\/
import scalaz.stream.ReceiveY.{ReceiveR, ReceiveL}

object LeakTest extends Properties("leak") {



  class Ex extends java.lang.Exception {
    override def fillInStackTrace(): Throwable = this
  }

/*
  property("find-a-leak") = secure {

    Thread.sleep(10000)
    println("starting")

    val count = 500000

    //two asyc queues that will be fed in from the other thread
    val (qA, qsA) = async.queue[String]
    val (qB, qsB) = async.queue[String]
    val sigTerm = async.signal[Boolean]

    val result = new SyncVar[Throwable \/ Unit]

    // sources of A and B merged together, then chunked and flatmapped to get only head and limit the resulting elements
    val mergedChunked = qsA merge qsB

    //Stream that interrupts on left side, that actually is only set intitially to false but never is set to true in this test
    //sigTerm.discrete.wye(mergedChunked)(wye.interrupt)

      mergedChunked.scan(0)({case (c,s) => c+1}).filter(_ % 100 == 0).map(println).run.runAsync({
        cb =>
          println("DONE WITH PROCESS")
          result.put(cb)
      })

    sigTerm.set(false).run

    println("Staring to feed")
    for {i <- 0 until count} yield {
      def put(q: Queue[String]) = {
        q.enqueue(i.toString)
         if (i % (count/100) == 0) println("fed "+ (i / (count / 100)) + "pct")
      }
      i match {
        case each3 if each3 % 3 == 0 => put(qB)
        case other                   => put(qA)
      }
    }

    println("Done with feeding")

    //refreshing the signal every single second
    val scheduler = Executors.newScheduledThreadPool(1)
    scheduler.scheduleAtFixedRate(new Runnable{
      def run() = {
        println("Refreshing")
        sigTerm.set(false).run
      }
    }, 0, 1, TimeUnit.SECONDS)


    //just awaiting on final result
    println(result.get(3000000))

    true
  }

*/
/*

  property("either") = secure {
    val w = wye.either[Int,Int]
    val s = Process(0 until 10:_*).toSource
    s.wye(s)(w).take(10).runLog.run.foreach(println)
    true
  }

  */

/*

 property("forwardFill") = secure {
    import concurrent.duration._
    val t2 = Process.awakeEvery(2 seconds).forwardFill.zip {
      Process.awakeEvery(100 milliseconds).take(100)
    }.map(v=>println(v)).run.run
    true
  }
*/

/*
  property("step") = secure {
    val p = Process.awakeEvery(100 millis).map(_.toMillis)
    val v = async.signal[Long]
    val t = toTask(p).map(a => v.value.set(a))
    val setvar = repeatEval(t).drain

    println(setvar.step.take(1).run.run)

   // v.continuous.merge(setvar)

    true
  }
*/
/*

  property("merge") = secure {
    import concurrent.duration._
    val sleepsL = Process.awakeEvery(1 seconds).take(3)
    val sleepsR = Process.awakeEvery(100 milliseconds).take(30)
    val sleeps = sleepsL merge sleepsR
    val p = sleeps.toTask
    val tasks = List.fill(10)(p.timed(500).attemptRun)
    tasks.forall(_.isRight)
  }

*/
  /*
    property("feedL") = secure {
      println("------------------ LEFT")
      val w = wye.feedL(List.fill(10)(1))(process1.id)
      val x = Process.range(0,100).wye(halt)(w).runLog.run
      x.toList == (List.fill(10)(1) ++ List.range(0,100))
    }



    property("feedR") = secure {
      println("------------------ RIGHT")
      val w = wye.feedR(List.fill(10)(1))(wye.merge[Int])
      val x = Process.range(0,100).wye(halt)(w).runLog.run
      println(x.toList)
      x.toList == (List.fill(10)(1) ++ List.range(0,100))
    }
  */

/*
  property("either") = secure {
    val w = wye.either[Int,Int]
    val s = Process.constant(1).take(1)
    val r = s.wye(s)(w).runLog.run

    println(r)
    r.map(_.fold(identity, identity)).toList == List(1,1)
  }
*/
/*
  property("interrupt") = secure {
    val p1 = Process(1,2,3,4,6).toSource
    val i1 = repeatEval(Task.now(false)).take(10)
    val v = (i1.wye(p1)(wye.interrupt)).runLog.run.toList
    v == List(1,2,3,4,6)
  }
*/
/*
  property("merge.million") = secure {
    val count = 1000000
    val m =
    (Process.range(0,count ) merge Process.range(0, count)).flatMap {
      (v: Int) =>
        if (v % 1000 == 0) {
          val e = new java.lang.Exception
          println(v,e.getStackTrace.length)
          if (e.getStackTrace.size > 1000) e.printStackTrace()

          emit(e.getStackTrace.length)
        } else {
         halt
        }
    }.fold(0)(_ max _)

    m.runLog.run.map(_ < 512) == Seq(true)

  }

*/
/*
  property("merge.deep") = secure {
    val count = 1000
    val deep = 100

    def src(of:Int) = Process.range(0,count).map((_,of))

    val merged =
    (1 until deep).foldLeft(src(0))({
      case (p,x) => p merge src(x)
    })

     val m =
      merged.flatMap {
        case (v,of) =>
          if (v % 1000 == 0) {
            val e = new java.lang.Exception
            println(of,e.getStackTrace.length)
            emit(e.getStackTrace.length)
          } else {
            halt
          }
      }.fold(0)(_ max _)

   m.runLog.run  .map(_ < 512) == Seq(true)

  }

*/
/*
  property("merge.million") = secure {
    val count = 1000000

    def source = Process.range(0,count).flatMap(i => eval(Task(i)))

    val m =
      (source merge source).flatMap {
        (v: Int) =>
          if (v % 1000 == 0) {
            val e = new java.lang.Exception
            println(v, e.getStackTrace.length)
            emit(e.getStackTrace.length)
          } else {
            halt
          }
      }.fold(0)(_ max _)

    m.runLog.run.map(_ < 512) == Seq(true)

  }
*/


/*

  // Subtyping of various Process types:
  // * Process1 is a Tee that only read from the left (Process1[I,O] <: Tee[I,Any,O])
  // * Tee is a Wye that never requests Both (Tee[I,I2,O] <: Wye[I,I2,O])
  // This 'test' is just ensuring that this typechecks
  object Subtyping {
    def asTee[I,O](p1: Process1[I,O]): Tee[I,Any,O] = p1
    def asWye[I,I2,O](t: Tee[I,I2,O]): Wye[I,I2,O] = t
  }


  implicit def EqualProcess[A:Equal]: Equal[Process0[A]] = new Equal[Process0[A]] {
    def equal(a: Process0[A], b: Process0[A]): Boolean =
      a.toList == b.toList
  }
  implicit def ArbProcess0[A:Arbitrary]: Arbitrary[Process0[A]] =
    Arbitrary(Arbitrary.arbitrary[List[A]].map(a => Process(a: _*)))

  property("basic") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) =>
    val f = (x: Int) => List.range(1, x.min(100))
    val g = (x: Int) => x % 7 == 0
    val pf : PartialFunction[Int,Int] = { case x : Int if x % 2 == 0 => x}

    val sm = Monoid[String]

      ("yip" |: {


        val l = p.toList.zip(p2.toList)
        println(l)
        val r = p.toSource.yip(p2.toSource).runLog.run.toList
        println(r)
        println("*"*150, l == r)
        (l === r)
      })
  }
*/
  /*
  property("basic2") = secure {
    val p = Process(1)
    val p2 = Process("a")
    val l = p.toList.zip(p2.toList)
    println(l)
    val r = p.toSource.yip(p2.toSource).runLog.run.toList
    println(r)
    println("*"*150, l == r)
    (l === r)
  }

  */
/*
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
*/
/*
  property("interrupt") = secure {
    val p1 = Process(1,2,3,4,6).toSource
    val i1 = repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).take(10).runLog.run.toList
    v == List(1,2,3,4,6)
  }
*/

  property("interrupt.wye") = secure {

    def go[I] : Wye[Boolean, Option[I], I] = awaitBoth[Boolean,Option[I]].flatMap {
      case ReceiveR(None) => println("HALTI"); halt
      case ReceiveR(Some(i)) => println(i); emit(i) ++ go
      case ReceiveL(kill) => if (kill) halt else go
      case ReceiveY(kill, Some(i)) => if (kill) halt else emit(i) ++ go
      case ReceiveY(kill, None) => halt
    }
    println(
       wye.feedR(Seq(Some(1),Some(2),Some(3),None))( wye.attachR(process1.id[Option[Int]])(go[Int]))
    )
    false
  }

}


