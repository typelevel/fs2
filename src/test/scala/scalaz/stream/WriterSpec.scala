package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties

import scalaz.std.anyVal._
import scalaz.stream.Process._
import scalaz.stream.TestInstances._
import scalaz.syntax.equal._

object WriterSpec extends Properties("Writer") {
  property("drainO ~= liftW . stripO") = forAll { w0: Writer[Nothing, Char, Int] =>
    val w = w0.toSource
    w.drainO.runLog.run == writer.liftW(w.stripO).runLog.run
  }

  property("drainW ~= liftO . stripW") = forAll { w0: Writer[Nothing, Char, Int] =>
    val w = w0.toSource
    w.drainW.runLog.run == writer.liftO(w.stripW).runLog.run
  }

  property("pipeO stripW ~= stripW pipe") = forAll { (p1: Process1[Int,Int]) =>
    val p = writer.logged(range(1, 11).toSource)
    p.pipeO(p1).stripW === p.stripW.pipe(p1)
  }

  property("pipeW stripO ~= stripO pipe") = forAll { (p1: Process1[Int,Int]) =>
    val p = writer.logged(range(1, 11).toSource)
    p.pipeW(p1).stripO === p.stripO.pipe(p1)
  }
}
