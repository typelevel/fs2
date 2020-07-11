package fs2

import scala.concurrent.duration._

import cats.effect.{Blocker, IO, Sync, SyncIO}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
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
}
