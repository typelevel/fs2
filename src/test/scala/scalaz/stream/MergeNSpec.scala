package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties
import scalaz.concurrent.Task
import scalaz.stream.Process._
import java.util.concurrent.atomic.AtomicInteger


object MergeNSpec extends Properties("mergeN") {


  property("basic") = forAll {
    (l: List[Int]) =>

      val count = (l.size % 6) max 1

      val ps =
        emitSeq(for (i <- 0 until count) yield {
          emitSeq(l.filter(v => (v % count).abs == i)).toSource
        }).toSource

      val result =
        merge.mergeN(ps).runLog.timed(3000).run

      (result.sorted.toList == l.sorted.toList) :| "All elements were collected"

  }


  property("source-cleanup-down-done") = secure {
    val cleanups = new AtomicInteger(0)
    val srcCleanup = new AtomicInteger(0)

    val ps =
      emitSeq(for (i <- 0 until 10) yield {
        (Process.constant(i+100) onComplete eval(Task.delay(cleanups.incrementAndGet())))
      }).toSource onComplete eval_(Task.delay(srcCleanup.set(99)))



    //this makes sure we see at least one value from sources
    // and therefore we won`t terminate downstream to early.
    val result =
     merge.mergeN(ps).scan(Set[Int]())({
       case (sum, next) => sum + next
     }).takeWhile(_.size < 10).runLog.timed(3000).run

    (cleanups.get == 10) :| s"Cleanups were called on upstreams: ${cleanups.get}" &&
      (srcCleanup.get == 99) :| "Cleanup on source was called"
  }

  //merges 10k of streams, each with 100 of elements
  property("merge-million") = secure {
    val count = 10000
    val eachSize = 100

    val ps =
          emitSeq(for (i <- 0 until count) yield {
            Process.range(0,eachSize)
          }).toSource

    val result = merge.mergeN(ps).fold(0)(_ + _).runLast.timed(30000).run

    (result == Some(49500000)) :| "All items were emitted"
  }

}
