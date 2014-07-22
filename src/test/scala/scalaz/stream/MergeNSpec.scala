package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.Process._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.SyncVar
import scalaz.\/


object MergeNSpec extends Properties("mergeN") {

  implicit val S = Strategy.DefaultStrategy
  implicit val scheduler = scalaz.stream.DefaultScheduler

  property("basic") = forAll {
    (l: List[Int]) =>

      val count = (l.size % 6) max 1

      val ps =
        emitAll(for (i <- 0 until count) yield {
          emitAll(l.filter(v => (v % count).abs == i)).toSource
        }).toSource

      val result =
        merge.mergeN(ps).runLog.timed(3000).run.toSet

      (result.toList.sorted == l.toSet.toList.sorted) :| "All elements were collected"

  }


  // tests that when downstream terminates,
  // all cleanup code is called on upstreams
  property("source-cleanup-down-done") = secure {

    val cleanupQ = async.boundedQueue[Int]()
    val cleanups = new SyncVar[Throwable \/ IndexedSeq[Int]]

    val ps =
      emitAll(for (i <- 0 until 10) yield {
        Process.constant(i+100)  onComplete eval_(cleanupQ.enqueueOne(i))
      }).toSource onComplete eval_(cleanupQ.enqueueOne(99))

    cleanupQ.dequeue.take(11).runLog.runAsync(cleanups.put)

    // this makes sure we see at least one value from sources
    // and therefore we won`t terminate downstream to early.
    merge.mergeN(ps).scan(Set[Int]())({
      case (sum, next) => sum + next
    }).takeWhile(_.size < 10).runLog.timed(3000).run



    (cleanups.get(3000).isDefined &&
      cleanups.get(0).get.isRight &&
      cleanups.get(0).get.toList.flatten.size == 11) :| s"Cleanups were called on upstreams: ${cleanups.get(0)}"
  }


  // unlike source-cleanup-down-done it focuses on situations where upstreams are in async state,
  // and thus will block until interrupted.
  property("source-cleanup-async-down-done") = secure {
    val cleanupQ = async.boundedQueue[Int]()
    val cleanups = new SyncVar[Throwable \/ IndexedSeq[Int]]
    cleanupQ.dequeue.take(11).runLog.runAsync(cleanups.put)


    //this below is due the non-thread-safety of scala object, we must memoize this here
    val delayEach10 =  Process.awakeEvery(10 seconds)

    def oneUp(index:Int) =
      (emit(index).toSource ++ delayEach10.map(_=>index))
      .onComplete(eval_(cleanupQ.enqueueOne(index)))

    val ps =
      (emitAll(for (i <- 0 until 10) yield oneUp(i)).toSource ++ delayEach10.drain) onComplete
        eval_(cleanupQ.enqueueOne(99))


    merge.mergeN(ps).takeWhile(_ < 9).runLog.timed(3000).run

    (cleanups.get(3000).isDefined &&
      cleanups.get(0).get.isRight &&
      cleanups.get(0).get.toList.flatten.size == 11) :| s"Cleanups were called on upstreams: ${cleanups.get(0)}"
  }


  //merges 10k of streams, each with 100 of elements
  property("merge-million") = secure {
    val count = 1000
    val eachSize = 1000

    val ps =
      emitAll(for (i <- 0 until count) yield {
        Process.range(0,eachSize)
      }).toSource

    val result = merge.mergeN(ps).fold(0)(_ + _).runLast.timed(120000).run

    (result == Some(499500000)) :| s"All items were emitted: $result"
  }

  property("merge-maxOpen") = secure {
    val count = 100
    val eachSize = 10

    val sizeSig = async.signal[Int]

    def incrementOpen =
      sizeSig.compareAndSet({
        case Some(running) => Some(running + 1)
        case None => Some(1)
      })

    def decrementDone =
      sizeSig.compareAndSet({
        case Some(running) => Some(running - 1)
        case None => Some(0)
      })

    val sleep5 = sleep(5 millis)

    val ps =
      emitAll(for (i <- 0 until count) yield {
        eval_(incrementOpen) fby
          Process.range(0,eachSize).flatMap(i=> emit(i) fby sleep5) onComplete
          eval_(decrementDone)
      }).toSource

    val running = new SyncVar[Throwable \/ IndexedSeq[Int]]
    Task.fork(sizeSig.discrete.runLog).runAsync(running.put)

    merge.mergeN(25)(ps).run.timed(10000).run
    sizeSig.close.run

    "mergeN and signal finished" |: running.get(3000).isDefined &&
      (s"max 25 were run in parallel ${running.get.toList.flatten}" |: running.get.toList.flatten.filter(_ > 25).isEmpty)

  }


  //tests that mergeN correctly terminates with drained process
  property("drain-halt") = secure {

    val effect = Process.repeatEval(Task.delay(())).drain
    val p = Process(1,2)

    merge.mergeN(Process(effect,p)).take(2)
    .runLog.timed(3000).run.size == 2

  }

  // tests that if one of the processes to mergeN is killed the mergeN is killed as well.
  property("drain-kill-one") = secure {
    import TestUtil._
    val effect = Process.repeatEval(Task.delay(())).drain
    val p = Process(1,2) onComplete Halt(Kill)

    val r =
      merge.mergeN(Process(effect,p))
      .expectedCause(_ == Kill)
      .runLog.timed(3000).run

    r.size == 2
  }

  property("kill mergeN") = secure {
    merge.mergeN(Process(Process.repeatEval(Task.now(1)))).kill.run.timed(3000).run
    true // Test terminates.
  }
}
