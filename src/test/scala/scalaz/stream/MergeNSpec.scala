package scalaz.stream

import Process._
import org.scalacheck.Prop._
import org.scalacheck.Properties


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

}
