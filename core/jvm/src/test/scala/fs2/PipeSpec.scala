package fs2

import java.util.concurrent.atomic.AtomicLong
import org.scalacheck.Gen

import cats.effect.IO
import cats.implicits._
import scala.concurrent.duration._

import fs2.Stream._

class PipeSpec extends Fs2Spec {

  "Pipe" - {

    "buffer" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      runLog { s.get.buffer(n.get) } shouldBe s.get.toVector

      var counter = 0
      val s2 = (s.get ++ Stream.emits(List.fill(n.get + 1)(0))).repeat
      runLog { s2.evalMap { i => IO { counter += 1; i }}.buffer(n.get).take(n.get + 1) }
      counter shouldBe (n.get * 2)
    }

    "bufferAll" in forAll { (s: PureStream[Int]) =>
      runLog { s.get.bufferAll } shouldBe s.get.toVector
      var counter = 0
      runLog { (s.get ++ s.get).evalMap { i => IO { counter += 1; i } }.bufferAll.take(s.get.toList.size + 1) }
      counter shouldBe (s.get.toList.size * 2)
    }

    "bufferBy" in forAll { (s: PureStream[Int]) =>
      runLog { s.get.bufferBy(_ >= 0) } shouldBe s.get.toVector

      var counter = 0
      val s2 = s.get.map(x => if (x == Int.MinValue) x + 1 else x).map(_.abs)
      val s3 = (s2 ++ Stream.emit(-1) ++ s2).evalMap { i => IO { counter += 1; i }}
      runLog { s3.bufferBy(_ >= 0).take(s.get.toList.size + 2) }
      counter shouldBe (s.get.toList.size * 2 + 1)
    }

    "changes" in {
      Stream.empty.covaryOutput[Int].changes.toList shouldBe Nil
      Stream(1, 2, 3, 4).changes.toList shouldBe List(1, 2, 3, 4)
      Stream(1, 1, 2, 2, 3, 3, 4, 3).changes.toList shouldBe List(1, 2, 3, 4, 3)
      Stream("1", "2", "33", "44", "5", "66").changesBy(_.length).toList shouldBe
        List("1", "33", "5", "66")
    }

    "chunkLimit" in forAll { (s: PureStream[Int], n0: SmallPositive) =>
      val sizeV = s.get.chunkLimit(n0.get).toVector.map(_.size)
      assert(sizeV.forall(_ <= n0.get) && sizeV.combineAll == s.get.toVector.size)
    }

