package fs2

import fs2.Stream._
import fs2.TestUtil._
import fs2.process1._
import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

class Process1Spec extends Properties("process1") {

  property("last") = forAll { (v: Vector[Int]) =>
    emitAll(v).pipe(last) ==? Vector(v.lastOption)
  }

  property("lift") = forAll { (v: Vector[Int]) =>
    emitAll(v).pipe(lift(_.toString)) ==? v.map(_.toString)
  }

  property("take") = forAll { (v: Vector[Int]) =>
    val n = Gen.choose(-1, 20).sample.get
    emitAll(v).pipe(take(n)) ==? v.take(n)
  }

  // TODO: test chunkiness. How do we do this?
}
