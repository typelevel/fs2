package fs2

import scala.concurrent.duration._

import cats.effect.{Blocker, IO, Sync, SyncIO}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import fs2.concurrent.SignallingRef

class StreamCombinatorsSuite extends Fs2Suite {

  group("awakeEvery") {
    test("basic") {
      Stream
        .awakeEvery[IO](500.millis)
        .map(_.toMillis)
        .take(5)
        .compile
        .toVector
        .map { r =>
          r.sliding(2)
            .map(s => (s.head, s.tail.head))
            .map { case (prev, next) => next - prev }
            .foreach(delta => assert(delta >= 350L && delta <= 650L))
        }
    }

    test("liveness") {
      val s = Stream
        .awakeEvery[IO](1.milli)
        .evalMap(_ => IO.async[Unit](cb => realExecutionContext.execute(() => cb(Right(())))))
        .take(200)
      Stream(s, s, s, s, s).parJoin(5).compile.drain
    }
  }

  group("buffer") {
    property("identity") {
      forAll { (s: Stream[Pure, Int], n: Int) =>
        assert(s.buffer(n).toVector == s.toVector)
      }
    }

    test("buffer results of evalMap") {
      forAllAsync { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        IO.suspend {
          var counter = 0
          val s2 = s.append(Stream.emits(List.fill(n + 1)(0))).repeat
          s2.evalMap(i => IO { counter += 1; i })
            .buffer(n)
            .take(n + 1)
            .compile
            .drain
            .map(_ => assert(counter == (n * 2)))
        }
      }
    }
  }

  group("bufferAll") {
    property("identity") {
      forAll((s: Stream[Pure, Int]) => assert(s.bufferAll.toVector == s.toVector))
    }

    test("buffer results of evalMap") {
      forAllAsync { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2
        IO.suspend {
          var counter = 0
          s.append(s)
            .evalMap(i => IO { counter += 1; i })
            .bufferAll
            .take(s.toList.size + 1)
            .compile
            .drain
            .map(_ => assert(counter == expected))
        }
      }
    }
  }

  group("bufferBy") {
    property("identity") {
      forAll { (s: Stream[Pure, Int]) =>
        assert(s.bufferBy(_ >= 0).toVector == s.toVector)
      }
    }

    test("buffer results of evalMap") {
      forAllAsync { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2 + 1
        IO.suspend {
          var counter = 0
          val s2 = s.map(x => if (x == Int.MinValue) x + 1 else x).map(_.abs)
          val s3 = s2.append(Stream.emit(-1)).append(s2).evalMap(i => IO { counter += 1; i })
          s3.bufferBy(_ >= 0)
            .take(s.toList.size + 2)
            .compile
            .drain
            .map(_ => assert(counter == expected))
        }
      }
    }
  }

  test("changes") {
    assert(Stream.empty.covaryOutput[Int].changes.toList == Nil)
    assert(Stream(1, 2, 3, 4).changes.toList == List(1, 2, 3, 4))
    assert(Stream(1, 1, 2, 2, 3, 3, 4, 3).changes.toList == List(1, 2, 3, 4, 3))
    val result = Stream("1", "2", "33", "44", "5", "66")
      .changesBy(_.length)
      .toList
    assert(result == List("1", "33", "5", "66"))
  }

