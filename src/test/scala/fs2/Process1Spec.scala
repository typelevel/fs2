package fs2

import fs2.Stream._
import fs2.TestUtil._
import fs2.process1._
import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

class Process1Spec extends Properties("process1") {

  property("chunks") = forAll(nonEmptyNestedVectorGen) { (v: Vector[Vector[Int]]) =>
    val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
    s.pipe(chunks).map(_.toVector) ==? v
  }

  // NB: this test currently fails
  property("chunks (2)") = forAll(nestedVectorGen[Int](0,10,emptyChunks = true)) { (v: Vector[Vector[Int]]) =>
    val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
    s.pipe(chunks).flatMap(Stream.chunk) ==? v.flatten
  }

  property("last") = forAll { (v: Vector[Int]) =>
    emits(v).pipe(last) ==? Vector(v.lastOption)
  }

  property("lift") = forAll { (v: Vector[Int]) =>
    emits(v).pipe(lift(_.toString)) ==? v.map(_.toString)
  }

  property("take") = forAll { (v: Vector[Int]) =>
    val n = Gen.choose(-1, 20).sample.get
    emits(v).pipe(take(n)) ==? v.take(n)
  }

  property("take.chunks") = secure {
    val s = Stream(1, 2) ++ Stream(3, 4)
    s.pipe(take(3)).pipe(chunks).map(_.toVector) ==? Vector(Vector(1, 2), Vector(3))
  }
}
