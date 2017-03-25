package fs2

import java.util.concurrent.atomic.AtomicLong
import org.scalacheck.Gen

import fs2.Stream._
import fs2.pipe._
import fs2.util.Async

class PipeSpec extends Fs2Spec {

  "Pipe" - {

    "buffer" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      runLog { s.get.buffer(n.get) } shouldBe s.get.toVector

      var counter = 0
      val s2 = (s.get ++ Stream.emits(List.fill(n.get + 1)(0))).repeat
      runLog { s2.evalMap { i => Task.delay { counter += 1; i }}.buffer(n.get).take(n.get + 1) }
      counter shouldBe (n.get * 2)
    }

    "bufferAll" in forAll { (s: PureStream[Int]) =>
      runLog { s.get.bufferAll } shouldBe s.get.toVector
      var counter = 0
      runLog { (s.get ++ s.get).evalMap { i => Task.delay { counter += 1; i } }.bufferAll.take(s.get.toList.size + 1) }
      counter shouldBe (s.get.toList.size * 2)
    }

    "bufferBy" in forAll { (s: PureStream[Int]) =>
      runLog { s.get.bufferBy(_ >= 0) } shouldBe s.get.toVector

      var counter = 0
      val s2 = s.get.map(_.abs)
      val s3 = (s2 ++ Stream.emit(-1) ++ s2).evalMap { i => Task.delay { counter += 1; i }}
      runLog { s3.bufferBy(_ >= 0).take(s.get.toList.size + 2) }
      counter shouldBe (s.get.toList.size * 2 + 1)
    }

    "changes" in {
      Stream.empty.covary[Pure].changes.toList shouldBe Nil
      Stream(1, 2, 3, 4).changes.toList shouldBe List(1, 2, 3, 4)
      Stream(1, 1, 2, 2, 3, 3, 4, 3).changes.toList shouldBe List(1, 2, 3, 4, 3)
      Stream("1", "2", "33", "44", "5", "66").changesBy(_.length).toList shouldBe
        List("1", "33", "5", "66")
    }

    "chunkLimit" in forAll { (s: PureStream[Int], n0: SmallPositive) =>
      val sizeV = s.get.chunkLimit(n0.get).toVector.map(_.size)
      assert(sizeV.forall(_ <= n0.get) && sizeV.sum == s.get.toVector.size)
    }

    "chunkN.fewer" in forAll { (s: PureStream[Int], n0: SmallPositive) =>
      val chunkedV = s.get.chunkN(n0.get, true).toVector
      val unchunkedV = s.get.toVector
      assert {
        // All but last list have n0 values
        chunkedV.dropRight(1).forall(_.map(_.size).sum == n0.get) &&
        // Last list has at most n0 values
        chunkedV.lastOption.fold(true)(_.map(_.size).sum <= n0.get) &&
        // Flattened sequence is equal to vector without chunking
        chunkedV.foldLeft(Vector.empty[Int])((v, l) =>v ++ l.foldLeft(Vector.empty[Int])((v, c) => v ++ c.iterator)) == unchunkedV
      }
    }

