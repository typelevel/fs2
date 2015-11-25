package fs2

import TestUtil._

import tee._

import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

object TeeSpec extends Properties("tee") {

  property("interleave left/right side infinite") = protect {
    val ones = Pull.infinite("1").run
    val p = Stream("A","B","C")
    ones.interleave(p) ==? Vector("1", "A", "1", "B", "1", "C") &&
      p.interleave(ones) ==? Vector("A", "1", "B", "1", "C", "1")
  }

  property("interleave both side infinite") = protect {
    val ones = Pull.infinite("1").run
    val as = Pull.infinite("A").run
    ones.interleave(as).take(3) ==? Vector("1", "A", "1")
    as.interleave(ones).take(3) ==? Vector("A", "1", "A")
  }

  property("interleaveAll left/right side infinite") = protect {
    val ones = Pull.infinite("1").run
    val p = Stream("A","B","C")
    ones.interleaveAll(p).take(9) ==? Vector("1", "A", "1", "B", "1", "C", "1", "1", "1") &&
      p.interleaveAll(ones).take(9) ==? Vector("A", "1", "B", "1", "C", "1", "1", "1", "1")
  }

  property("interleaveAll both side infinite") = protect {
    val ones = Pull.infinite("1").run
    val as = Pull.infinite("A").run
    ones.interleaveAll(as).take(3) ==? Vector("1", "A", "1")
    as.interleaveAll(ones).take(3) ==? Vector("A", "1", "A")
  }

  // Uses a small scope to avoid using time to generate too large streams and not finishing
  property("interleave is equal to interleaveAll on infinite streams (by step-indexing)") = protect {
    forAll(Gen.choose(0,100)) { (n : Int) =>
      val ones = Pull.infinite("1").run
      val as = Pull.infinite("A").run
      ones.interleaveAll(as).take(n).toVector == ones.interleave(as).take(n).toVector
    }
  }
}
