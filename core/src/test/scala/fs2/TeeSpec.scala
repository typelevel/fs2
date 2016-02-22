package fs2

import TestUtil._

import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

object TeeSpec extends Properties("tee") {

  property("zipWith left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.zipWith(p)(_ + _) ==? Vector("1A", "1B", "1C") &&
      p.zipWith(ones)(_ + _) ==? Vector("A1", "B1", "C1")
  }

  property("zipWith both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.zipWith(as)(_ + _).take(3) ==? Vector("1A", "1A", "1A") &&
      as.zipWith(ones)(_ + _).take(3) ==? Vector("A1", "A1", "A1")
  }

  property("zipAllWith left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.tee(p)(tee.zipAllWith("2","Z")(_ + _)).take(5) ==?
        Vector("1A", "1B", "1C", "1Z", "1Z") &&
      p.tee(ones)(tee.zipAllWith("Z","2")(_ + _)).take(5) ==?
        Vector("A1", "B1", "C1", "Z1", "Z1")
  }

  property("zipAllWith both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.tee(as)(tee.zipAllWith("2", "Z")(_ + _)).take(3) ==?
     Vector("1A", "1A", "1A") &&
    as.tee(ones)(tee.zipAllWith("Z", "2")(_ + _)).take(3) ==?
     Vector("A1", "A1", "A1")
  }

  property("zip left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.zip(p) ==? Vector("1" -> "A", "1" -> "B", "1" -> "C") &&
      p.zip(ones) ==? Vector("A" -> "1", "B" -> "1", "C" -> "1")
  }

  property("zip both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.zip(as).take(3) ==? Vector("1" -> "A", "1" -> "A", "1" -> "A") &&
      as.zip(ones).take(3) ==? Vector("A" -> "1", "A" -> "1", "A" -> "1")
  }

  property("zipAll left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.tee(p)(tee.zipAll("2","Z")).take(5) ==? Vector("1" -> "A", "1" -> "B", "1" -> "C", "1" -> "Z", "1" -> "Z") &&
      p.tee(ones)(tee.zipAll("Z","2")).take(5) ==? Vector("A" -> "1", "B" -> "1", "C" -> "1", "Z" -> "1", "Z" -> "1")
  }

  property("zipAll both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.tee(as)(tee.zipAll("2", "Z")).take(3) ==? Vector("1" -> "A", "1" -> "A", "1" -> "A") &&
      as.tee(ones)(tee.zipAll("Z", "2")).take(3) ==? Vector("A" -> "1", "A" -> "1", "A" -> "1")
  }

  property("interleave left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.interleave(p) ==? Vector("1", "A", "1", "B", "1", "C") &&
      p.interleave(ones) ==? Vector("A", "1", "B", "1", "C", "1")
  }

  property("interleave both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.interleave(as).take(3) ==? Vector("1", "A", "1") &&
      as.interleave(ones).take(3) ==? Vector("A", "1", "A")
  }

  property("interleaveAll left/right side infinite") = protect {
    val ones = Stream.constant("1")
    val p = Stream("A","B","C")
    ones.interleaveAll(p).take(9) ==? Vector("1", "A", "1", "B", "1", "C", "1", "1", "1") &&
      p.interleaveAll(ones).take(9) ==? Vector("A", "1", "B", "1", "C", "1", "1", "1", "1")
  }

  property("interleaveAll both side infinite") = protect {
    val ones = Stream.constant("1")
    val as = Stream.constant("A")
    ones.interleaveAll(as).take(3) ==? Vector("1", "A", "1") &&
      as.interleaveAll(ones).take(3) ==? Vector("A", "1", "A")
  }

  // Uses a small scope to avoid using time to generate too large streams and not finishing
  property("interleave is equal to interleaveAll on infinite streams (by step-indexing)") = protect {
    forAll(Gen.choose(0,100)) { (n : Int) =>
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      ones.interleaveAll(as).take(n).toVector == ones.interleave(as).take(n).toVector
    }
  }
}
