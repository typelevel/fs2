package fs2

import fs2.Chunk.{Bits, Bytes, Doubles}
import fs2.Stream._
import fs2.TestUtil._
import fs2.process1._
import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}
import scodec.bits.{BitVector, ByteVector}

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

  property("delete") = forAll { (s: PureStream[Int]) =>
    val v = run(s.get)
    val i = Gen.oneOf(v).sample.getOrElse(0)
    s.get.delete(_ == i) ==? v.diff(Vector(i))
  }

  property("mapChunked") = forAll { (s: PureStream[Int]) =>
    s.get.mapChunks(identity).chunks ==? run(s.get.chunks)
  }
  
  property("collect") = forAll { (s: PureStream[Int]) =>
    val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
    s.get.pipe(fs2.process1.collect(pf)) ==? run(s.get).collect(pf)
  }

  property("filter") = forAll { (s: PureStream[Int], n: SmallPositive) =>
    val predicate = (i: Int) => i % n.get == 0
    s.get.filter(predicate) ==? run(s.get).filter(predicate)
  }

  property("filter (2)") = forAll { (s: PureStream[Double]) =>
    val predicate = (i: Double) => i - i.floor < 0.5
    val s2 = s.get.mapChunks(c => Chunk.doubles(c.iterator.toArray[Double]))
    s2.filter(predicate) ==? run(s2).filter(predicate)
  }

  property("filter (3)") = forAll { (s: PureStream[Byte]) =>
    val predicate = (b: Byte) => b < 0
    val s2 = s.get.mapChunks(c => Bytes(ByteVector(c.iterator.toArray[Byte])))
    s2.filter(predicate) ==? run(s2).filter(predicate)
  }

  property("filter (4)") = forAll { (s: PureStream[Boolean]) =>
    val predicate = (b: Boolean) => !b
    val s2 = s.get.mapChunks(c => Bits(BitVector.bits(c.iterator.toArray[Boolean])))
    s2.filter(predicate) ==? run(s2).filter(predicate)
  }

  property("fold") = forAll { (s: PureStream[Int], n: Int) =>
    val f = (a: Int, b: Int) => a + b
    s.get.fold(n)(f) ==? Vector(run(s.get).foldLeft(n)(f))
  }

  property("fold (2)") = forAll { (s: PureStream[Int], n: String) =>
    val f = (a: String, b: Int) => a + b
    s.get.fold(n)(f) ==? Vector(run(s.get).foldLeft(n)(f))
  }

  property("fold1") = forAll { (s: PureStream[Int]) =>
    val v = run(s.get)
    val f = (a: Int, b: Int) => a + b
    s.get.fold1(f) ==? v.headOption.fold(Vector.empty[Int])(h => Vector(v.drop(1).foldLeft(h)(f)))
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

  property("last") = forAll { (s: PureStream[Int]) =>
    val shouldCompile = s.get.last
    s.get.pipe(last) ==? Vector(run(s.get).lastOption)
  }

  property("lift") = forAll { (s: PureStream[Double]) =>
    s.get.pipe(lift(_.toString)) ==? run(s.get).map(_.toString)
  }

  property("take") = forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
    val n = if (negate) -n0.get else n0.get
    s.get.take(n) ==? run(s.get).take(n)
  }

  property("takeWhile") = forAll { (s: PureStream[Int], n: SmallNonnegative) =>
    val set = run(s.get).take(n.get).toSet    
    s.get.pipe(takeWhile(set)) ==? run(s.get).takeWhile(set)
  }
  
  property("drop") = forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
    val n = if (negate) -n0.get else n0.get
    s.get.pipe(drop(n)) ==? run(s.get).drop(n)    
  }
  
  property("dropWhile") = forAll { (s: PureStream[Int], n: SmallNonnegative) =>
    val set = run(s.get).take(n.get).toSet    
    s.get.pipe(dropWhile(set)) ==? run(s.get).dropWhile(set)
  }

  property("scan") = forAll { (s: PureStream[Int], n: Int) =>
    val f = (a: Int, b: Int) => a + b
    s.get.scan(n)(f) ==? run(s.get).scanLeft(n)(f)
  }

  property("scan (2)") = forAll { (s: PureStream[Int], n: String) =>
    val f = (a: String, b: Int) => a + b
    s.get.scan(n)(f) ==? run(s.get).scanLeft(n)(f)
  }

  property("scan1") = forAll { (s: PureStream[Int]) =>
    val v = run(s.get)
    val f = (a: Int, b: Int) => a + b
    s.get.scan1(f) ==? v.headOption.fold(Vector.empty[Int])(h => v.drop(1).scanLeft(h)(f))
  }

  property("take.chunks") = secure {
    val s = Stream(1, 2) ++ Stream(3, 4)
    s.pipe(take(3)).pipe(chunks).map(_.toVector) ==? Vector(Vector(1, 2), Vector(3))
  }
  
  property("zipWithIndex") = forAll { (s: PureStream[Int]) =>
    s.get.pipe(zipWithIndex) ==? run(s.get).zipWithIndex
  }
}
