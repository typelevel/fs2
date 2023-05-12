/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.{Queue, Semaphore}
import cats.effect.testkit.TestControl
import cats.effect.{IO, SyncIO}
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import org.scalacheck.effect.PropF.forAllF
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import scala.concurrent.duration._
import scala.concurrent.TimeoutException
import scala.util.control.NoStackTrace

class StreamCombinatorsSuite extends Fs2Suite {
  override def munitIOTimeout = 1.minute

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
        .evalMap(_ => IO.async_[Unit](cb => munitExecutionContext.execute(() => cb(Right(())))))
        .take(200)
      Stream(s, s, s, s, s).parJoin(5).compile.drain
    }

    test("short periods, no underflow") {
      val input: Stream[IO, Int] = Stream.range(0, 10)
      input.metered(1.nanos).assertEmitsSameAs(input)
    }

    test("very short periods, no underflow") {
      val input: Stream[IO, Int] = Stream.range(0, 10)
      input.metered(0.3.nanos).assertEmitsSameAs(input)
    }

    test("zero-length periods, no underflow") {
      val input: Stream[IO, Int] = Stream.range(0, 10)
      input.metered(0.nanos).assertEmitsSameAs(input)
    }

    test("dampening") {
      val count = 10L
      val period = 10.millis
      Stream
        .awakeEvery[IO](period)
        .take(count)
        .mapAccumulate(0L)((acc, o) => (acc + 1, o))
        .evalMap { case (i, o) => IO.sleep(if (i == 2) 5 * period else 0.seconds).as(o) }
        .compile
        .toVector
        .map { v =>
          val elapsed = v.last - v.head
          assert(elapsed > count * period)
        }
    }
  }

  group("buffer") {
    property("identity") {
      forAll { (s: Stream[Pure, Int], n: Int) =>
        assertEquals(s.buffer(n).toVector, s.toVector)
      }
    }

    test("buffer results of evalMap") {
      forAllF { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        Counter[IO].flatMap { counter =>
          val s2 = s.append(Stream.emits(List.fill(n + 1)(0))).repeat
          s2.evalTap(_ => counter.increment)
            .buffer(n)
            .take(n + 1L)
            .compile
            .drain >> counter.get.assertEquals(n.toLong * 2)
        }
      }
    }
  }

  group("bufferAll") {
    property("identity") {
      forAll((s: Stream[Pure, Int]) => assertEquals(s.bufferAll.toVector, s.toVector))
    }

    test("buffer results of evalMap") {
      forAllF { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2
        Counter[IO].flatMap { counter =>
          s.append(s)
            .evalTap(_ => counter.increment)
            .bufferAll
            .take(s.toList.size + 1L)
            .compile
            .drain >> counter.get.assertEquals(expected.toLong)
        }
      }
    }
  }

  group("bufferBy") {
    property("identity") {
      forAll { (s: Stream[Pure, Int]) =>
        assertEquals(s.bufferBy(_ >= 0).toVector, s.toVector)
      }
    }

    test("buffer results of evalMap") {
      forAllF { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2 + 1
        Counter[IO].flatMap { counter =>
          val s2 = s.map(x => if (x == Int.MinValue) x + 1 else x).map(_.abs)
          val s3 = s2.append(Stream.emit(-1)).append(s2).evalTap(_ => counter.increment)
          s3.bufferBy(_ >= 0)
            .take(s.toList.size + 2L)
            .compile
            .drain >> counter.get.assertEquals(expected.toLong)
        }
      }
    }
  }

  test("changes") {
    assertEquals(Stream.empty.covaryOutput[Int].changes.toList, Nil)
    assertEquals(Stream(1, 2, 3, 4).changes.toList, List(1, 2, 3, 4))
    assertEquals(Stream(1, 1, 2, 2, 3, 3, 4, 3).changes.toList, List(1, 2, 3, 4, 3))
    val result = Stream("1", "2", "33", "44", "5", "66").changesBy(_.length)
    result.assertEmits(List("1", "33", "5", "66"))
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
      assertEquals((even ++ odd).collectWhile(pf).toVector, even.toVector)
    }
  }

  test("debounce") {
    val delay = 200.milliseconds
    TestControl.executeEmbed {
      (Stream(1, 2, 3) ++ Stream.sleep[IO](delay * 2) ++ Stream() ++ Stream(4, 5) ++ Stream
        .sleep[IO](delay / 2) ++ Stream(6))
        .debounce(delay)
        .assertEmits(List(3, 6))
    }
  }

  property("delete") {
    forAll { (s: Stream[Pure, Int], idx0: Int) =>
      val v = s.toVector
      val i = if (v.isEmpty) 0 else v((idx0 % v.size).abs)
      assertEquals(s.delete(_ == i).toVector, v.diff(Vector(i)))
    }
  }

  property("drop") {
    forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else (n0 % v.size).abs
      val n = if (negate) -n1 else n1
      assertEquals(s.drop(n.toLong).toVector, s.toVector.drop(n))
    }
  }

  property("dropLast") {
    forAll { (s: Stream[Pure, Int]) =>
      assertEquals(s.dropLast.toVector, s.toVector.dropRight(1))
    }
  }

  property("dropLastIf") {
    forAll { (s: Stream[Pure, Int]) =>
      assertEquals(s.dropLastIf(_ => false).toVector, s.toVector)
      assertEquals(s.dropLastIf(_ => true).toVector, s.toVector.dropRight(1))
    }
  }

  property("dropRight") {
    forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else (n0 % v.size).abs
      val n = if (negate) -n1 else n1
      assertEquals(s.dropRight(n).toVector, v.dropRight(n))
    }
  }

  property("dropWhile") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs
      val set = s.toVector.take(n).toSet
      assertEquals(s.dropWhile(set).toVector, s.toVector.dropWhile(set))
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
      assertEquals(s.dropThrough(set).toVector, expected)
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
        assertEquals(result.size, 1)
        val head = result.head
        assert(clue(head.toMillis) >= clue(delay.toMillis - 5))
      }
  }

  test("either") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      val s2List = s2.toList
      s1.covary[IO].either(s2).compile.toList.map { result =>
        assertEquals(result.collect { case Left(i) => i }, s1List)
        assertEquals(result.collect { case Right(i) => i }, s2List)
      }
    }
  }

  group("evalSeq") {
    test("with List") {
      Stream
        .evalSeq(IO(List(1, 2, 3)))
        .compile
        .toList
        .assertEquals(List(1, 2, 3))
    }
    test("with Seq") {
      Stream.evalSeq(IO(Seq(4, 5, 6))).compile.toList.assertEquals(List(4, 5, 6))
    }
  }

  group("evalFilter") {
    test("with effectful const(true)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.evalFilter(constTrue).assertEmitsSameAs(s)
      }
    }

    test("with effectful const(false)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.evalFilter(constFalse).compile.last.assertEquals(None)
      }
    }

    test("with function that filters out odd elements") {
      Stream.range(1, 10).evalFilter(isEven).assertEmits(List(2, 4, 6, 8))
    }
  }

  group("evalFilterAsync") {
    test("with effectful const(true)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO].evalFilterAsync(5)(constTrue).assertEmitsSameAs(s)
      }
    }

    test("with effectful const(false)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO].evalFilterAsync(5)(constFalse).assertEmpty()
      }
    }

    test("with function that filters out odd elements") {
      Stream.range(1, 10).evalFilterAsync[IO](5)(isEven).assertEmits(List(2, 4, 6, 8))
    }

    test("filters up to N items in parallel".flaky) {
      val s = Stream.range(0, 100)
      val n = 5

      (Semaphore[IO](n.toLong), SignallingRef[IO, Int](0)).tupled
        .flatMap { case (sem, sig) =>
          val tested = s
            .covary[IO]
            .evalFilterAsync(n) { _ =>
              val ensureAcquired =
                sem.tryAcquire.ifM(
                  IO.unit,
                  IO.raiseError(new Throwable("Couldn't acquire permit"))
                )

              ensureAcquired
                .bracket(_ =>
                  sig.update(_ + 1).bracket(_ => IO.sleep(100.millis))(_ => sig.update(_ - 1))
                )(_ => sem.release)
                .as(true)
            }

          sig.discrete
            .interruptWhen(tested.drain.covaryOutput[Boolean])
            .fold1(_.max(_))
            .compile
            .lastOrError
            .product(sig.get)
        }
        .assertEquals(n -> 0)
    }
  }

  group("evalFilterNot") {
    test("with effectful const(true)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.evalFilterNot(constFalse).assertEmitsSameAs(s)
      }
    }

    test("with effectful const(false)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.evalFilterNot(constTrue).assertEmpty()
      }
    }

    test("with function that filters out odd elements") {
      Stream.range(1, 10).evalFilterNot(isEven).assertEmits(List(1, 3, 5, 7, 9))
    }
  }

  group("evalFilterNotAsync") {
    test("with effectful const(true)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO]
          .evalFilterNotAsync(5)(constTrue)
          .assertEmpty()
      }
    }

    test("with effectful const(false)") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO].evalFilterNotAsync(5)(constFalse).assertEmitsSameAs(s)
      }
    }

    test("with function that filters out odd elements") {
      Stream
        .range(1, 10)
        .evalFilterNotAsync[IO](5)(isEven)
        .assertEmits(List(1, 3, 5, 7, 9))
    }

    test("filters up to N items in parallel".flaky) {
      val s = Stream.range(0, 100)
      val n = 5

      (Semaphore[IO](n.toLong), SignallingRef[IO, Int](0)).tupled
        .flatMap { case (sem, sig) =>
          val tested = s
            .covary[IO]
            .evalFilterNotAsync(n) { _ =>
              val ensureAcquired =
                sem.tryAcquire.ifM(
                  IO.unit,
                  IO.raiseError(new Throwable("Couldn't acquire permit"))
                )

              ensureAcquired
                .bracket(_ =>
                  sig.update(_ + 1).bracket(_ => IO.sleep(100.millis))(_ => sig.update(_ - 1))
                )(_ => sem.release)
                .as(false)
            }

          sig.discrete
            .interruptWhen(tested.drain.covaryOutput[Boolean])
            .fold1(_.max(_))
            .compile
            .lastOrError
            .product(sig.get)
        }
        .assertEquals(n -> 0)
    }
  }

  test("evalMapAccumulate") {
    forAllF { (s: Stream[Pure, Int], m: Int, n0: Int) =>
      val sVector = s.toVector
      val n = (n0 % 20).abs + 1
      val f = (_: Int) % n == 0
      val r = s.covary[IO].evalMapAccumulate(m)((s, i) => IO.pure((s + i, f(i))))
      List(
        r.map(_._1).compile.toVector.assertEquals(sVector.scanLeft(m)(_ + _).tail),
        r.map(_._2).compile.toVector.assertEquals(sVector.map(f))
      ).sequence_
    }
  }

  group("evalMapFilter") {
    test("with effectful optional identity function") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.evalMapFilter(n => IO.pure(n.some)).assertEmitsSameAs(s)
      }
    }

    test("with effectful constant function that returns None for any element") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.evalMapFilter(_ => IO.pure(none[Int])).assertEmpty()
      }
    }

    test("with effectful function that filters out odd elements") {
      def isEven(e: Int): IO[Option[Int]] =
        IO.pure(e.some.filter(_ % 2 == 0))

      Stream.range(1, 10).evalMapFilter(isEven).assertEmits(List(2, 4, 6, 8))
    }
  }

  test("evalScan") {
    forAllF { (s: Stream[Pure, Int], n: String) =>
      val f: (String, Int) => IO[String] = (a: String, b: Int) => IO.pure(a + b)
      val g = (a: String, b: Int) => a + b
      val expected = s.toList.scanLeft(n)(g)
      s.covary[IO].evalScan(n)(f).assertEmits(expected)
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
      .take(draws.toLong)
      .through(durationSinceLastTrue)

    (IO.cede >> durationsSinceSpike.compile.toVector).map { result =>
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
      s.exists(f).assertEmits(List(s.toList.exists(f)))
    }
  }

  group("filter") {
    property("1") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val predicate = (i: Int) => i % n == 0
        s.filter(predicate).assertEmits(s.toList.filter(predicate))
      }
    }

    property("2") {
      forAll { (s: Stream[Pure, Double]) =>
        val predicate = (i: Double) => i - i.floor < 0.5
        val s2 = s.mapChunks(c => Chunk.array(c.toArray))
        assertEquals(s2.filter(predicate).toList, s2.toList.filter(predicate))
      }
    }

    property("3") {
      forAll { (s: Stream[Pure, Byte]) =>
        val predicate = (b: Byte) => b < 0
        val s2 = s.mapChunks(c => Chunk.array(c.toArray))
        s2.filter(predicate).assertEmits(s2.toList.filter(predicate))
      }
    }

    property("4") {
      forAll { (s: Stream[Pure, Boolean]) =>
        val predicate = (b: Boolean) => !b
        val s2 = s.mapChunks(c => Chunk.array(c.toArray))
        s2.filter(predicate).assertEmits(s2.toList.filter(predicate))
      }
    }
  }

  property("find") {
    forAll { (s: Stream[Pure, Int], i: Int) =>
      val predicate = (item: Int) => item < i
      s.find(predicate).assertEmits(s.toList.find(predicate).toList)
    }
  }
  group("fold") {
    property("1") {
      forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        s.fold(n)(f).assertEmits(List(s.toList.foldLeft(n)(f)))
      }
    }

    property("2") {
      forAll { (s: Stream[Pure, Int], n: String) =>
        val f = (a: String, b: Int) => a + b
        s.fold(n)(f).assertEmits(List(s.toList.foldLeft(n)(f)))
      }
    }
  }

  group("foldMonoid") {
    property("1") {
      forAll { (s: Stream[Pure, Int]) =>
        assertEquals(s.foldMonoid.toVector, Vector(s.toVector.combineAll))
      }
    }

    property("2") {
      forAll { (s: Stream[Pure, Double]) =>
        assertEquals(s.foldMonoid.toVector, Vector(s.toVector.combineAll))
      }
    }
  }

  property("fold1") {
    forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      val expected = v.headOption.fold(Vector.empty[Int])(h => Vector(v.drop(1).foldLeft(h)(f)))
      assertEquals(s.fold1(f).toVector, expected)
    }
  }

  property("foldable") {
    forAll((c: List[Int]) => assertEquals(Stream.foldable(c).toList, c))
  }

  property("forall") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val f = (i: Int) => i % n == 0
      assertEquals(s.forall(f).toList, List(s.toList.forall(f)))
    }
  }

  property("fromEither") {
    forAll { (either: Either[Throwable, Int]) =>
      val stream: Stream[Fallible, Int] = Stream.fromEither[Fallible](either)
      either match {
        case Left(t)  => assertEquals(stream.toList, Left(t))
        case Right(i) => assertEquals(stream.toList, Right(List(i)))
      }
    }
  }

  property("fromOption") {
    forAll { (option: Option[Int]) =>
      val stream: Stream[Pure, Int] = Stream.fromOption[Pure](option)
      option match {
        case None    => assertEquals(stream.toList, List.empty)
        case Some(i) => assertEquals(stream.toList, List(i))
      }
    }
  }

  test("fromIterator") {
    forAllF { (x: List[Int], cs: Int) =>
      val chunkSize = (cs % 4096).abs + 1
      Stream.fromIterator[IO](x.iterator, chunkSize).assertEmits(x)
    }
  }

  test("fromBlockingIterator") {
    forAllF { (x: List[Int], cs: Int) =>
      val chunkSize = (cs % 4096).abs + 1
      Stream.fromBlockingIterator[IO](x.iterator, chunkSize).assertEmits(x)
    }
  }

  property("groupAdjacentBy") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val f = (i: Int) => i % n
      val s1 = s.groupAdjacentBy(f)
      val s2 = s.map(f).changes
      assertEquals(s1.map(_._2).toList.flatMap(_.toList), s.toList)
      assertEquals(s1.map(_._1).toList, s2.toList)
      assertEquals(
        s1.map { case (k, vs) => vs.toVector.forall(f(_) == k) }.toList,
        s2.as(true).toList
      )
    }
  }

  property("groupAdjacentByLimit") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      val s1 = s.groupAdjacentByLimit(n)(_ => true)
      assertEquals(s1.map(_._2).toList.map(_.toList), s.toList.grouped(n).toList)
    }
  }

  group("groupWithin") {
    test("should never lose any elements") {
      forAllF { (s0: Stream[Pure, Int], d0: Int, maxGroupSize0: Int) =>
        val maxGroupSize = (maxGroupSize0 % 20).abs + 1
        val d = (d0 % 50).abs.millis
        val s = s0.map(i => (i % 500).abs)
        s.covary[IO]
          .evalTap(shortDuration => IO.sleep(shortDuration.micros))
          .groupWithin(maxGroupSize, d)
          .flatMap(s => Stream.emits(s.toList))
          .assertEmitsSameAs(s)
      }
    }

    test("should never emit empty groups") {
      forAllF { (s: Stream[Pure, Int], d0: Int, maxGroupSize0: Int) =>
        val maxGroupSize = (maxGroupSize0 % 20).abs + 1
        val d = (d0 % 50).abs.millis

        s
          .map(i => (i % 500).abs)
          .covary[IO]
          .evalTap(shortDuration => IO.sleep(shortDuration.micros))
          .groupWithin(maxGroupSize, d)
          .compile
          .toList
          .map(it => assert(it.forall(_.nonEmpty)))
      }
    }

    test("should never have more elements than in its specified limit") {
      forAllF { (s: Stream[Pure, Int], d0: Int, maxGroupSize0: Int) =>
        val maxGroupSize = (maxGroupSize0 % 20).abs + 1
        val d = (d0 % 50).abs.millis
        s.map(i => (i % 500).abs)
          .evalTap(shortDuration => IO.sleep(shortDuration.micros))
          .groupWithin(maxGroupSize, d)
          .compile
          .toList
          .map(it => assert(it.forall(_.size <= maxGroupSize)))
      }
    }

    test("should be equivalent to chunkN when no timeouts trigger") {
      val s = Stream.range(0, 100)
      val size = 5

      val groupedWithin = s.covary[IO].groupWithin(size, 1.second).map(_.toList)
      val expected = s.chunkN(size).map(_.toList)

      groupedWithin.assertEmitsSameAs(expected)
    }

    test(
      "should return a finite stream back in a single chunk given a group size equal to the stream size and an absurdly high duration"
    ) {
      forAllF { (streamAsList0: List[Int]) =>
        val streamAsList = 0 :: streamAsList0
        Stream
          .emits(streamAsList)
          .covary[IO]
          .groupWithin(streamAsList.size, (Int.MaxValue - 1L).nanoseconds)
          .compile
          .toList
          .map(_.head.toList)
          .assertEquals(streamAsList)
      }
    }

    test("accumulation and splitting") {
      val t = 200.millis
      val size = 5
      val sleep = Stream.sleep_[IO](2 * t)

      def chunk(from: Int, size: Int) =
        Stream.range(from, from + size).chunkAll.unchunks

      // this test example is designed to have good coverage of
      // the chunk manipulation logic in groupWithin
      val source =
        chunk(from = 1, size = 3) ++
          sleep ++
          chunk(from = 4, size = 12) ++
          chunk(from = 16, size = 7)

      val expected = List(
        List(1, 2, 3),
        List(4, 5, 6, 7, 8),
        List(9, 10, 11, 12, 13),
        List(14, 15, 16, 17, 18),
        List(19, 20, 21, 22)
      )

      source.groupWithin(size, t).map(_.toList).assertEmits(expected)
    }

    test("accumulation and splitting 2") {
      val t = 200.millis
      val size = 5
      val sleep = Stream.sleep_[IO](2 * t)
      val longSleep = sleep.repeatN(5)

      def chunk(from: Int, size: Int) =
        Stream.range(from, from + size).chunkAll.unchunks

      // this test example is designed to have good coverage of
      // the chunk manipulation logic in groupWithin
      val source =
        chunk(from = 1, size = 3) ++
          sleep ++
          chunk(from = 4, size = 1) ++ longSleep ++
          chunk(from = 5, size = 11) ++
          chunk(from = 16, size = 7)

      val expected = List(
        List(1, 2, 3),
        List(4),
        List(5, 6, 7, 8, 9),
        List(10, 11, 12, 13, 14),
        List(15, 16, 17, 18, 19),
        List(20, 21, 22)
      )

      source.groupWithin(size, t).map(_.toList).assertEmits(expected)
    }

    test("does not reset timeout if nothing is emitted") {
      TestControl
        .executeEmbed(
          Ref[IO]
            .of(0.millis)
            .flatMap { ref =>
              val timeout = 5.seconds

              def measureEmission[A]: Pipe[IO, A, A] =
                _.chunks
                  .evalTap(_ => IO.monotonic.flatMap(ref.set))
                  .unchunks

              // emits elements after the timeout has expired
              val source =
                Stream.sleep_[IO](timeout + 200.millis) ++
                  Stream(4, 5) ++
                  Stream.never[IO] // avoids emission due to source termination

              source
                .through(measureEmission)
                .groupWithin(5, timeout)
                .evalMap(_ => (IO.monotonic, ref.get).mapN(_ - _))
                .interruptAfter(timeout * 3)
                .compile
                .lastOrError
            }
        )
        .assertEquals(0.millis) // The stream emits after the timeout
      // so groupWithin should re-emit with zero delay
    }

    test("Edge case: should not introduce unnecessary delays when groupSize == chunkSize") {
      TestControl
        .executeEmbed(
          Ref[IO]
            .of(0.millis)
            .flatMap { ref =>
              val timeout = 5.seconds

              def measureEmission[A]: Pipe[IO, A, A] =
                _.chunks
                  .evalTap(_ => IO.monotonic.flatMap(ref.set))
                  .unchunks

              val source =
                Stream(1, 2, 3) ++
                  Stream.sleep_[IO](timeout + 200.millis)

              source
                .through(measureEmission)
                .groupWithin(3, timeout)
                .evalMap(_ => (IO.monotonic, ref.get).mapN(_ - _))
                .compile
                .lastOrError
            }
        )
        .assertEquals(0.millis)
    }

    test("stress test (short execution): all elements are processed") {

      val rangeLength = 100000

      Stream
        .eval(Ref[IO].of(0))
        .flatMap { counter =>
          Stream
            .range(0, rangeLength)
            .covary[IO]
            .groupWithin(256, 100.micros)
            .evalTap(ch => counter.update(_ + ch.size)) *> Stream.eval(counter.get)
        }
        .compile
        .lastOrError
        .assertEquals(rangeLength)

    }

    // ignoring because it's a (relatively) long running test (around 3/4 minutes), but it's useful
    // to asses the validity of permits management and timeout logic over an extended period of time
    test("stress test (long execution): all elements are processed".ignore) {
      val rangeLength = 10000000

      Stream
        .eval(Ref[IO].of(0))
        .flatMap { counter =>
          Stream
            .range(0, rangeLength)
            .covary[IO]
            .evalTap(d => IO.sleep((d % 10 + 2).micros))
            .groupWithin(275, 5.millis)
            .evalTap(ch => counter.update(_ + ch.size)) *> Stream.eval(counter.get)
        }
        .compile
        .lastOrError
        .assertEquals(rangeLength)
    }

    test("upstream failures are propagated downstream") {

      case object SevenNotAllowed extends NoStackTrace

      val source = Stream
        .unfold(0)(s => Some((s, s + 1)))
        .covary[IO]
        .evalMap(n => if (n == 7) IO.raiseError(SevenNotAllowed) else IO.pure(n))

      val downstream = source.groupWithin(100, 2.seconds)

      downstream.compile.lastOrError.intercept[SevenNotAllowed.type]
    }

    test(
      "upstream interruption causes immediate downstream termination with all elements being emitted"
    ) {

      val sourceTimeout = 5.5.seconds
      val downstreamTimeout = sourceTimeout + 2.seconds

      TestControl
        .executeEmbed(
          Ref[IO]
            .of(0.millis)
            .flatMap { ref =>
              val source: Stream[IO, Int] =
                Stream
                  .unfold(0)(s => Some((s, s + 1)))
                  .covary[IO]
                  .meteredStartImmediately(1.second)
                  .interruptAfter(sourceTimeout)

              // large chunkSize and timeout (no emissions expected in the window
              // specified, unless source ends, due to interruption or
              // natural termination (i.e runs out of elements)
              val downstream: Stream[IO, Chunk[Int]] =
                source.groupWithin(Int.MaxValue, 1.day)

              downstream.compile.lastOrError
                .map(_.toList)
                .timeout(downstreamTimeout)
                .flatTap(_ => IO.monotonic.flatMap(ref.set))
                .flatMap(emit => ref.get.map(timeLapsed => (timeLapsed, emit)))
            }
        )
        .assertEquals(
          // downstream ended immediately (i.e timeLapsed = sourceTimeout)
          // emitting whatever was accumulated at the time of interruption
          (sourceTimeout, List(0, 1, 2, 3, 4, 5))
        )
    }

    test(
      "Edge case: if the buffer fills up and timeout expires at the same time there won't be a deadlock"
    ) {

      forAllF { (s0: Stream[Pure, Int], b: Byte) =>
        TestControl
          .executeEmbed {

            // preventing empty or singleton streams that would bypass the logic being tested
            val n = b.max(2).toInt
            val s = s0 ++ Stream.range(0, n)

            // the buffer will reach its full capacity every
            // n seconds exactly when the timeout expires
            s
              .covary[IO]
              .metered(1.second)
              .groupWithin(n, n.seconds)
              .map(_.toList)
              .assertCompletes
          }
      }
    }
  }

  property("head")(forAll((s: Stream[Pure, Int]) => assertEquals(s.head.toList, s.toList.take(1))))

  group("ifEmpty") {
    test("when empty") {
      assertEquals(Stream().ifEmptyEmit(0).compile.toList, List(0))
    }
    test("when nonEmpty") {
      assertEquals(Stream(1).ifEmptyEmit(0).compile.toList, List(1))
    }
  }

  group("interleave") {
    test("interleave left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      ones.interleave(s).assertEmits(List("1", "A", "1", "B", "1", "C"))
      s.interleave(ones).assertEmits(List("A", "1", "B", "1", "C", "1"))
    }

    test("interleave both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      ones.interleave(as).take(3).assertEmits(List("1", "A", "1"))
      as.interleave(ones).take(3).assertEmits(List("A", "1", "A"))
    }

    test("interleaveAll left/right side infinite") {
      val ones = Stream.constant("1")
      val s = Stream("A", "B", "C")
      assertEquals(
        ones.interleaveAll(s).take(9).toList,
        List("1", "A", "1", "B", "1", "C", "1", "1", "1")
      )
      assertEquals(
        s.interleaveAll(ones).take(9).toList,
        List("A", "1", "B", "1", "C", "1", "1", "1", "1")
      )
    }

    test("interleaveAll both side infinite") {
      val ones = Stream.constant("1")
      val as = Stream.constant("A")
      assertEquals(ones.interleaveAll(as).take(3).toList, List("1", "A", "1"))
      assertEquals(as.interleaveAll(ones).take(3).toList, List("A", "1", "A"))
    }

    // Uses a small scope to avoid using time to generate too large streams and not finishing
    property("interleave is equal to interleaveAll on infinite streams (by step-indexing)") {
      forAll(Gen.chooseNum(0, 100)) { (n: Int) =>
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assertEquals(
          ones.interleaveAll(as).take(n.toLong).toVector,
          ones.interleave(as).take(n.toLong).toVector
        )
      }
    }

    property("interleaveOrdered for ordered streams emits stable-sorted stream with same data") {
      // stability estimating element type and ordering
      type Elem = (Int, Byte)
      implicit val ordering: Ordering[Elem] = Ordering.by(_._1)
      implicit val order: cats.Order[Elem] = cats.Order.fromOrdering

      type SortedData = Vector[Chunk[Elem]]
      implicit val arbSortedData: Arbitrary[SortedData] = Arbitrary(
        for {
          sortedData <- Arbitrary.arbContainer[Array, Elem].arbitrary.map(_.sorted)
          splitIdxs <- Gen.someOf(sortedData.indices).map(_.sorted)
          borders = (0 +: splitIdxs).zip(splitIdxs :+ sortedData.length)
        } yield borders.toVector
          .map { case (from, to) =>
            Chunk.array(sortedData, from, to - from)
          }
      )

      def mkStream(parts: SortedData): Stream[Pure, Elem] = parts.map(Stream.chunk).combineAll

      forAll { (sortedL: SortedData, sortedR: SortedData) =>
        mkStream(sortedL)
          .interleaveOrdered(mkStream(sortedR))
          .assertEmits(
            (sortedL ++ sortedR).toList.flatMap(_.toList).sorted // std .sorted is stable
          )
      }
    }

    test("interleaveOrdered - fromQueueNoneTerminated") {
      for {
        q1 <- Queue.unbounded[IO, Option[Int]]
        q2 <- Queue.unbounded[IO, Option[Int]]
        s1 = Stream.fromQueueNoneTerminated(q1)
        s2 = Stream.fromQueueNoneTerminated(q2)
        _ <- Vector(Chunk(1, 2), Chunk(3, 5, 7)).traverse(chunk =>
          q1.tryOfferN(chunk.toList.map(_.some))
        )
        _ <- Vector(Chunk(2), Chunk.empty, Chunk(4, 6)).traverse(chunk =>
          q2.tryOfferN(chunk.toList.map(_.some))
        )
        _ <- q1.offer(None)
        _ <- q2.offer(None)
        results <- s1.interleaveOrdered(s2).compile.toList
      } yield assertEquals(results, List(1, 2, 2, 3, 4, 5, 6, 7))
    }
  }

  property("intersperse") {
    forAll { (s: Stream[Pure, Int], n: Int) =>
      assertEquals(s.intersperse(n).toList, s.toList.flatMap(i => List(i, n)).dropRight(1))
    }
  }

  property("iterable") {
    forAll((c: Set[Int]) => assertEquals(Stream.iterable(c).compile.to(Set), c))
  }

  test("iterate") {
    assertEquals(Stream.iterate(0)(_ + 1).take(100).toList, List.iterate(0, 100)(_ + 1))
  }

  test("iterateEval") {
    Stream
      .iterateEval(0)(i => IO(i + 1))
      .take(100)
      .assertEmits(List.iterate(0, 100)(_ + 1))
  }

  property("last") {
    forAll { (s: Stream[Pure, Int]) =>
      s.last.assertEmits(List(s.toList.lastOption))
    }
  }

  property("lastOr") {
    forAll { (s: Stream[Pure, Int], n0: Int) =>
      val n = (n0 % 20).abs + 1
      assertEquals(s.lastOr(n).toList, List(s.toList.lastOption.getOrElse(n)))
    }
  }

  property("mapAccumulate") {
    forAll { (s: Stream[Pure, Int], m: Int, n0: Int) =>
      val n = (n0 % 20).abs + 1
      val f = (_: Int) % n == 0
      val r = s.mapAccumulate(m)((s, i) => (s + i, f(i)))

      assertEquals(r.map(_._1).toList, s.toList.scanLeft(m)(_ + _).tail)
      assertEquals(r.map(_._2).toList, s.toList.map(f))
    }
  }

  group("mapAsync") {
    test("same as map") {
      forAllF { (s: Stream[Pure, Int]) =>
        val f = (_: Int) + 1
        val r = s.covary[IO].mapAsync(16)(i => IO(f(i)))
        val sVector = s.toVector
        r.compile.toVector.assertEquals(sVector.map(f))
      }
    }

    test("exception") {
      forAllF { (s: Stream[Pure, Int]) =>
        val f = (_: Int) => IO.raiseError[Int](new RuntimeException)
        val r = (s ++ Stream(1)).covary[IO].mapAsync(1)(f).attempt
        r.compile.toVector.map(_.size).assertEquals(1)
      }
    }
  }

  test("metered should not start immediately") {
    TestControl.executeEmbed {
      Stream
        .emit[IO, Int](1)
        .repeatN(10)
        .metered(1.second)
        .interruptAfter(500.milliseconds)
        .assertEmpty()
    }
  }

  test("meteredStartImmediately should start immediately") {
    TestControl.executeEmbed {
      Stream
        .emit[IO, Int](1)
        .repeatN(10)
        .meteredStartImmediately(1.second)
        .interruptAfter(500.milliseconds)
        .assertEmits(List(1))
    }
  }

  test("spaced should start immediately if startImmediately is not set") {
    TestControl.executeEmbed {
      Stream
        .emit[IO, Int](1)
        .repeatN(10)
        .spaced(1.second)
        .interruptAfter(500.milliseconds)
        .assertEmits(List(1))
    }
  }

  test("spaced should not start immediately if startImmediately is set to false") {
    TestControl.executeEmbed {
      Stream
        .emit[IO, Int](1)
        .repeatN(10)
        .spaced(1.second, startImmediately = false)
        .interruptAfter(500.milliseconds)
        .assertEmpty()
    }
  }

  test("metered should not wait between events that last longer than the rate") {
    TestControl.executeEmbed {
      Stream
        .eval[IO, Int](IO.sleep(1.second).as(1))
        .repeatN(10)
        .metered(1.second)
        .interruptAfter(4500.milliseconds)
        .compile
        .toList
        .map(results => assert(results.size == 3))
    }
  }

  test("meteredStartImmediately should not wait between events that last longer than the rate") {
    TestControl.executeEmbed {
      Stream
        .eval[IO, Int](IO.sleep(1.second).as(1))
        .repeatN(10)
        .meteredStartImmediately(1.second)
        .interruptAfter(4500.milliseconds)
        .compile
        .toList
        .map(results => assert(results.size == 4))
    }
  }

  test("spaced should wait between events") {
    TestControl.executeEmbed {
      Stream
        .eval[IO, Int](IO.sleep(1.second).as(1))
        .repeatN(10)
        .spaced(1.second)
        .interruptAfter(4500.milliseconds)
        .compile
        .toList
        .map(results => assert(results.size == 2))
    }
  }

  test("mapAsyncUnordered") {
    forAllF { (s: Stream[Pure, Int]) =>
      val f = (_: Int) + 1
      val r = s.covary[IO].mapAsyncUnordered(16)(i => IO(f(i)))
      val sVector = s.toVector
      r.compile.toVector.map(_.toSet).assertEquals(sVector.map(f).toSet)
    }
  }

  group("pauseWhen") {
    test("pause and resume".flaky) {
      SignallingRef[IO, Boolean](false)
        .product(Ref[IO].of(0))
        .flatMap { case (pause, counter) =>
          def counterChangesFrom(i: Int): IO[Unit] =
            counter.get.flatMap { v =>
              IO.cede >> counterChangesFrom(i).whenA(i == v)
            }

          def counterStopsChanging: IO[Int] = {
            def loop(i: Int): IO[Int] =
              IO.cede >> counter.get.flatMap { v =>
                if (i == v) i.pure[IO] else loop(i)
              }

            counter.get.flatMap(loop)
          }

          val stream =
            Stream
              .iterate(0)(_ + 1)
              .covary[IO]
              .evalMap(i => counter.set(i) >> IO.cede)
              .pauseWhen(pause)

          val behaviour = for {
            _ <- counterChangesFrom(0)
            _ <- pause.set(true)
            v <- counterStopsChanging
            _ <- pause.set(false)
            _ <- counterChangesFrom(v)
          } yield ()

          for {
            fiber <- stream.compile.drain.start
            _ <- behaviour.timeout(5.seconds).guarantee(fiber.cancel)
          } yield ()
        }
    }

    test("starts in paused state") {
      SignallingRef[IO, Boolean](true)
        .product(Ref[IO].of(false))
        .flatMap { case (pause, written) =>
          Stream
            .eval(written.set(true))
            .pauseWhen(pause)
            .timeout(200.millis)
            .compile
            .drain
            .attempt >> written.get.assertEquals(false)
        }
    }
  }

  group("prefetch") {
    test("identity: a prefeteched stream emits same outputs as the source") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO].prefetch.assertEmitsSameAs(s)
      }
    }

    test("timing") {
      TestControl
        .executeEmbed(
          IO.monotonic.flatMap { start =>
            Stream(1, 2, 3)
              .evalMap(_ => IO.sleep(1.second))
              .prefetch
              .evalMap(_ => IO.sleep(1.second))
              .compile
              .drain >> IO.monotonic.map(_ - start)
          }
        )
        .assertEquals(4.seconds)
    }
  }

  test("range") {
    assertEquals(Stream.range(0, 100).toList, List.range(0, 100))
    assertEquals(Stream.range(0, 1).toList, List.range(0, 1))
    assertEquals(Stream.range(0, 0).toList, List.range(0, 0))
    assertEquals(Stream.range(0, 101, 2).toList, List.range(0, 101, 2))
    assertEquals(Stream.range(5, 0, -1).toList, List.range(5, 0, -1))
    assertEquals(Stream.range(5, 0, 1).toList, Nil)
    assertEquals(Stream.range(10, 50, 0).toList, Nil)
  }

  property("ranges") {
    forAll(Gen.chooseNum(1, 101)) { size =>
      val result = Stream
        .ranges(0, 100, size)
        .flatMap { case (i, j) => Stream.emits(i until j) }
        .toList
      assertEquals(result, List.range(0, 100))
    }
  }

  group("rechunkRandomlyWithSeed") {
    property("is deterministic") {
      forAll { (s0: Stream[Pure, Int], seed: Long) =>
        def s = s0.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed)
        assertEquals(s.toList, s.toList)
      }
    }

    property("does not drop elements") {
      forAll { (s: Stream[Pure, Int], seed: Long) =>
        assertEquals(
          s.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed).toList,
          s.toList
        )
      }
    }

    property("chunk size in interval [inputChunk.size * minFactor, inputChunk.size * maxFactor]") {
      forAll { (s: Stream[Pure, Int], seed: Long) =>
        val c = s.chunks.toVector
        if (c.nonEmpty) {
          val (min, max) = c.tail.foldLeft(c.head.size -> c.head.size) { case ((min, max), c) =>
            Math.min(min, c.size) -> Math.max(max, c.size)
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

  group("rechunkRandomly") {
    test("output identity: the rechunked stream emits same output values as source") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.rechunkRandomly[IO]().assertEmitsSameAs(s)
      }
    }
  }

  test("repartition") {
    assertEquals(
      Stream("Lore", "m ip", "sum dolo", "r sit amet")
        .repartition(s => Chunk.array(s.split(" ")))
        .toList,
      List("Lorem", "ipsum", "dolor", "sit", "amet")
    )
    assertEquals(
      Stream("hel", "l", "o Wor", "ld")
        .repartition(s => Chunk.indexedSeq(s.grouped(2).toVector))
        .toList,
      List("he", "ll", "o ", "Wo", "rl", "d")
    )
    Stream.empty.covaryOutput[String].repartition(_ => Chunk.empty).assertEmpty()
    Stream("hello").repartition(_ => Chunk.empty).assertEmpty()

    def input = Stream("ab").repeat
    def ones(s: String) = Chunk.vector(s.grouped(1).toVector)
    assertEquals(input.take(2).repartition(ones).toVector, Vector("a", "b", "a", "b"))
    assertEquals(
      input.take(4).repartition(ones).toVector,
      Vector("a", "b", "a", "b", "a", "b", "a", "b")
    )
    assertEquals(input.repartition(ones).take(2).toVector, Vector("a", "b"))
    assertEquals(input.repartition(ones).take(4).toVector, Vector("a", "b", "a", "b"))
    assertEquals(
      Stream.emits(input.take(4).toVector).repartition(ones).toVector,
      Vector("a", "b", "a", "b", "a", "b", "a", "b")
    )
    assertEquals(
      Stream(1, 2, 3, 4, 5).repartition(i => Chunk(i, i)).toList,
      List(1, 3, 6, 10, 15, 15)
    )
    assertEquals(
      Stream(1, 10, 100).repartition(_ => Chunk.seq(1 to 1000)).take(4).toList,
      List(1, 2, 3, 4)
    )
  }

  group("scan") {
    property("1") {
      forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        assertEquals(s.scan(n)(f).toList, s.toList.scanLeft(n)(f))
      }
    }

    test("2") {
      val s = Stream(1).map(x => x)
      val f = (a: Int, b: Int) => a + b
      assertEquals(s.scan(0)(f).toList, s.toList.scanLeft(0)(f))
    }

    test("temporal") {
      val never = Stream.eval(IO.async_[Int](_ => ()))
      val s = Stream(1)
      val f = (a: Int, b: Int) => a + b
      val result = s.toList.scanLeft(0)(f)
      s.append(never)
        .scan(0)(f)
        .take(result.size.toLong)
        .assertEmits(result)
    }
  }

  property("scan1") {
    forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      assertEquals(
        s.scan1(f).toVector,
        v.headOption.fold(Vector.empty[Int])(h => v.drop(1).scanLeft(h)(f))
      )
    }
  }

  property("sliding") {
    forAll { (s: Stream[Pure, Int], n0: Int, n1: Int) =>
      val size = (n0 % 20).abs + 1
      val step = (n1 % 20).abs + 1

      assertEquals(
        s.sliding(size, step).toList.map(_.toList),
        s.toList.sliding(size, step).map(_.toList).toList
      )
    }
  }

  test("sliding shouldn't swallow last issue-2428") {
    forAllF { (n0: Int, n1: Int, n2: Int) =>
      val streamSize = (n0 % 1000).abs + 1
      val size = (n1 % 20).abs + 1
      val step = (n2 % 20).abs + 1

      val action =
        Vector.fill(streamSize)(Deferred[IO, Unit]).sequence.map { seenArr =>
          def peek(ind: Int)(f: Option[Unit] => Boolean) =
            seenArr.get(ind.toLong).fold(true.pure[IO])(_.tryGet.map(f))

          Stream
            .emits(0 until streamSize)
            .chunkLimit(1)
            .unchunks
            .evalTap(seenArr(_).complete(()))
            .sliding(size, step)
            .evalMap { chunk =>
              val next = chunk.head.get + size + step - 1
              val viewed = chunk.map(i => peek(i)(_.nonEmpty))
              val notViewed = Chunk.singleton(peek(next)(_.isEmpty))
              (notViewed ++ viewed).sequence
            }
            .unchunks
        }

      Stream.force(action).compile.fold(true)(_ && _).assert
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
        assertEquals(
          s2.chunkLimit(n)
            .intersperse(Chunk.singleton(0))
            .unchunks
            .split(_ == 0)
            .filter(_.nonEmpty)
            .toVector,
          s2.chunkLimit(n).filter(_.nonEmpty).toVector,
          s"n = $n, s = ${s.toList}, s2 = " + s2.toList
        )
      }
    }

    test("2") {
      assertEquals(
        Stream(1, 2, 0, 0, 3, 0, 4).split(_ == 0).toVector.map(_.toVector),
        Vector(
          Vector(1, 2),
          Vector(),
          Vector(3),
          Vector(4)
        )
      )
      assertEquals(
        Stream(1, 2, 0, 0, 3, 0).split(_ == 0).toVector.map(_.toVector),
        Vector(
          Vector(1, 2),
          Vector(),
          Vector(3)
        )
      )
      assertEquals(
        Stream(1, 2, 0, 0, 3, 0, 0).split(_ == 0).toVector.map(_.toVector),
        Vector(
          Vector(1, 2),
          Vector(),
          Vector(3),
          Vector()
        )
      )
    }
  }

  property("tail")(forAll((s: Stream[Pure, Int]) => assertEquals(s.tail.toList, s.toList.drop(1))))

  test("unfold") {
    Stream
      .unfold((0, 1)) { case (f1, f2) =>
        if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
      }
      .map(_._1)
      .assertEmits(List(0, 1, 1, 2, 3, 5, 8, 13))
  }

  test("unfoldChunk") {
    def expand(s: Long): Option[(Chunk[Long], Long)] =
      if (s > 0) Some((Chunk.array(Array[Long](s, s)), s - 1)) else None

    Stream
      .unfoldChunk(4L)(expand)
      .assertEmits(List(4, 4, 3, 3, 2, 2, 1, 1))
  }

  test("unfoldEval") {
    Stream
      .unfoldEval(10)(s => IO.pure(if (s > 0) Some((s, s - 1)) else None))
      .assertEmits(List.range(10, 0, -1))
  }

  test("unfoldChunkEval") {
    Stream
      .unfoldChunkEval(true)(s =>
        SyncIO.pure(
          if (s) Some((Chunk.array(Array[Boolean](s)), false))
          else None
        )
      )
      .compile
      .toList
      .assertEquals(List(true))
  }

  property("unNone") {
    forAll { (s: Stream[Pure, Option[Int]]) =>
      assertEquals(
        s.unNone.chunks.toList,
        s.filter(_.isDefined).map(_.get).chunks.toList
      )
    }
  }

  group("withFilter") {
    test("filter in for comprehension") {
      val stream = for {
        value <- Stream.range(0, 10)
        if value % 2 == 0
      } yield value
      stream.assertEmits(List(0, 2, 4, 6, 8))
    }
  }

  group("withTimeout") {
    test("timeout never-ending stream") {
      TestControl.executeEmbed {
        Stream.never[IO].timeout(100.millis).intercept[TimeoutException]
      }
    }

    test("not trigger timeout on successfully completed stream") {
      TestControl.executeEmbed {
        Stream.sleep[IO](10.millis).timeout(1.second).compile.drain
      }
    }

    test("compose timeouts d1 and d2 when d1 < d2") {
      TestControl.executeEmbed {
        val d1 = 20.millis
        val d2 = 30.millis
        (Stream.sleep[IO](10.millis).timeout(d1) ++ Stream.sleep[IO](30.millis))
          .timeout(d2)
          .intercept[TimeoutException]
      }
    }

    test("compose timeouts d1 and d2 when d1 > d2") {
      TestControl.executeEmbed {
        val d1 = 40.millis
        val d2 = 30.millis
        (Stream.sleep[IO](10.millis).timeout(d1) ++ Stream.sleep[IO](25.millis))
          .timeout(d2)
          .intercept[TimeoutException]
      }
    }
  }

  group("limit") {
    test("limit a stream with > n elements, leaving chunks untouched") {
      val s = Stream(1, 2) ++ Stream(3, 4)
      s.covary[IO]
        .limit[IO](5)
        .chunks
        .map(_.toList)
        .assertEmits(List(List(1, 2), List(3, 4)))
    }

    test("allow a stream with exactly n elements, leaving chunks untouched") {
      val s = Stream(1, 2) ++ Stream(3, 4)
      s.covary[IO]
        .limit[IO](4)
        .chunks
        .map(_.toList)
        .assertEmits(List(List(1, 2), List(3, 4)))
    }

    test("emit exactly n elements in case of error") {
      val s = Stream(1, 2) ++ Stream(3, 4)
      s.covary[IO]
        .limit[IO](3)
        .recoverWith { case _: IllegalStateException => Stream.empty }
        .chunks
        .map(_.toList)
        .assertEmits(List(List(1, 2), List(3)))
    }

    test("raise IllegalStateException when stream exceeds n elements") {
      val s = Stream(1, 2) ++ Stream(3, 4)
      s.covary[IO]
        .limit[IO](3)
        .chunks
        .map(_.toList)
        .compile
        .last
        .intercept[IllegalStateException]
        .void
        .assert
    }
  }

  private val constTrue: Int => IO[Boolean] = _ => IO.pure(true)
  private def constFalse: Int => IO[Boolean] = _ => IO.pure(false)
  private def isEven(e: Int): IO[Boolean] = IO(e % 2 == 0)

}