  property("collect consistent with list collect") {
    forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      assertEquals(s.collect(pf).toList, s.toList.collect(pf))
    }
  }

  property("collectFirst consistent with list collectFirst") {
    forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      assertEquals(s.collectFirst(pf).toVector, s.collectFirst(pf).toVector)
    }
  }

  property("collectWhile") {
    forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      val even = s1.filter(_ % 2 == 0)
      val odd = s2.filter(_ % 2 != 0)
      assert((even ++ odd).collectWhile(pf).toVector == even.toVector)
    }
  }

  test("debounce") {
    val delay = 200.milliseconds
    (Stream(1, 2, 3) ++ Stream.sleep[IO](delay * 2) ++ Stream() ++ Stream(4, 5) ++ Stream
      .sleep[IO](delay / 2) ++ Stream(6))
      .debounce(delay)
      .compile
      .toList
      .map(it => assert(it == List(3, 6)))
  }

  property("delete") {
    forAll { (s: Stream[Pure, Int], idx0: Int) =>
      val v = s.toVector
      val i = if (v.isEmpty) 0 else v((idx0 % v.size).abs)
      assert(s.delete(_ == i).toVector == v.diff(Vector(i)))
    }
  }

  property("drop") {
    forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else (n0 % v.size).abs
      val n = if (negate) -n1 else n1
      assert(s.drop(n).toVector == s.toVector.drop(n))
    }
  }

  property("dropLast") {
    forAll { (s: Stream[Pure, Int]) =>
      assert(s.dropLast.toVector == s.toVector.dropRight(1))
    }
  }

  property("dropLastIf") {
    forAll { (s: Stream[Pure, Int]) =>
      assert(s.dropLastIf(_ => false).toVector == s.toVector)
      assert(s.dropLastIf(_ => true).toVector == s.toVector.dropRight(1))
    }
  }

  property("dropRight") {
    forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else (n0 % v.size).abs
      val n = if (negate) -n1 else n1
      assert(s.dropRight(n).toVector == v.dropRight(n))
    }
  }

  property("dropWhile") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs
      val set = s.toVector.take(n).toSet
      assert(s.dropWhile(set).toVector == s.toVector.dropWhile(set))
    }
  }

  property("dropThrough") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs
      val set = s.toVector.take(n).toSet
      val expected = {
        val vec = s.toVector.dropWhile(set)
        if (vec.isEmpty) vec else vec.tail
      }
      assert(s.dropThrough(set).toVector == expected)
    }
  }

  test("duration") {
    val delay = 200.millis
    Stream
      .emit(())
      .append(Stream.eval(IO.sleep(delay)))
      .zip(Stream.duration[IO])
      .drop(1)
      .map(_._2)
      .compile
      .toVector
      .map { result =>
        assert(result.size == 1)
        val head = result.head
        assert(head.toMillis >= (delay.toMillis - 5))
      }
  }

  test("either") {
    forAllAsync { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      val s2List = s2.toList
      s1.covary[IO].either(s2).compile.toList.map { result =>
        assert(result.collect { case Left(i) => i } == s1List)
        assert(result.collect { case Right(i) => i } == s2List)
      }
    }
  }

  group("evalSeq") {
    test("with List") {
      Stream
        .evalSeq(IO(List(1, 2, 3)))
        .compile
        .toList
        .map(it => assert(it == List(1, 2, 3)))
    }
    test("with Seq") {
      Stream.evalSeq(IO(Seq(4, 5, 6))).compile.toList.map(it => assert(it == List(4, 5, 6)))
    }
  }

  group("evalFilter") {
    test("with effectful const(true)") {
      forAllAsync { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.evalFilter(_ => IO.pure(true)).compile.toList.map(it => assert(it == s1))
      }
    }

    test("with effectful const(false)") {
      forAllAsync { s: Stream[Pure, Int] =>
        s.evalFilter(_ => IO.pure(false)).compile.toList.map(it => assert(it.isEmpty))
      }
    }

    test("with function that filters out odd elements") {
      Stream
        .range(1, 10)
        .evalFilter(e => IO(e % 2 == 0))
        .compile
        .toList
        .map(it => assert(it == List(2, 4, 6, 8)))
    }
  }

  group("evalFilterAsync") {
    test("with effectful const(true)") {
      forAllAsync { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.covary[IO]
          .evalFilterAsync(5)(_ => IO.pure(true))
          .compile
          .toList
          .map(it => assert(it == s1))
      }
    }

    test("with effectful const(false)") {
      forAllAsync { s: Stream[Pure, Int] =>
        s.covary[IO]
          .evalFilterAsync(5)(_ => IO.pure(false))
          .compile
          .toList
          .map(it => assert(it.isEmpty))
      }
    }

    test("with function that filters out odd elements") {
      Stream
        .range(1, 10)
        .evalFilterAsync[IO](5)(e => IO(e % 2 == 0))
        .compile
        .toList
        .map(it => assert(it == List(2, 4, 6, 8)))
    }

    test("filters up to N items in parallel") {
      val s = Stream.range(0, 100)
      val n = 5

      (Semaphore[IO](n), SignallingRef[IO, Int](0)).tupled
        .flatMap {
          case (sem, sig) =>
            val tested = s
              .covary[IO]
              .evalFilterAsync(n) { _ =>
                val ensureAcquired =
                  sem.tryAcquire.ifM(
                    IO.unit,
                    IO.raiseError(new Throwable("Couldn't acquire permit"))
                  )

                ensureAcquired.bracket(_ =>
                  sig.update(_ + 1).bracket(_ => IO.sleep(10.millis))(_ => sig.update(_ - 1))
                )(_ => sem.release) *>
                  IO.pure(true)
              }

            sig.discrete
              .interruptWhen(tested.drain)
              .fold1(_.max(_))
              .compile
              .lastOrError
              .product(sig.get)
        }
        .map(it => assert(it == ((n, 0))))
    }
  }

  group("evalFilterNot") {
    test("with effectful const(true)") {
      forAllAsync { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.evalFilterNot(_ => IO.pure(false)).compile.toList.map(it => assert(it == s1))
      }
    }

    test("with effectful const(false)") {
      forAllAsync { s: Stream[Pure, Int] =>
        s.evalFilterNot(_ => IO.pure(true)).compile.toList.map(it => assert(it.isEmpty))
      }
    }

    test("with function that filters out odd elements") {
      Stream
        .range(1, 10)
        .evalFilterNot(e => IO(e % 2 == 0))
        .compile
        .toList
        .map(it => assert(it == List(1, 3, 5, 7, 9)))
    }
  }

  group("evalFilterNotAsync") {
    test("with effectful const(true)") {
      forAllAsync { s: Stream[Pure, Int] =>
        s.covary[IO]
          .evalFilterNotAsync(5)(_ => IO.pure(true))
          .compile
          .toList
          .map(it => assert(it.isEmpty))
      }
    }

    test("with effectful const(false)") {
      forAllAsync { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.covary[IO]
          .evalFilterNotAsync(5)(_ => IO.pure(false))
          .compile
          .toList
          .map(it => assert(it == s1))
      }
    }

    test("with function that filters out odd elements") {
      Stream
        .range(1, 10)
        .evalFilterNotAsync[IO](5)(e => IO(e % 2 == 0))
        .compile
        .toList
        .map(it => assert(it == List(1, 3, 5, 7, 9)))
    }

    test("filters up to N items in parallel") {
      val s = Stream.range(0, 100)
      val n = 5

      (Semaphore[IO](n), SignallingRef[IO, Int](0)).tupled
        .flatMap {
          case (sem, sig) =>
            val tested = s
              .covary[IO]
              .evalFilterNotAsync(n) { _ =>
                val ensureAcquired =
                  sem.tryAcquire.ifM(
                    IO.unit,
                    IO.raiseError(new Throwable("Couldn't acquire permit"))
                  )

                ensureAcquired.bracket(_ =>
                  sig.update(_ + 1).bracket(_ => IO.sleep(10.millis))(_ => sig.update(_ - 1))
                )(_ => sem.release) *>
                  IO.pure(false)
              }

            sig.discrete
              .interruptWhen(tested.drain)
              .fold1(_.max(_))
              .compile
              .lastOrError
              .product(sig.get)
        }
        .map(it => assert(it == ((n, 0))))
    }
  }

  test("evalMapAccumulate") {
    forAllAsync { (s: Stream[Pure, Int], m: Int, n0: Int) =>
      val sVector = s.toVector
      val n = (n0 % 20).abs + 1
      val f = (_: Int) % n == 0
      val r = s.covary[IO].evalMapAccumulate(m)((s, i) => IO.pure((s + i, f(i))))
      List(
        r.map(_._1).compile.toVector.map(it => assert(it == sVector.scanLeft(m)(_ + _).tail)),
        r.map(_._2).compile.toVector.map(it => assert(it == sVector.map(f)))
      ).sequence_
    }
  }

  group("evalMapFilter") {
    test("with effectful optional identity function") {
      forAllAsync { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.evalMapFilter(n => IO.pure(n.some)).compile.toList.map(it => assert(it == s1))
      }
    }

    test("with effectful constant function that returns None for any element") {
      forAllAsync { s: Stream[Pure, Int] =>
        s.evalMapFilter(_ => IO.pure(none[Int]))
          .compile
          .toList
          .map(it => assert(it.isEmpty))
      }
    }

    test("with effectful function that filters out odd elements") {
      Stream
        .range(1, 10)
        .evalMapFilter(e => IO.pure(e.some.filter(_ % 2 == 0)))
        .compile
        .toList
        .map(it => assert(it == List(2, 4, 6, 8)))
    }
  }

  test("evalScan") {
    forAllAsync { (s: Stream[Pure, Int], n: String) =>
      val sVector = s.toVector
      val f: (String, Int) => IO[String] = (a: String, b: Int) => IO.pure(a + b)
      val g = (a: String, b: Int) => a + b
      s.covary[IO]
        .evalScan(n)(f)
        .compile
        .toVector
        .map(it => assert(it == sVector.scanLeft(n)(g)))
    }
  }

  test("every".flaky) {
    type BD = (Boolean, FiniteDuration)
    def durationSinceLastTrue[F[_]]: Pipe[F, BD, BD] = {
      def go(lastTrue: FiniteDuration, s: Stream[F, BD]): Pull[F, BD, Unit] =
        s.pull.uncons1.flatMap {
          case None => Pull.done
          case Some((pair, tl)) =>
            pair match {
              case (true, d) =>
                Pull.output1((true, d - lastTrue)) >> go(d, tl)
              case (false, d) =>
                Pull.output1((false, d - lastTrue)) >> go(lastTrue, tl)
            }
        }
      s => go(0.seconds, s).stream
    }

    val delay = 20.millis
    val draws = (600.millis / delay).min(50) // don't take forever

    val durationsSinceSpike = Stream
      .every[IO](delay)
      .map(d => (d, System.nanoTime.nanos))
      .take(draws.toInt)
      .through(durationSinceLastTrue)

    (IO.shift >> durationsSinceSpike.compile.toVector).map { result =>
      val list = result.toList
      assert(list.head._1, "every always emits true first")
      assert(
        list.tail.filter(_._1).map(_._2).forall(_ >= delay),
        s"true means the delay has passed: ${list.tail}"
      )
      assert(
        list.tail.filterNot(_._1).map(_._2).forall(_ <= delay),
        s"false means the delay has not passed: ${list.tail}"
      )
    }
  }

  property("exists") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val f = (i: Int) => i % n == 0
      assert(s.exists(f).toList == List(s.toList.exists(f)))
    }
  }

  group("filter") {
    property("1") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val predicate = (i: Int) => i % n == 0
        assert(s.filter(predicate).toList == s.toList.filter(predicate))
      }
    }

    property("2") {
      forAll { (s: Stream[Pure, Double]) =>
        val predicate = (i: Double) => i - i.floor < 0.5
        val s2 = s.mapChunks(c => Chunk.doubles(c.toArray))
        assert(s2.filter(predicate).toList == s2.toList.filter(predicate))
      }
    }

    property("3") {
      forAll { (s: Stream[Pure, Byte]) =>
        val predicate = (b: Byte) => b < 0
        val s2 = s.mapChunks(c => Chunk.bytes(c.toArray))
        assert(s2.filter(predicate).toList == s2.toList.filter(predicate))
      }
    }

    property("4") {
      forAll { (s: Stream[Pure, Boolean]) =>
        val predicate = (b: Boolean) => !b
        val s2 = s.mapChunks(c => Chunk.booleans(c.toArray))
        assert(s2.filter(predicate).toList == s2.toList.filter(predicate))
      }
    }
  }

  property("find") {
    forAll { (s: Stream[Pure, Int], i: Int) =>
      val predicate = (item: Int) => item < i
      assert(s.find(predicate).toList == s.toList.find(predicate).toList)
    }
  }
  group("fold") {
    property("1") {
      forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        assert(s.fold(n)(f).toList == List(s.toList.foldLeft(n)(f)))
      }
    }

    property("2") {
      forAll { (s: Stream[Pure, Int], n: String) =>
        val f = (a: String, b: Int) => a + b
        assert(s.fold(n)(f).toList == List(s.toList.foldLeft(n)(f)))
      }
    }
  }

  group("foldMonoid") {
    property("1") {
      forAll { (s: Stream[Pure, Int]) =>
        assert(s.foldMonoid.toVector == Vector(s.toVector.combineAll))
      }
    }

    property("2") {
      forAll { (s: Stream[Pure, Double]) =>
        assert(s.foldMonoid.toVector == Vector(s.toVector.combineAll))
      }
    }
  }

  property("fold1") {
    forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      val expected = v.headOption.fold(Vector.empty[Int])(h => Vector(v.drop(1).foldLeft(h)(f)))
      assert(s.fold1(f).toVector == expected)
    }
  }

  property("foldable") {
    forAll((c: List[Int]) => assert(Stream.foldable(c).compile.to(List) == c))
  }

  property("forall") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val f = (i: Int) => i % n == 0
      assert(s.forall(f).toList == List(s.toList.forall(f)))
    }
  }

  property("fromEither") {
    forAll { either: Either[Throwable, Int] =>
      val stream: Stream[Fallible, Int] = Stream.fromEither[Fallible](either)
      either match {
        case Left(t)  => assert(stream.toList == Left(t))
        case Right(i) => assert(stream.toList == Right(List(i)))
      }
    }
  }

  test("fromIterator") {
    forAllAsync { x: List[Int] =>
      Stream
        .fromIterator[IO](x.iterator)
        .compile
        .toList
        .map(it => assert(it == x))
    }
  }

  test("fromBlockingIterator") {
    forAllAsync { x: List[Int] =>
      Stream
        .fromBlockingIterator[IO](Blocker.liftExecutionContext(implicitly), x.iterator)
        .compile
        .toList
        .map(it => assert(it == x))
    }
  }

  property("groupAdjacentBy") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val f = (i: Int) => i % n
      val s1 = s.groupAdjacentBy(f)
      val s2 = s.map(f).changes
      assert(s1.map(_._2).toList.flatMap(_.toList) == s.toList)
      assert(s1.map(_._1).toList == s2.toList)
      assert(
        s1.map { case (k, vs) => vs.toVector.forall(f(_) == k) }.toList ==
          s2.map(_ => true).toList
      )
    }
  }

  property("groupAdjacentByLimit") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val s1 = s.groupAdjacentByLimit(n)(_ => true)
      assert(s1.map(_._2).toList.map(_.toList) == s.toList.grouped(n).toList)
    }
  }

  group("groupWithin") {
    test("should never lose any elements") {
      forAllAsync { (s0: Stream[Pure, Int], d0: Int, maxGroupSize0: Int) =>
        val maxGroupSize = (maxGroupSize0 % 20).abs + 1
        val d = (d0 % 50).abs.millis
        val s = s0.map(i => (i % 500).abs)
        val sList = s.toList
        s.covary[IO]
          .evalTap(shortDuration => IO.sleep(shortDuration.micros))
          .groupWithin(maxGroupSize, d)
          .flatMap(s => Stream.emits(s.toList))
          .compile
          .toList
          .map(it => assert(it == sList))
      }
    }

    test("should never emit empty groups") {
      forAllAsync { (s: Stream[Pure, Int], d0: Int, maxGroupSize0: Int) =>
        val maxGroupSize = (maxGroupSize0 % 20).abs + 1
        val d = (d0 % 50).abs.millis
        Stream(1)
          .append(s)
          .map(i => (i % 500).abs)
          .covary[IO]
          .evalTap(shortDuration => IO.sleep(shortDuration.micros))
          .groupWithin(maxGroupSize, d)
          .map(_.toList)
          .compile
          .toList
          .map(it => assert(it.forall(_.nonEmpty)))
      }
    }

    test("should never have more elements than in its specified limit") {
      forAllAsync { (s: Stream[Pure, Int], d0: Int, maxGroupSize0: Int) =>
        val maxGroupSize = (maxGroupSize0 % 20).abs + 1
        val d = (d0 % 50).abs.millis
        s.map(i => (i % 500).abs)
          .evalTap(shortDuration => IO.sleep(shortDuration.micros))
          .groupWithin(maxGroupSize, d)
          .map(_.toList.size)
          .compile
          .toList
          .map(it => assert(it.forall(_ <= maxGroupSize)))
      }
    }

    test(
      "should return a finite stream back in a single chunk given a group size equal to the stream size and an absurdly high duration"
    ) {
      forAllAsync { (streamAsList0: List[Int]) =>
        val streamAsList = 0 :: streamAsList0
        Stream
          .emits(streamAsList)
          .covary[IO]
          .groupWithin(streamAsList.size, (Int.MaxValue - 1L).nanoseconds)
          .compile
          .toList
          .map(it => assert(it.head.toList == streamAsList))
      }
    }
  }

  property("head")(forAll((s: Stream[Pure, Int]) => assert(s.head.toList == s.toList.take(1))))

  group("interleave") {
    test("interleave left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      assert(ones.interleave(s).toList == List("1", "A", "1", "B", "1", "C"))
      assert(s.interleave(ones).toList == List("A", "1", "B", "1", "C", "1"))
    }

    test("interleave both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      assert(ones.interleave(as).take(3).toList == List("1", "A", "1"))
      assert(as.interleave(ones).take(3).toList == List("A", "1", "A"))
    }

    test("interleaveAll left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      assert(
        ones.interleaveAll(s).take(9).toList == List(
          "1",
          "A",
          "1",
          "B",
          "1",
          "C",
          "1",
          "1",
          "1"
        )
      )
      assert(
        s.interleaveAll(ones).take(9).toList == List(
          "A",
          "1",
          "B",
          "1",
          "C",
          "1",
          "1",
          "1",
          "1"
        )
      )
    }

    test("interleaveAll both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      assert(ones.interleaveAll(as).take(3).toList == List("1", "A", "1"))
      assert(as.interleaveAll(ones).take(3).toList == List("A", "1", "A"))
    }

    // Uses a small scope to avoid using time to generate too large streams and not finishing
    property("interleave is equal to interleaveAll on infinite streams (by step-indexing)") {
      forAll(Gen.chooseNum(0, 100)) { (n: Int) =>
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assert(
          ones.interleaveAll(as).take(n).toVector == ones
            .interleave(as)
            .take(n)
            .toVector
        )
      }
    }
  }

  property("intersperse") {
    forAll { (s: Stream[Pure, Int], n: Int) =>
      assert(s.intersperse(n).toList == s.toList.flatMap(i => List(i, n)).dropRight(1))
    }
  }

  property("iterable") {
    forAll((c: Set[Int]) => assert(Stream.iterable(c).compile.to(Set) == c))
  }

  test("iterate") {
    assert(Stream.iterate(0)(_ + 1).take(100).toList == List.iterate(0, 100)(_ + 1))
  }

  test("iterateEval") {
    Stream
      .iterateEval(0)(i => IO(i + 1))
      .take(100)
      .compile
      .toVector
      .map(it => assert(it == List.iterate(0, 100)(_ + 1)))
  }

  property("last") {
    forAll { (s: Stream[Pure, Int]) =>
      val _ = s.last
      assert(s.last.toList == List(s.toList.lastOption))
    }
  }

  property("lastOr") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      assert(s.lastOr(n).toList == List(s.toList.lastOption.getOrElse(n)))
    }
  }

  property("mapAccumulate") {
    forAll { (s: Stream[Pure, Int], m: Int, n0: Int) =>
      val n = (n0 % 20).abs + 1
      val f = (_: Int) % n == 0
      val r = s.mapAccumulate(m)((s, i) => (s + i, f(i)))

      assert(r.map(_._1).toList == s.toList.scanLeft(m)(_ + _).tail)
      assert(r.map(_._2).toList == s.toList.map(f))
    }
  }

  group("mapAsync") {
    test("same as map") {
      forAllAsync { s: Stream[Pure, Int] =>
        val f = (_: Int) + 1
        val r = s.covary[IO].mapAsync(16)(i => IO(f(i)))
        val sVector = s.toVector
        r.compile.toVector.map(it => assert(it == sVector.map(f)))
      }
    }

    test("exception") {
      forAllAsync { s: Stream[Pure, Int] =>
        val f = (_: Int) => IO.raiseError[Int](new RuntimeException)
        val r = (s ++ Stream(1)).covary[IO].mapAsync(1)(f).attempt
        r.compile.toVector.map(it => assert(it.size == 1))
      }
    }
  }

  test("mapAsyncUnordered") {
    forAllAsync { s: Stream[Pure, Int] =>
      val f = (_: Int) + 1
      val r = s.covary[IO].mapAsyncUnordered(16)(i => IO(f(i)))
      val sVector = s.toVector
      r.compile.toVector.map(it => assert(it.toSet == sVector.map(f).toSet))
    }
  }

  test("pause") {
    Stream
      .eval(SignallingRef[IO, Boolean](false))
      .flatMap { pause =>
        Stream
          .awakeEvery[IO](10.millis)
          .scan(0)((acc, _) => acc + 1)
          .evalMap { n =>
            if (n % 2 != 0)
              pause.set(true) >> ((Stream.sleep_[IO](10.millis) ++ Stream.eval(
                pause.set(false)
              )).compile.drain).start >> IO
                .pure(n)
            else IO.pure(n)
          }
          .take(5)
          .pauseWhen(pause)
      }
      .compile
      .toList
      .map(it => assert(it == List(0, 1, 2, 3, 4)))
  }

  group("prefetch") {
    test("identity") {
      forAllAsync { (s: Stream[Pure, Int]) =>
        val expected = s.toList
        s.covary[IO].prefetch.compile.toList.map(it => assert(it == expected))
      }
    }

    test("timing") {
      // should finish in about 3-4 seconds
      IO.suspend {
        val start = System.currentTimeMillis
        Stream(1, 2, 3)
          .evalMap(i => IO.sleep(1.second).as(i))
          .prefetch
          .flatMap(i => Stream.eval(IO.sleep(1.second).as(i)))
          .compile
          .toList
          .map { _ =>
            val stop = System.currentTimeMillis
            val elapsed = stop - start
            assert(elapsed < 6000L)
          }
      }
    }
  }

  test("random") {
    val x = Stream.random[SyncIO].take(100).compile.toList
    (x, x).tupled.map {
      case (first, second) =>
        assert(first != second)
    }
  }

  test("randomSeeded") {
    val x = Stream.randomSeeded(1L).take(100).toList
    val y = Stream.randomSeeded(1L).take(100).toList
    assert(x == y)
  }

  test("range") {
    assert(Stream.range(0, 100).toList == List.range(0, 100))
    assert(Stream.range(0, 1).toList == List.range(0, 1))
    assert(Stream.range(0, 0).toList == List.range(0, 0))
    assert(Stream.range(0, 101, 2).toList == List.range(0, 101, 2))
    assert(Stream.range(5, 0, -1).toList == List.range(5, 0, -1))
    assert(Stream.range(5, 0, 1).toList == Nil)
    assert(Stream.range(10, 50, 0).toList == Nil)
  }

  property("ranges") {
    forAll(Gen.chooseNum(1, 101)) { size =>
      val result = Stream
        .ranges(0, 100, size)
        .flatMap { case (i, j) => Stream.emits(i until j) }
        .toVector
      assert(result == IndexedSeq.range(0, 100))
    }
  }

  group("rechunkRandomlyWithSeed") {
    property("is deterministic") {
      forAll { (s0: Stream[Pure, Int], seed: Long) =>
        def s = s0.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed)
        assert(s.toList == s.toList)
      }
    }

    property("does not drop elements") {
      forAll { (s: Stream[Pure, Int], seed: Long) =>
        assert(s.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed).toList == s.toList)
      }
    }

    property("chunk size in interval [inputChunk.size * minFactor, inputChunk.size * maxFactor]") {
      forAll { (s: Stream[Pure, Int], seed: Long) =>
        val c = s.chunks.toVector
        if (c.nonEmpty) {
          val (min, max) = c.tail.foldLeft(c.head.size -> c.head.size) {
            case ((min, max), c) => Math.min(min, c.size) -> Math.max(max, c.size)
          }
          val (minChunkSize, maxChunkSize) = (min * 0.1, max * 2.0)
          // Last element is dropped as it may not fulfill size constraint
          val isChunkSizeCorrect = s
            .rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed)
            .chunks
            .map(_.size)
            .toVector
            .dropRight(1)
            .forall { it =>
              it >= minChunkSize.toInt &&
              it <= maxChunkSize.toInt
            }
          assert(isChunkSizeCorrect)
        }
      }
    }
  }

  test("rechunkRandomly") {
    forAllAsync { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      s.rechunkRandomly[IO]().compile.toList.map(it => assert(it == expected))
    }
  }

  test("repartition") {
    assert(
      Stream("Lore", "m ip", "sum dolo", "r sit amet")
        .repartition(s => Chunk.array(s.split(" ")))
        .toList ==
        List("Lorem", "ipsum", "dolor", "sit", "amet")
    )
    assert(
      Stream("hel", "l", "o Wor", "ld")
        .repartition(s => Chunk.indexedSeq(s.grouped(2).toVector))
        .toList ==
        List("he", "ll", "o ", "Wo", "rl", "d")
    )
    assert(
      Stream.empty
        .covaryOutput[String]
        .repartition(_ => Chunk.empty)
        .toList == List()
    )
    assert(Stream("hello").repartition(_ => Chunk.empty).toList == List())

    def input = Stream("ab").repeat
    def ones(s: String) = Chunk.vector(s.grouped(1).toVector)
    assert(input.take(2).repartition(ones).toVector == Vector("a", "b", "a", "b"))
    assert(
      input.take(4).repartition(ones).toVector == Vector(
        "a",
        "b",
        "a",
        "b",
        "a",
        "b",
        "a",
        "b"
      )
    )
    assert(input.repartition(ones).take(2).toVector == Vector("a", "b"))
    assert(input.repartition(ones).take(4).toVector == Vector("a", "b", "a", "b"))
    assert(
      Stream
        .emits(input.take(4).toVector)
        .repartition(ones)
        .toVector == Vector("a", "b", "a", "b", "a", "b", "a", "b")
    )

    assert(
      Stream(1, 2, 3, 4, 5).repartition(i => Chunk(i, i)).toList == List(1, 3, 6, 10, 15, 15)
    )

    assert(
      Stream(1, 10, 100)
        .repartition(_ => Chunk.seq(1 to 1000))
        .take(4)
        .toList == List(1, 2, 3, 4)
    )
  }

  group("scan") {
    property("1") {
      forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        assert(s.scan(n)(f).toList == s.toList.scanLeft(n)(f))
      }
    }

    test("2") {
      val s = Stream(1).map(x => x)
      val f = (a: Int, b: Int) => a + b
      assert(s.scan(0)(f).toList == s.toList.scanLeft(0)(f))
    }

    test("temporal") {
      val never = Stream.eval(IO.async[Int](_ => ()))
      val s = Stream(1)
      val f = (a: Int, b: Int) => a + b
      val result = s.toList.scanLeft(0)(f)
      s.append(never)
        .scan(0)(f)
        .take(result.size)
        .compile
        .toList
        .map(it => assert(it == result))
    }
  }

  property("scan1") {
    forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      assert(
        s.scan1(f).toVector == v.headOption.fold(Vector.empty[Int])(h => v.drop(1).scanLeft(h)(f))
      )
    }
  }

  test("sleep") {
    val delay = 200.millis
    // force a sync up in duration, then measure how long sleep takes
    val emitAndSleep = Stream(()) ++ Stream.sleep[IO](delay)
    emitAndSleep
      .zip(Stream.duration[IO])
      .drop(1)
      .map(_._2)
      .compile
      .toList
      .map(result =>
        assert(result.head >= (delay * 0.95))
      ) // Allow for sleep starting just before duration measurement
  }

  property("sliding") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      assert(
        s.sliding(n).toList.map(_.toList) == s.toList
          .sliding(n)
          .map(_.toList)
          .toList
      )
    }
  }

  group("split") {
    property("1") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val s2 = s
          .map(x => if (x == Int.MinValue) x + 1 else x)
          .map(_.abs)
          .filter(_ != 0)
        assert(
          s2.chunkLimit(n)
            .intersperse(Chunk.singleton(0))
            .flatMap(Stream.chunk)
            .split(_ == 0)
            .map(_.toVector)
            .filter(_.nonEmpty)
            .toVector == s2.chunkLimit(n).filter(_.nonEmpty).map(_.toVector).toVector,
          s"n = $n, s = ${s.toList}, s2 = " + s2.toList
        )
      }
    }

    test("2") {
      assert(
        Stream(1, 2, 0, 0, 3, 0, 4).split(_ == 0).toVector.map(_.toVector) == Vector(
          Vector(1, 2),
          Vector(),
          Vector(3),
          Vector(4)
        )
      )
      assert(
        Stream(1, 2, 0, 0, 3, 0).split(_ == 0).toVector.map(_.toVector) == Vector(
          Vector(1, 2),
          Vector(),
          Vector(3)
        )
      )
      assert(
        Stream(1, 2, 0, 0, 3, 0, 0).split(_ == 0).toVector.map(_.toVector) == Vector(
          Vector(1, 2),
          Vector(),
          Vector(3),
          Vector()
        )
      )
    }
  }

}
