package scalaz.stream2

import org.scalacheck.Prop._

import scalaz._
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.std.string._

import org.scalacheck.{Gen, Arbitrary, Properties}
import scalaz.concurrent.{Task, Strategy}
import Util._
import process1._
import scalaz.stream2.Process.Kill
import Process._
import TestInstances._
import scala.concurrent.duration._
import scala.concurrent.SyncVar

object ProcessSpec extends Properties("Process") {

  case object FailWhale extends RuntimeException("the system... is down")

  implicit val S = Strategy.DefaultStrategy
  implicit val scheduler = scalaz.stream2.DefaultScheduler

  // Subtyping of various Process types:
  // * Process1 is a Tee that only read from the left (Process1[I,O] <: Tee[I,Any,O])
  // * Tee is a Wye that never requests Both (Tee[I,I2,O] <: Wye[I,I2,O])
  // This 'test' is just ensuring that this typechecks
    object Subtyping {
      def asTee[I,O](p1: Process1[I,O]): Tee[I,Any,O] = p1
      def asWye[I,I2,O](t: Tee[I,I2,O]): Wye[I,I2,O] = t
    }

  property("basic") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) =>
    val f = (x: Int) => List.range(1, x.min(100))
    val g = (x: Int) => x % 7 == 0
    val pf: PartialFunction[Int, Int] = {case x: Int if x % 2 == 0 => x }

    val sm = Monoid[String]
//
//   println("##########"*10 + p)
//   println("P1 " + p.toList.map(_ + 1) )
//  println("P2 " +  p.pipe(lift(_ + 1)).toList )
//    println("====" +  (p.toList.map(_ + 1) === p.pipe(lift(_ + 1)).toList) )
  try {
    val examples = Seq(
        "map" |:  (p.toList.map(_ + 1) === p.map(_ + 1).toList)
       , "map-pipe" |: (p.toList.map(_ + 1) === p.pipe(lift(_ + 1)).toList)
       , "flatMap" |:   (p.toList.flatMap(f) === p.flatMap(f andThen Process.emitAll).toList)
    )

    examples.reduce(_ && _)
  } catch {
    case t : Throwable => t.printStackTrace(); throw t
  }



  }





  property("awakeEvery") = secure {
    Process.awakeEvery(100 millis).map(_.toMillis/100).take(5).runLog.run == Vector(1,2,3,4,5)
  }


  property("duration") =  {
    val firstValueDiscrepancy = duration.take(1).runLast.run.get
    val reasonableError = 200 * 1000000 // 200 millis
    (firstValueDiscrepancy.toNanos < reasonableError) :| "duration is near zero at first access"
  }

//  property("enqueue") = secure {
//    val tasks = Process.range(0,1000).map(i => Task { Thread.sleep(1); 1 })
//    tasks.sequence(50).pipe(processes.sum[Int].last).runLog.run.head == 1000 &&
//      tasks.gather(50).pipe(processes.sum[Int].last).runLog.run.head == 1000
//  }


  import scala.concurrent.duration._
  val smallDelay = Gen.choose(10, 300) map {_.millis}


  property("every") =
    forAll(smallDelay) { delay: Duration =>
      type BD = (Boolean, Duration)
      val durationSinceLastTrue: Process1[BD, BD] = {
        def go(lastTrue: Duration): Process1[BD,BD] = {
          await1 flatMap { pair:(Boolean, Duration) => pair match {
            case (true , d) => emit((true , d - lastTrue)) fby go(d)
            case (false, d) => emit((false, d - lastTrue)) fby go(lastTrue)
          } }
        }
        go(0.seconds)
      }

      val draws = (600.millis / delay) min 10 // don't take forever

      val durationsSinceSpike = every(delay).
                                tee(duration)(tee zipWith {(a,b) => (a,b)}).
                                take(draws.toInt) |>
        durationSinceLastTrue

      val result = durationsSinceSpike.runLog.run.toList
      val (head :: tail) = result

      head._1 :| "every always emits true first" &&
        tail.filter   (_._1).map(_._2).forall { _ >= delay } :| "true means the delay has passed" &&
        tail.filterNot(_._1).map(_._2).forall { _ <= delay } :| "false means the delay has not passed"
    }

  property("fill") = forAll(Gen.choose(0,30) flatMap (i => Gen.choose(0,50) map ((i,_)))) {
    case (n,chunkSize) =>
      Process.fill(n)(42, chunkSize).toList == List.fill(n)(42)
  }

  property("forwardFill") = secure {
    import scala.concurrent.duration._
    val t2 = Process.awakeEvery(2 seconds).forwardFill.zip {
      Process.awakeEvery(100 milliseconds).take(100)
    }.run.timed(15000).run
    true
  }

  property("iterate") = secure {
    Process.iterate(0)(_ + 1).take(100).toList == List.iterate(0, 100)(_ + 1)
  }

  property("kill executes cleanup") = secure {
    val cleanup = new SyncVar[Int]
    val p: Process[Task, Int] = halt onComplete(eval_(Task.delay { cleanup.put(1) }))
    p.kill.run.run
    cleanup.get(500).get == 1
  }

  property("kill") = secure {
    ("repeated-emit" |: emit(1).repeat.kill.toList == List()) &&
    ("repeated-emit-exception" |: {
      try { emit(1).repeat.killBy(FailWhale).toList; false }
      catch { case FailWhale => true }
    })
  }

  property("pipeIn") = secure {
    val q = async.boundedQueue[String]()
    val sink = q.enqueue.pipeIn(process1.lift[Int,String](_.toString))

    (Process.range(0,10) to sink).run.run
    val res = q.dequeue.take(10).runLog.run.toList
    q.close.run

    res === (0 until 10).map(_.toString).toList
  }

  // Single instance of original sink is used for all elements.
  property("pipeIn uses original sink once") = secure {
    // Sink starts by wiping `written`.
    var written = List[Int]()
    def acquire: Task[Unit] = Task.delay { written = Nil }
    def release(res: Unit): Task[Unit] = Task.now(())
    def step(res: Unit): Task[Int => Task[Unit]] = Task.now((i: Int) => Task.delay { written = written :+ i  })
    val sink = io.resource[Unit, Int => Task[Unit]](acquire)(release)(step)

    val source = Process(1, 2, 3).toSource

    val transformer = process1.lift((i: Int) => i + 1)
    source.to(sink.pipeIn(transformer)).run.run

    written == List(2, 3, 4)
  }


  property("range") = secure {
    Process.range(0, 100).runLog.run == IndexedSeq.range(0, 100) &&
      Process.range(0, 1).runLog.run == IndexedSeq.range(0, 1) &&
      Process.range(0, 0).runLog.run == IndexedSeq.range(0, 0)
  }

  property("ranges") = forAll(Gen.choose(1, 101)) { size =>
    Process.ranges(0, 100, size).flatMap { case (i,j) => emitAll(i until j) }.runLog.run ==
      IndexedSeq.range(0, 100)
  }

  property("state") = secure {
    val s = Process.state((0, 1))
    val fib = Process(0, 1) ++ s.flatMap { case (get, set) =>
      val (prev0, prev1) = get
      val next = prev0 + prev1
      eval(set((prev1, next))).drain ++ emit(next)
    }
    val l = fib.take(10).runLog.timed(3000).run.toList
    l === List(0, 1, 1, 2, 3, 5, 8, 13, 21, 34)
  }

  property("unfold") = secure {
    Process.unfold((0, 1)) {
      case (f1, f2) => if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
    }.map(_._1).runLog.run.toList == List(0, 1, 1, 2, 3, 5, 8, 13)
  }
}
