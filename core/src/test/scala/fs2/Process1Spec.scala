package fs2

import fs2.Stream._
import fs2.TestUtil._
import fs2.process1._
import fs2.util.Task
import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}

object Process1Spec extends Properties("process1") {

  property("chunkLimit") = forAll { (s: PureStream[Int], n0: SmallPositive) =>
    val sizeV = s.get.chunkLimit(n0.get).toVector.map(_.size)
    sizeV.forall(_ <= n0.get) && sizeV.sum == s.get.toVector.size
  }

  property("chunkN.fewer") = forAll { (s: PureStream[Int], n0: SmallPositive) =>
    val chunkedV = s.get.chunkN(n0.get, true).toVector
    val unchunkedV = s.get.toVector
    // All but last list have n0 values
    chunkedV.dropRight(1).forall(_.map(_.size).sum == n0.get) &&
      // Last list has at most n0 values
      chunkedV.lastOption.fold(true)(_.map(_.size).sum <= n0.get) &&
      // Flattened sequence is equal to vector without chunking
      chunkedV.foldLeft(Vector.empty[Int])((v, l) =>v ++ l.foldLeft(Vector.empty[Int])((v, c) => v ++ c.iterator)) == unchunkedV
  }

  property("chunkN.no-fewer") = forAll { (s: PureStream[Int], n0: SmallPositive) =>
    val chunkedV = s.get.chunkN(n0.get, false).toVector
    val unchunkedV = s.get.toVector
    val expectedSize = unchunkedV.size - (unchunkedV.size % n0.get)
    // All lists have n0 values
    chunkedV.forall(_.map(_.size).sum == n0.get) &&
      // Flattened sequence is equal to vector without chunking, minus "left over" values that could not fit in a chunk
      chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.foldLeft(Vector.empty[Int])((v, c) => v ++ c.iterator)) == unchunkedV.take(expectedSize)
  }

  property("chunks") = forAll(nonEmptyNestedVectorGen) { (v0: Vector[Vector[Int]]) =>
    val v = Vector(Vector(11,2,2,2), Vector(2,2,3), Vector(2,3,4), Vector(1,2,2,2,2,2,3,3))
    val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
    s.pipe(chunks).map(_.toVector) ==? v
  }

  property("chunks (2)") = forAll(nestedVectorGen[Int](0,10, emptyChunks = true)) { (v: Vector[Vector[Int]]) =>
    val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
    s.pipe(chunks).flatMap(Stream.chunk) ==? v.flatten
  }

  property("collect") = forAll { (s: PureStream[Int]) =>
    val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
    s.get.pipe(fs2.process1.collect(pf)) ==? run(s.get).collect(pf)
  }

  property("collectFirst") = forAll { (s: PureStream[Int]) =>
    val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
    s.get.collectFirst(pf) ==? run(s.get).collectFirst(pf).toVector
  }

  property("delete") = forAll { (s: PureStream[Int]) =>
    val v = run(s.get)
    val i = Gen.oneOf(v).sample.getOrElse(0)
    s.get.delete(_ == i) ==? v.diff(Vector(i))
  }

  property("drop") = forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
    val n = if (negate) -n0.get else n0.get
    s.get.pipe(drop(n)) ==? run(s.get).drop(n)
  }

  property("dropWhile") = forAll { (s: PureStream[Int], n: SmallNonnegative) =>
    val set = run(s.get).take(n.get).toSet
    s.get.pipe(dropWhile(set)) ==? run(s.get).dropWhile(set)
  }

  property("exists") = forAll { (s: PureStream[Int], n: SmallPositive) =>
    val f = (i: Int) => i % n.get == 0
    s.get.exists(f) ==? Vector(run(s.get).exists(f))
  }

  property("filter") = forAll { (s: PureStream[Int], n: SmallPositive) =>
    val predicate = (i: Int) => i % n.get == 0
    s.get.filter(predicate) ==? run(s.get).filter(predicate)
  }

  property("filter (2)") = forAll { (s: PureStream[Double]) =>
    val predicate = (i: Double) => i - i.floor < 0.5
    val s2 = s.get.mapChunks(c => Chunk.doubles(c.toArray))
    s2.filter(predicate) ==? run(s2).filter(predicate)
  }

  property("filter (3)") = forAll { (s: PureStream[Byte]) =>
    val predicate = (b: Byte) => b < 0
    val s2 = s.get.mapChunks(c => Chunk.bytes(c.toArray))
    s2.filter(predicate) ==? run(s2).filter(predicate)
  }

  property("filter (4)") = forAll { (s: PureStream[Boolean]) =>
    val predicate = (b: Boolean) => !b
    val s2 = s.get.mapChunks(c => Chunk.booleans(c.toArray))
    s2.filter(predicate) ==? run(s2).filter(predicate)
  }

  property("find") = forAll { (s: PureStream[Int], i: Int) =>
    val predicate = (item: Int) => item < i
    s.get.find(predicate) ==? run(s.get).find(predicate).toVector
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

  property("forall") = forAll { (s: PureStream[Int], n: SmallPositive) =>
    val f = (i: Int) => i % n.get == 0
    s.get.forall(f) ==? Vector(run(s.get).forall(f))
  }

  property("mapChunked") = forAll { (s: PureStream[Int]) =>
    s.get.mapChunks(identity).chunks ==? run(s.get.chunks)
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

  property("lastOr") = forAll { (s: PureStream[Int], n: SmallPositive) =>
    val default = n.get
    s.get.lastOr(default) ==? Vector(run(s.get).lastOption.getOrElse(default))
  }

  property("lift") = forAll { (s: PureStream[Double]) =>
    s.get.pipe(lift(_.toString)) ==? run(s.get).map(_.toString)
  }

  property("mapAccumulate") = forAll { (s: PureStream[Int], n0: Int, n1: SmallPositive) =>
    val f = (_: Int) % n1.get == 0
    val r = s.get.mapAccumulate(n0)((s, i) => (s + i, f(i)))

    r.map(_._1) ==? run(s.get).scan(n0)(_ + _).tail
    r.map(_._2) ==? run(s.get).map(f)
  }

  property("prefetch") = forAll { (s: PureStream[Int]) =>
    s.get.covary[Task].through(prefetch) ==? run(s.get)
  }

  property("prefetch (timing)") = secure {
    // should finish in about 3-4 seconds
    val s = Stream(1,2,3)
          . evalMap(i => Task.delay { Thread.sleep(1000); i })
          . through(prefetch)
          . flatMap { i => Stream.eval(Task.delay { Thread.sleep(1000); i}) }
    val start = System.currentTimeMillis
    run(s)
    val stop = System.currentTimeMillis
    println("prefetch (timing) took " + (stop-start) + " milliseconds, should be under 6000 milliseconds")
    (stop-start) < 6000
  }

  property("sum") = forAll { (s: PureStream[Int]) =>
    s.get.sum ==? Vector(run(s.get).sum)
  }

  property("sum (2)") = forAll { (s: PureStream[Double]) =>
    s.get.sum ==? Vector(run(s.get).sum)
  }

  property("take") = forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
    val n = if (negate) -n0.get else n0.get
    s.get.take(n) ==? run(s.get).take(n)
  }

  property("takeRight") = forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
    val n = if (negate) -n0.get else n0.get
    s.get.takeRight(n) ==? run(s.get).takeRight(n)
  }

  property("takeWhile") = forAll { (s: PureStream[Int], n: SmallNonnegative) =>
    val set = run(s.get).take(n.get).toSet
    s.get.pipe(takeWhile(set)) ==? run(s.get).takeWhile(set)
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

  property("tail") = forAll { (s: PureStream[Int]) =>
    s.get.tail ==? run(s.get).drop(1)
  }

  property("take.chunks") = secure {
    val s = Stream(1, 2) ++ Stream(3, 4)
    s.pipe(take(3)).pipe(chunks).map(_.toVector) ==? Vector(Vector(1, 2), Vector(3))
  }

  property("vectorChunkN") = forAll { (s: PureStream[Int], n: SmallPositive) =>
    s.get.vectorChunkN(n.get) ==? run(s.get).grouped(n.get).toVector
  }

  property("zipWithIndex") = forAll { (s: PureStream[Int]) =>
    s.get.pipe(zipWithIndex) ==? run(s.get).zipWithIndex
  }

  property("zipWithNext") = forAll { (s: PureStream[Int]) =>
    s.get.pipe(zipWithNext) ==? {
      val xs = run(s.get)
      xs.zipAll(xs.map(Some(_)).drop(1), -1, None)
    }
  }

  property("zipWithNext (2)") = protect {
    Stream().zipWithNext === Vector() &&
    Stream(0).zipWithNext === Vector((0, None)) &&
    Stream(0, 1, 2).zipWithNext === Vector((0, Some(1)), (1, Some(2)), (2, None))
  }

  property("zipWithPrevious") = forAll { (s: PureStream[Int]) =>
    s.get.pipe(zipWithPrevious) ==? {
      val xs = run(s.get)
      (None +: xs.map(Some(_))).zip(xs)
    }
  }

  property("zipWithPrevious (2)") = protect {
    Stream().zipWithPrevious === Vector() &&
    Stream(0).zipWithPrevious === Vector((None, 0)) &&
    Stream(0, 1, 2).zipWithPrevious === Vector((None, 0), (Some(0), 1), (Some(1), 2))
  }

  property("zipWithPreviousAndNext") = forAll { (s: PureStream[Int]) =>
    s.get.pipe(zipWithPreviousAndNext) ==? {
      val xs = run(s.get)
      val zipWithPrevious = (None +: xs.map(Some(_))).zip(xs)
      val zipWithPreviousAndNext = zipWithPrevious
        .zipAll(xs.map(Some(_)).drop(1), (None, -1), None)
        .map { case ((prev, that), next) => (prev, that, next) }

      zipWithPreviousAndNext
    }
  }

  property("zipWithPreviousAndNext (2)") = protect {
    Stream().zipWithPreviousAndNext === Vector() &&
    Stream(0).zipWithPreviousAndNext === Vector((None, 0, None)) &&
    Stream(0, 1, 2).zipWithPreviousAndNext === Vector((None, 0, Some(1)), (Some(0), 1, Some(2)), (Some(1), 2, None))
  }
}
