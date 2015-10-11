package fs2

import fs2.Stream._
import fs2.TestUtil._
import fs2.process1._
import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

class Process1Spec extends Properties("process1") {

  property("chunks") = forAll(nonEmptyNestedVectorGen) { (v: Vector[Vector[Int]]) =>
    val s = v.map(emitAll).reduce(_ ++ _)
    s.pipe(chunks).map(_.toVector) ==? v
  }

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

  property("take.chunks") = secure {
    val s = Stream(1, 2) ++ Stream(3, 4)
    s.pipe(take(3)).pipe(chunks).map(_.toVector) ==? Vector(Vector(1, 2), Vector(3))
  }
}