    "chunkN.no-fewer" in forAll { (s: PureStream[Int], n0: SmallPositive) =>
      val chunkedV = s.get.chunkN(n0.get, false).toVector
      val unchunkedV = s.get.toVector
      val expectedSize = unchunkedV.size - (unchunkedV.size % n0.get)
      assert {
        // All lists have n0 values
        chunkedV.forall(_.map(_.size).sum == n0.get) &&
        // Flattened sequence is equal to vector without chunking, minus "left over" values that could not fit in a chunk
        chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.foldLeft(Vector.empty[Int])((v, c) => v ++ c.iterator)) == unchunkedV.take(expectedSize)
      }
    }

    "chunks" in forAll(nonEmptyNestedVectorGen) { (v0: Vector[Vector[Int]]) =>
      val v = Vector(Vector(11,2,2,2), Vector(2,2,3), Vector(2,3,4), Vector(1,2,2,2,2,2,3,3))
      val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
      runLog(s.throughPure(chunks).map(_.toVector)) shouldBe v
    }

    "chunks (2)" in forAll(nestedVectorGen[Int](0,10, emptyChunks = true)) { (v: Vector[Vector[Int]]) =>
      val s = if (v.isEmpty) Stream.empty else v.map(emits).reduce(_ ++ _)
      runLog(s.throughPure(chunks).flatMap(Stream.chunk)) shouldBe v.flatten
    }

    "collect" in forAll { (s: PureStream[Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      runLog(s.get.through(fs2.pipe.collect(pf))) shouldBe runLog(s.get).collect(pf)
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
      runLog(s.get.through(drop(n))) shouldBe runLog(s.get).drop(n)
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
      runLog(s.get.through(dropWhile(set))) shouldBe runLog(s.get).dropWhile(set)
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
      val s2 = s.get.mapChunks(c => Chunk.doubles(c.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "filter (3)" in forAll { (s: PureStream[Byte]) =>
      val predicate = (b: Byte) => b < 0
      val s2 = s.get.mapChunks(c => Chunk.bytes(c.toArray))
      runLog(s2.filter(predicate)) shouldBe runLog(s2).filter(predicate)
    }

    "filter (4)" in forAll { (s: PureStream[Boolean]) =>
      val predicate = (b: Boolean) => !b
      val s2 = s.get.mapChunks(c => Chunk.booleans(c.toArray))
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

    "mapChunked" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.mapChunks(identity).chunks) shouldBe runLog(s.get.chunks)
    }

    "performance of multi-stage pipeline" in {
      val v = Vector.fill(1000)(Vector.empty[Int])
      val v2 = Vector.fill(1000)(Vector(0))
      val s = (v.map(Stream.emits): Vector[Stream[Pure,Int]]).reduce(_ ++ _)
      val s2 = (v2.map(Stream.emits(_)): Vector[Stream[Pure,Int]]).reduce(_ ++ _)
      val start = System.currentTimeMillis
      runLog(s.through(pipe.id).through(pipe.id).through(pipe.id).through(pipe.id).through(pipe.id)) shouldBe Vector()
      runLog(s2.through(pipe.id).through(pipe.id).through(pipe.id).through(pipe.id).through(pipe.id)) shouldBe Vector.fill(1000)(0)
    }

    "last" in forAll { (s: PureStream[Int]) =>
      val shouldCompile = s.get.last
      runLog(s.get.through(last)) shouldBe Vector(runLog(s.get).lastOption)
    }

    "lastOr" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val default = n.get
      runLog(s.get.lastOr(default)) shouldBe Vector(runLog(s.get).lastOption.getOrElse(default))
    }

    "lift" in forAll { (s: PureStream[Double]) =>
      runLog(s.get.through(lift(_.toString))) shouldBe runLog(s.get).map(_.toString)
    }

    "mapAccumulate" in forAll { (s: PureStream[Int], n0: Int, n1: SmallPositive) =>
      val f = (_: Int) % n1.get == 0
      val r = s.get.mapAccumulate(n0)((s, i) => (s + i, f(i)))

      runLog(r.map(_._1)) shouldBe runLog(s.get).scan(n0)(_ + _).tail
      runLog(r.map(_._2)) shouldBe runLog(s.get).map(f)
    }

    "prefetch" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.covary[Task].through(prefetch)) shouldBe runLog(s.get)
    }

    "prefetch (timing)" in {
      // should finish in about 3-4 seconds
      val s = Stream(1,2,3)
            . evalMap(i => Task.delay { Thread.sleep(1000); i })
            . through(prefetch)
            . flatMap { i => Stream.eval(Task.delay { Thread.sleep(1000); i}) }
      val start = System.currentTimeMillis
      runLog(s)
      val stop = System.currentTimeMillis
      println("prefetch (timing) took " + (stop-start) + " milliseconds, should be under 6000 milliseconds")
      assert((stop-start) < 6000)
    }

    "sliding" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      s.get.sliding(n.get).toList shouldBe s.get.toList.sliding(n.get).map(_.toVector).toList
    }

    "split" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val s2 = s.get.map(_.abs).filter(_ != 0)
      runLog { s2.chunkLimit(n.get).intersperse(Chunk.singleton(0)).flatMap(Stream.chunk).split(_ == 0) } shouldBe s2.chunkLimit(n.get).map(_.toVector).toVector
    }

    "split (2)" in {
      Stream(1, 2, 0, 0, 3, 0, 4).split(_ == 0).toVector shouldBe Vector(Vector(1, 2), Vector(), Vector(3), Vector(4))
      Stream(1, 2, 0, 0, 3, 0).split(_ == 0).toVector shouldBe Vector(Vector(1, 2), Vector(), Vector(3))
      Stream(1, 2, 0, 0, 3, 0, 0).split(_ == 0).toVector shouldBe Vector(Vector(1, 2), Vector(), Vector(3), Vector())
    }

    "sum" in forAll { (s: PureStream[Int]) =>
      s.get.sum.toVector shouldBe Vector(runLog(s.get).sum)
    }

    "sum (2)" in forAll { (s: PureStream[Double]) =>
      s.get.sum.toVector shouldBe Vector(runLog(s.get).sum)
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
      runLog(s.get.through(takeWhile(set))) shouldBe runLog(s.get).takeWhile(set)
    }

    "takeThrough" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      val f = (i: Int) => i % n.get == 0
      val vec = runLog(s.get)
      val result = if (vec.exists(i => !f(i))) vec.takeWhile(f) ++ vec.find(i => !(f(i))).toVector else vec.takeWhile(f)
      runLog(s.get.takeThrough(f)) shouldBe result
    }

    "scan" in forAll { (s: PureStream[Int], n: Int) =>
      val f = (a: Int, b: Int) => a + b
      runLog(s.get.scan(n)(f)) shouldBe runLog(s.get).scanLeft(n)(f)
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

    "scanF" in forAll { (s: PureStream[Int], n: String) =>
      val f: (String, Int) => Task[String] = (a: String, b: Int) => Task.now(a + b)
      val g = (a: String, b: Int) => a + b
      runLog(s.get.covary[Task].scanF[Task, String](n)(f)) shouldBe runLog(s.get).scanLeft(n)(g)
    }

    "shiftRight" in forAll { (s: PureStream[Int], v: Vector[Int]) =>
      runLog(s.get.shiftRight(v: _*)) shouldBe v ++ runLog(s.get)
    }

    "tail" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.tail) shouldBe runLog(s.get).drop(1)
    }

    "take.chunks" in {
      val s = Stream.pure(1, 2) ++ Stream(3, 4)
      runLog(s.through(take(3)).through(chunks).map(_.toVector)) shouldBe Vector(Vector(1, 2), Vector(3))
    }

    "vectorChunkN" in forAll { (s: PureStream[Int], n: SmallPositive) =>
      runLog(s.get.vectorChunkN(n.get)) shouldBe runLog(s.get).grouped(n.get).toVector
    }

    "zipWithIndex" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.through(zipWithIndex)) shouldBe runLog(s.get).zipWithIndex
    }

    "zipWithNext" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.through(zipWithNext)) shouldBe {
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
      runLog(s.get.through(zipWithPrevious)) shouldBe {
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
      runLog(s.get.through(zipWithPreviousAndNext)) shouldBe {
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
            pipe.observe(s.get.covary[Task]) {
              _.evalMap(i => Task.delay { sum.addAndGet(i.toLong); () })
            }
          }
          out.map(_.toLong).sum shouldBe sum.get
          sum.set(0)
          val out2 = runLog {
            pipe.observeAsync(s.get.covary[Task], maxQueued = 10) {
              _.evalMap(i => Task.delay { sum.addAndGet(i.toLong); () })
            }
          }
          out2.map(_.toLong).sum shouldBe sum.get
        }
      }
      "handle errors from observing sink" in {
        forAll { (s: PureStream[Int]) =>
          runLog {
            pipe.observe(s.get.covary[Task]) { _ => Stream.fail(Err) }.attempt
          } should contain theSameElementsAs Left(Err) +: s.get.toVector.map(Right(_))
          runLog {
            pipe.observeAsync(s.get.covary[Task], 2) { _ => Stream.fail(Err) }.attempt
          } should contain theSameElementsAs Left(Err) +: s.get.toVector.map(Right(_))
        }
      }
      "handle finite observing sink" in {
        forAll { (s: PureStream[Int]) =>
          runLog {
            pipe.observe(s.get.covary[Task]) { _ => Stream.empty }
          } should contain theSameElementsAs s.get.toVector
          runLog {
            pipe.observe(s.get.covary[Task]) { _.take(2).drain }
          } should contain theSameElementsAs s.get.toVector
          runLog {
            pipe.observeAsync(s.get.covary[Task], 2) { _ => Stream.empty }
          } should contain theSameElementsAs s.get.toVector
        }
      }
      "handle multiple consecutive observations" in {
        forAll { (s: PureStream[Int], f: Failure) =>
          runLog {
            val sink: Sink[Task,Int] = _.evalMap(i => Task.delay(()))
            val src: Stream[Task, Int] = s.get.covary[Task]
            src.observe(sink).observe(sink)
          } shouldBe s.get.toVector
        }
      }
      "no hangs on failures" in {
        forAll { (s: PureStream[Int], f: Failure) =>
          swallow {
            runLog {
              val sink: Sink[Task,Int] = in => spuriousFail(in.evalMap(i => Task.delay(i)), f).map(_ => ())
              val src: Stream[Task, Int] = spuriousFail(s.get.covary[Task], f)
              src.observe(sink).observe(sink)
            } shouldBe s.get.toVector
          }
        }
      }
    }

    "sanity-test" in {
      val s = Stream.range(0,100)
      val s2 = s.covary[Task].flatMap { i => Stream.emit(i).onFinalize(Task.delay { println(s"finalizing $i")}) }
      val q = async.unboundedQueue[Task,Int].unsafeRun()
      runLog { merge2(trace("s2")(s2), trace("q")(q.dequeue)).take(10) } shouldBe Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    }
  }

  def trace[F[_],A](msg: String)(s: Stream[F,A]) = s mapChunks { a => println(msg + ": " + a.toList); a }

  def merge2[F[_]:Async,A](a: Stream[F,A], a2: Stream[F,A]): Stream[F,A] = {
    type FS = ScopedFuture[F,Stream[F,A]] // Option[Step[Chunk[A],Stream[F,A]]]]
    def go(fa: FS, fa2: FS): Stream[F,A] = (fa race fa2).stream.flatMap {
      case Left(sa) => sa.uncons.flatMap {
        case Some((hd, sa)) => Stream.chunk(hd) ++ (sa.fetchAsync flatMap (go(_,fa2)))
        case None => println("left stream terminated"); fa2.stream.flatMap(identity)
      }
      case Right(sa2) => sa2.uncons.flatMap {
        case Some((hd, sa2)) => Stream.chunk(hd) ++ (sa2.fetchAsync flatMap (go(fa,_)))
        case None => println("right stream terminated"); fa.stream.flatMap(identity)
      }
    }
    a.fetchAsync flatMap { fa =>
    a2.fetchAsync flatMap { fa2 => go(fa, fa2) }}
  }
}
