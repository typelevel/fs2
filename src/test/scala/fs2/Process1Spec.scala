package fs2

import fs2.Stream._
import fs2.TestUtil._
import fs2.process1._
import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

object Process1Spec extends Properties("process1") {

  property("chunks") = forAll(nonEmptyNestedVectorGen) { (v0: Vector[Vector[Int]]) =>
    val v = Vector(Vector(11,2,2,2), Vector(2,2,3), Vector(2,3,4), Vector(1,2,2,2,2,2,3,3))
    val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
    s.pipe(chunks).map(_.toVector) ==? v
  }

  property("chunks (2)") = forAll(nestedVectorGen[Int](0,10, emptyChunks = true)) { (v: Vector[Vector[Int]]) =>
    val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
    s.pipe(chunks).flatMap(Stream.chunk) ==? v.flatten
  }

  property("filter") = forAll { (i: Int) =>
    val predicate = (i: Int) => i % 2 == 0
    emit(i).filter(predicate) ==? Vector(i).filter(predicate)
  }

  property("filter (2)") = forAll { (v: Vector[Int]) =>
    val predicate = (i: Int) => i % 2 == 0
    emits(v).filter(predicate) ==? v.filter(predicate)
  }

  property("filter (3)") = forAll { (v: Array[Double]) =>
    val predicate = (i: Double) => i - i.floor < 0.5
    emits(v).filter(predicate) ==? v.toVector.filter(predicate)
  }

  property("performance of multi-stage pipeline") = secure {
    println("checking performance of multistage pipeline... this should finish quickly")
    val v = Vector.fill(1000)(Vector.empty[Int])
    val v2 = Vector.fill(1000)(Vector(0))
    val s = (v.map(Stream.emits): Vector[Stream[Pure,Int]]).reduce(_ ++ _)
    val s2 = (v2.map(Stream.emits(_)): Vector[Stream[Pure,Int]]).reduce(_ ++ _)
    val start = System.currentTimeMillis
    s.pipe(process1.id).pipe(process1.id).pipe(process1.id).pipe(process1.id).pipe(process1.id) ==? Vector()
    s2.pipe(process1.id).pipe(process1.id).pipe(process1.id).pipe(process1.id).pipe(process1.id) ==? Vector.fill(1000)(0)
    println("done checking performance; took " + (System.currentTimeMillis - start) + " milliseconds")
    true
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