    "segmentN.fewer" in forAll { (s: PureStream[Int], n0: SmallPositive) =>
      val segmentedV = s.get.segmentN(n0.get, true).toVector
      val unsegmentedV = s.get.toVector
      assert {
        // All but last list have n0 values
        segmentedV.dropRight(1).forall(_.toChunk.size == n0.get) &&
        // Last list has at most n0 values
        segmentedV.lastOption.fold(true)(_.toChunk.size <= n0.get) &&
        // Flattened sequence is equal to vector without segmenting
        segmentedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) == unsegmentedV
      }
    }

    "segmentN.no-fewer" in forAll { (s: PureStream[Int], n0: SmallPositive) =>
      val segmentedV = s.get.segmentN(n0.get, false).toVector
      val unsegmentedV = s.get.toVector
      val expectedSize = unsegmentedV.size - (unsegmentedV.size % n0.get)
      assert {
        // All lists have n0 values
        segmentedV.forall(_.toChunk.size == n0.get) &&
        // Flattened sequence is equal to vector without segmenting, minus "left over" values that could not fit in a segment
        segmentedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) == unsegmentedV.take(expectedSize)
      }
    }

    "chunks" in forAll(nonEmptyNestedVectorGen) { (v0: Vector[Vector[Int]]) =>
      val v = Vector(Vector(11,2,2,2), Vector(2,2,3), Vector(2,3,4), Vector(1,2,2,2,2,2,3,3))
      val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
      runLog(s.chunks.map(_.toVector)) shouldBe v
    }

    "chunks (2)" in forAll(nestedVectorGen[Int](0,10, emptyChunks = true)) { (v: Vector[Vector[Int]]) =>
      val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
      runLog(s.chunks.flatMap(Stream.chunk(_))) shouldBe v.flatten
    }

    "collect" in forAll { (s: PureStream[Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      runLog(s.get.collect(pf)) shouldBe runLog(s.get).collect(pf)
    }

    "collectFirst" in forAll { (s: PureStream[Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      runLog(s.get.collectFirst(pf)) shouldBe runLog(s.get).collectFirst(pf).toVector
    }

    "delete" in forAll { (s: PureStream[Int]) =>
      val v = runLog(s.get)
      val i = if (v.isEmpty) 0 else Gen.oneOf(v).sample.getOrElse(0)
      runLog(s.get.delete(_ == i)) shouldBe v.diff(Vector(i))
    }

    "drop" in forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
      val n = if (negate) -n0.get else n0.get
      runLog(s.get.drop(n)) shouldBe runLog(s.get).drop(n)
    }

    "dropLast" in forAll { (s: PureStream[Int]) =>
      runLog { s.get.dropLast } shouldBe s.get.toVector.dropRight(1)
    }

    "dropLastIf" in forAll { (s: PureStream[Int]) =>
      runLog { s.get.dropLastIf(_ => false) } shouldBe s.get.toVector
      runLog { s.get.dropLastIf(_ => true) } shouldBe s.get.toVector.dropRight(1)
    }

    "dropRight" in forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
      val n = if (negate) -n0.get else n0.get
      runLog(s.get.dropRight(n)) shouldBe runLog(s.get).dropRight(n)
    }

    "dropWhile" in forAll { (s: PureStream[Int], n: SmallNonnegative) =>
      val set = runLog(s.get).take(n.get).toSet
      runLog(s.get.dropWhile(set)) shouldBe runLog(s.get).dropWhile(set)
    }

    "dropThrough" in forAll { (s: PureStream[Int], n: SmallNonnegative) =>
      val set = runLog(s.get).take(n.get).toSet
      runLog(s.get.dropThrough(set)) shouldBe {
        val vec = runLog(s.get).dropWhile(set)
        if (vec.isEmpty) vec else vec.tail
      }
    }

    "evalScan" in forAll { (s: PureStream[Int], n: String) =>
      val f: (String, Int) => IO[String] = (a: String, b: Int) => IO.pure(a + b)
      val g = (a: String, b: Int) => a + b
      runLog(s.get.covary[IO].evalScan(n)(f)) shouldBe runLog(s.get).scanLeft(n)(g)
    }

    "exists" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get == 0
      runLog(s.get.exists(f)) shouldBe Vector(runLog(s.get).exists(f))
    }

    "filter" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val predicate = (i: Int) => i % n.get == 0
      runLog(s.get.filter(predicate)) shouldBe runLog(s.get).filter(predicate)
    }

    "filter (2)" in forAll { (s: PureStream[Double]) =>
      val predicate = (i: Double) => i - i.floor < 0.5
      val s2 = s.get.mapSegments(s => Chunk.doubles(s.toVector.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "filter (3)" in forAll { (s: PureStream[Byte]) =>
      val predicate = (b: Byte) => b < 0
      val s2 = s.get.mapSegments(s => Chunk.bytes(s.toVector.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "filter (4)" in forAll { (s: PureStream[Boolean]) =>
      val predicate = (b: Boolean) => !b
      val s2 = s.get.mapSegments(s => Chunk.booleans(s.toVector.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "find" in forAll { (s: PureStream[Int], i: Int) =>
      val predicate = (item: Int) => item < i
      runLog(s.get.find(predicate)) shouldBe runLog(s.get).find(predicate).toVector
    }

    "fold" in forAll { (s: PureStream[Int], n: Int) =>
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.fold(n)(f)) shouldBe Vector(runLog(s.get).foldLeft(n)(f))
    }

    "fold (2)" in forAll { (s: PureStream[Int], n: String) =>
      val f = (a: String, b: Int) => a + b
      runLog(s.get.fold(n)(f)) shouldBe Vector(runLog(s.get).foldLeft(n)(f))
    }

    "foldMonoid" in forAll { (s: PureStream[Int]) =>
      s.get.foldMonoid.toVector shouldBe Vector(runLog(s.get).combineAll)
    }

    "foldMonoid (2)" in forAll { (s: PureStream[Double]) =>
      s.get.foldMonoid.toVector shouldBe Vector(runLog(s.get).combineAll)
    }

    "fold1" in forAll { (s: PureStream[Int]) =>
      val v = runLog(s.get)
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.fold1(f)) shouldBe v.headOption.fold(Vector.empty[Int])(h => Vector(v.drop(1).foldLeft(h)(f)))
    }

    "forall" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get == 0
      runLog(s.get.forall(f)) shouldBe Vector(runLog(s.get).forall(f))
    }

    "groupBy" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get
      val s1 = s.get.groupBy(f)
      val s2 = s.get.map(f).changes
      runLog(s1.map(_._2)).flatten shouldBe runLog(s.get)
      runLog(s1.map(_._1)) shouldBe runLog(s2)
      runLog(s1.map { case (k, vs) => vs.forall(f(_) == k) }) shouldBe runLog(s2.map(_ => true))
    }

    "head" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.head) shouldBe runLog(s.get).take(1)
    }

    "intersperse" in forAll { (s: PureStream[Int], n: Int) =>
      runLog(s.get.intersperse(n)) shouldBe runLog(s.get).flatMap(i => Vector(i, n)).dropRight(1)
    }

    "mapSegments" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.mapSegments(identity).segments) shouldBe runLog(s.get.segments)
    }

    "performance of multi-stage pipeline" in {
      val v = Vector.fill(1000)(Vector.empty[Int])
      val v2 = Vector.fill(1000)(Vector(0))
      val s = (v.map(Stream.emits(_)): Vector[Stream[Pure,Int]]).reduce(_ ++ _)
      val s2 = (v2.map(Stream.emits(_)): Vector[Stream[Pure,Int]]).reduce(_ ++ _)
      val id = (_: Stream[Pure,Int]).mapSegments(s => s)
      runLog(s.through(id).through(id).through(id).through(id).through(id)) shouldBe Vector()
      runLog(s2.through(id).through(id).through(id).through(id).through(id)) shouldBe Vector.fill(1000)(0)
    }

    "last" in forAll { (s: PureStream[Int]) =>
      val _ = s.get.last
      runLog(s.get.last) shouldBe Vector(runLog(s.get).lastOption)
    }

    "lastOr" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val default = n.get
      runLog(s.get.lastOr(default)) shouldBe Vector(runLog(s.get).lastOption.getOrElse(default))
    }

    "mapAccumulate" in forAll { (s: PureStream[Int], n0: Int, n1: SmallPositive) =>
      val f = (_: Int) % n1.get == 0
      val r = s.get.mapAccumulate(n0)((s, i) => (s + i, f(i)))

      runLog(r.map(_._1)) shouldBe runLog(s.get).scan(n0)(_ + _).tail
      runLog(r.map(_._2)) shouldBe runLog(s.get).map(f)
    }

    "prefetch" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.covary[IO].prefetch) shouldBe runLog(s.get)
    }

    "prefetch (timing)" in {
      // should finish in about 3-4 seconds
      val s = Stream(1,2,3).
               evalMap(i => IO { Thread.sleep(1000); i }).
               prefetch.
               flatMap { i => Stream.eval(IO { Thread.sleep(1000); i}) }
      val start = System.currentTimeMillis
      runLog(s)
      val stop = System.currentTimeMillis
      println("prefetch (timing) took " + (stop-start) + " milliseconds, should be under 6000 milliseconds")
      assert((stop-start) < 6000)
    }

    "sliding" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      s.get.sliding(n.get).toList.map(_.toList) shouldBe s.get.toList.sliding(n.get).map(_.toList).toList
    }

    "split" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val s2 = s.get.map(x => if (x == Int.MinValue) x + 1 else x).map(_.abs).filter(_ != 0)
      withClue(s"n = $n, s = ${s.get.toList}, s2 = " + s2.toList) {
      runLog { s2.chunkLimit(n.get).intersperse(Chunk.singleton(0)).flatMap(Stream.chunk).split(_ == 0).map(_.toVector).filter(_.nonEmpty) } shouldBe
        s2.chunkLimit(n.get).filter(_.nonEmpty).map(_.toVector).toVector
      }
    }

    "split (2)" in {
      Stream(1, 2, 0, 0, 3, 0, 4).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1, 2), Vector(), Vector(3), Vector(4))
      Stream(1, 2, 0, 0, 3, 0).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1, 2), Vector(), Vector(3))
      Stream(1, 2, 0, 0, 3, 0, 0).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1, 2), Vector(), Vector(3), Vector())
    }

    "take" in forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
      val n = if (negate) -n0.get else n0.get
      runLog(s.get.take(n)) shouldBe runLog(s.get).take(n)
    }

    "takeRight" in forAll { (s: PureStream[Int], negate: Boolean, n0: SmallNonnegative) =>
      val n = if (negate) -n0.get else n0.get
      runLog(s.get.takeRight(n)) shouldBe runLog(s.get).takeRight(n)
    }

    "takeWhile" in forAll { (s: PureStream[Int], n: SmallNonnegative) =>
      val set = runLog(s.get).take(n.get).toSet
      runLog(s.get.takeWhile(set)) shouldBe runLog(s.get).takeWhile(set)
    }

    "takeThrough" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get == 0
      val vec = runLog(s.get)
      val result = vec.takeWhile(f) ++ vec.dropWhile(f).headOption
      withClue(s.get.toList)(runLog(s.get.takeThrough(f)) shouldBe result)
    }

    "scan (simple ex)" in {
      val s = PureStream("simple", Stream(1).map(x => x)) // note, without the .map, test passes
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.scan(0)(f)) shouldBe runLog(s.get).scanLeft(0)(f)
    }

    "scan (temporal)" in {
      val never = Stream.eval(IO.async[Int](_ => ()))
      val s = Stream(1)
      val f = (a: Int, b: Int) => a + b
      val result = s.toVector.scan(0)(f)
      runLog((s ++ never).scan(0)(f).take(result.size), timeout = 1 second) shouldBe result
    }

    "scan" in forAll { (s: PureStream[Int], n: Int) =>
      val f = (a: Int, b: Int) => a + b
      try runLog(s.get.scan(n)(f)) shouldBe runLog(s.get).scanLeft(n)(f)
      catch { case e: Throwable =>
        println(s.get.toList)
        throw e
      }
    }

    "scan (2)" in forAll { (s: PureStream[Int], n: String) =>
      val f = (a: String, b: Int) => a + b
      runLog(s.get.scan(n)(f)) shouldBe runLog(s.get).scanLeft(n)(f)
    }

    "scan1" in forAll { (s: PureStream[Int]) =>
      val v = runLog(s.get)
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.scan1(f)) shouldBe v.headOption.fold(Vector.empty[Int])(h => v.drop(1).scanLeft(h)(f))
    }

    "tail" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.tail) shouldBe runLog(s.get).drop(1)
    }

    "take.segments" in {
      val s = Stream(1, 2) ++ Stream(3, 4)
      runLog(s.take(3).segments.map(_.toVector)) shouldBe Vector(Vector(1, 2), Vector(3))
    }

    "unNone" in forAll { (s: PureStream[Option[Int]]) =>
      runLog(s.get.unNone.chunks.filter(_.nonEmpty)) shouldBe runLog(s.get.filter(_.isDefined).map(_.get).chunks)
    }

    "zipWithIndex" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithIndex) shouldBe runLog(s.get).zipWithIndex
    }

    "zipWithNext" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithNext) shouldBe {
        val xs = runLog(s.get)
        xs.zipAll(xs.map(Some(_)).drop(1), -1, None)
      }
    }

    "zipWithNext (2)" in {
      runLog(Stream().zipWithNext) shouldBe Vector()
      runLog(Stream(0).zipWithNext) shouldBe Vector((0, None))
      runLog(Stream(0, 1, 2).zipWithNext) shouldBe Vector((0, Some(1)), (1, Some(2)), (2, None))
    }

    "zipWithPrevious" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithPrevious) shouldBe {
        val xs = runLog(s.get)
        (None +: xs.map(Some(_))).zip(xs)
      }
    }

    "zipWithPrevious (2)" in {
      runLog(Stream().zipWithPrevious) shouldBe Vector()
      runLog(Stream(0).zipWithPrevious) shouldBe Vector((None, 0))
      runLog(Stream(0, 1, 2).zipWithPrevious) shouldBe Vector((None, 0), (Some(0), 1), (Some(1), 2))
    }

    "zipWithPreviousAndNext" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.zipWithPreviousAndNext) shouldBe {
        val xs = runLog(s.get)
        val zipWithPrevious = (None +: xs.map(Some(_))).zip(xs)
        val zipWithPreviousAndNext = zipWithPrevious
          .zipAll(xs.map(Some(_)).drop(1), (None, -1), None)
          .map { case ((prev, that), next) => (prev, that, next) }

        zipWithPreviousAndNext
      }
    }

    "zipWithPreviousAndNext (2)" in {
      runLog(Stream().zipWithPreviousAndNext) shouldBe Vector()
      runLog(Stream(0).zipWithPreviousAndNext) shouldBe Vector((None, 0, None))
      runLog(Stream(0, 1, 2).zipWithPreviousAndNext) shouldBe Vector((None, 0, Some(1)), (Some(0), 1, Some(2)), (Some(1), 2, None))
    }

    "zipWithScan" in {
      runLog(Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length)) shouldBe Vector("uno" -> 0, "dos" -> 3, "tres" -> 6, "cuatro" -> 10)
      runLog(Stream().zipWithScan(())((acc, i) => ???)) shouldBe Vector()
    }

    "zipWithScan1" in {
      runLog(Stream("uno", "dos", "tres", "cuatro").zipWithScan1(0)(_ + _.length)) shouldBe Vector("uno" -> 3, "dos" -> 6, "tres" -> 10, "cuatro" -> 16)
      runLog(Stream().zipWithScan1(())((acc, i) => ???)) shouldBe Vector()
    }

    "observe/observeAsync" - {
      "basic functionality" in {
        forAll { (s: PureStream[Int]) =>
          val sum = new AtomicLong(0)
          val out = runLog {
            s.get.covary[IO].observe { _.evalMap(i => IO { sum.addAndGet(i.toLong); () }) }
          }
          out.map(_.toLong).sum shouldBe sum.get
          sum.set(0)
          val out2 = runLog {
            s.get.covary[IO].observeAsync(maxQueued = 10) { _.evalMap(i => IO { sum.addAndGet(i.toLong); () }) }
          }
          out2.map(_.toLong).sum shouldBe sum.get
        }
      }
    }
    "handle errors from observing sink" in {
      forAll { (s: PureStream[Int]) =>
        runLog {
          s.get.covary[IO].observe { _ => Stream.raiseError(Err) }.attempt
        } should contain theSameElementsAs Left(Err) +: s.get.toVector.map(Right(_))
        runLog {
          s.get.covary[IO].observeAsync(2) { _ => Stream.raiseError(Err) }.attempt
        } should contain theSameElementsAs Left(Err) +: s.get.toVector.map(Right(_))
      }
    }
    "handle finite observing sink" in {
      forAll { (s: PureStream[Int]) =>
        runLog {
          s.get.covary[IO].observe { _ => Stream.empty }
        } should contain theSameElementsAs s.get.toVector
        runLog {
          s.get.covary[IO].observe { _.take(2).drain }
        } should contain theSameElementsAs s.get.toVector
        runLog {
          s.get.covary[IO].observeAsync(2) { _ => Stream.empty }
        } should contain theSameElementsAs s.get.toVector
      }
    }
    "handle multiple consecutive observations" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        runLog {
          val sink: Sink[IO,Int] = _.evalMap(i => IO(()))
          val src: Stream[IO, Int] = s.get.covary[IO]
          src.observe(sink).observe(sink)
        } shouldBe s.get.toVector
      }
    }
    "no hangs on failures" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        swallow {
          runLog {
            val sink: Sink[IO,Int] = in => spuriousFail(in.evalMap(i => IO(i)), f).map(_ => ())
            val src: Stream[IO, Int] = spuriousFail(s.get.covary[IO], f)
            src.observe(sink).observe(sink)
          }
        }
      }
    }

    "stepping" - {
      "example" in {
        import Pipe.Stepper
        // Note: this is a useful but unsafe function - for each input (I,A), it remembers the A value, feeds the inner pipe, and then
        // tags any output values with the remembered A value. This scheme breaks when the inner pipe buffers elements before emitting.
        def first[I,O,A](p: Pipe[Pure,I,O]): Pipe[Pure,(I,A),(O,A)] = {
          def go(last: Option[A], stepper: Stepper[I,O], s: Stream[Pure,(I,A)]): Pull[Pure,(O,A),Unit] = {
            stepper.step match {
              case Stepper.Done => Pull.done
              case Stepper.Fail(err) => Pull.raiseError(err)
              case Stepper.Emits(segment, next) =>
                last match {
                  case Some(a) => Pull.output(segment.map { o => (o,a) }) *> go(last, next, s)
                  case None => go(last, next, s)
                }
              case Stepper.Await(receive) =>
                s.pull.uncons1.flatMap {
                  case Some(((i,a),s)) => go(Some(a), receive(Some(Chunk.singleton(i))), s)
                  case None => go(last, receive(None), s)
                }
            }
          }
          s => go(None, Pipe.stepper(p), s).stream
        }
        Stream.range(0, 100).map(i => (i,i)).through(first(_.map(_ + 1).take(5))).toList shouldBe List((1,0), (2,1), (3,2), (4,3), (5,4))
      }
    }
  }
}
