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

import scala.annotation.{nowarn, tailrec}
import scala.concurrent.duration._

import cats.data.Chain
import cats.effect.{Deferred, IO, Outcome, Ref, Resource, SyncIO}
import cats.effect.std.Queue
import cats.syntax.all._
import org.scalacheck.Gen
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Prop.forAll
import org.scalacheck.effect.PropF.forAllF

import fs2.concurrent.SignallingRef

@nowarn("cat=w-flag-dead-code")
class StreamSuite extends Fs2Suite {

  group("basics") {

    property("append consistent with list concat") {
      forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        assertEquals((s1 ++ s2).toList, s1.toList ++ s2.toList)
      }
    }

    test("construction via apply") {
      assertEquals(Stream(1, 2, 3).toList, List(1, 2, 3))
    }

    property(">> consistent with list flatMap") {
      forAll { (s: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        assertEquals((s >> s2).toList, s.flatMap(_ => s2).toList)
      }
    }

    property("chunk") {
      forAll((c: Chunk[Int]) => assertEquals(Stream.chunk(c).compile.to(Chunk), c))
    }

    property("chunkLimit") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val sizeV = s.chunkLimit(n).toVector.map(_.size)
        assert(sizeV.forall(_ <= n))
        assertEquals(sizeV.combineAll, s.toVector.size)
      }
    }

    property("chunkMin") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val chunkedV = s.chunkMin(n, true).toVector
        val withIfSmallerV = s.chunkMin(n, false).toVector
        val unchunkedV = s.toVector
        val smallerSet = s.take(n - 1L).toVector
        val smallerN = s.take(n - 1L).chunkMin(n, false).toVector
        val smallerY = s.take(n - 1L).chunkMin(n, true).toVector
        // All but last list have n values
        assert(chunkedV.dropRight(1).forall(_.size >= n))
        // Equivalent to last chunk with allowFewerTotal
        if (chunkedV.nonEmpty && chunkedV.last.size < n)
          assertEquals(chunkedV.dropRight(1), withIfSmallerV)
        // Flattened sequence with allowFewerTotal true is equal to vector without chunking
        assertEquals(chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector), unchunkedV)
        // If smaller than Chunk Size and allowFewerTotal false is empty then
        // no elements should be emitted
        assertEquals(smallerN, Vector.empty)
        // If smaller than Chunk Size and allowFewerTotal true is equal to the size
        // of the taken chunk initially
        assertEquals(smallerY.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector), smallerSet)
      }
    }

    group("chunkN") {
      property("fewer") {
        forAll { (s: Stream[Pure, Int], n0: Int) =>
          val n = (n0 % 20).abs + 1
          val chunkedV = s.chunkN(n, true).toVector
          val unchunkedV = s.toVector
          // All but last list have n0 values
          assert(chunkedV.dropRight(1).forall(_.size == n))
          // Last list has at most n0 values
          assert(chunkedV.lastOption.forall(_.size <= n))
          // Flattened sequence is equal to vector without chunking
          assertEquals(chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector), unchunkedV)
        }
      }

      property("no-fewer") {
        forAll { (s: Stream[Pure, Int], n0: Int) =>
          val n = (n0 % 20).abs + 1
          val chunkedV = s.chunkN(n, false).toVector
          val unchunkedV = s.toVector
          val expectedSize = unchunkedV.size - (unchunkedV.size % n)
          // All lists have n0 values
          assert(chunkedV.forall(_.size == n))
          // Flattened sequence is equal to vector without chunking, minus "left over" values that could not fit in a chunk
          val left = chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector)
          val right = unchunkedV.take(expectedSize)
          assertEquals(left, right)
        }
      }
    }

    group("chunks") {
      property("chunks.map identity") {
        forAll { (v: Vector[Vector[Int]]) =>
          val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
          assertEquals(s.chunks.map(_.toVector).toVector, v.filter(_.nonEmpty))
        }
      }

      property("chunks.flatMap(chunk) identity") {
        forAll { (v: Vector[Vector[Int]]) =>
          val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
          assertEquals(s.chunks.flatMap(Stream.chunk).toVector, v.flatten)
        }
      }
    }

    test("eval") {
      Stream.eval(SyncIO(23)).compile.lastOrError.assertEquals(23)
    }

    test("evals") {
      Stream.evals(SyncIO(List(1, 2, 3))).compile.toList.assertEquals(List(1, 2, 3)) >>
        Stream.evals(SyncIO(Chain(4, 5, 6))).compile.toList.assertEquals(List(4, 5, 6)) >>
        Stream.evals(SyncIO(Option(42))).compile.lastOrError.assertEquals(42)
    }

    property("flatMap") {
      forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
        assertEquals(s.flatMap(inner => inner).toList, s.toList.flatMap(inner => inner.toList))
      }
    }

    group("handleErrorWith") {
      property("1") {
        forAll { (s: Stream[Pure, Int]) =>
          val s2 = s.covary[Fallible] ++ Stream.raiseError[Fallible](new Err)
          assertEquals(s2.handleErrorWith(_ => Stream.empty).toList, Right(s.toList))
        }
      }

      test("2") {
        val result =
          Stream.raiseError[Fallible](new Err).handleErrorWith(_ => Stream(1)).compile.lastOrError
        assertEquals(result, Right(1))
      }

      test("3") {
        val result = Stream(1)
          .append(Stream.raiseError[Fallible](new Err))
          .handleErrorWith(_ => Stream(1))
          .toList
        assertEquals(result, Right(List(1, 1)))
      }

      test("4 - error in eval") {
        Stream
          .eval(SyncIO(throw new Err))
          .map(Right(_): Either[Throwable, Int])
          .handleErrorWith(t => Stream.emit(Left(t)).covary[SyncIO])
          .take(1)
          .compile
          .toVector
          .map(it => assert(it.head.swap.toOption.get.isInstanceOf[Err]))
      }

      test("5") {
        Stream
          .raiseError[SyncIO](new Err)
          .handleErrorWith(e => Stream(e))
          .flatMap(Stream.emit)
          .compile
          .toVector
          .map { v =>
            assertEquals(v.size, 1)
            assert(v.head.isInstanceOf[Err])
          }
      }

      test("6") {
        Stream
          .raiseError[IO](new Err)
          .handleErrorWith(Stream.emit)
          .map(identity)
          .compile
          .toVector
          .map { v =>
            assertEquals(v.size, 1)
            assert(v.head.isInstanceOf[Err])
          }
      }

      test("7 - parJoin") {
        Stream(Stream.emit(1).covary[IO], Stream.raiseError[IO](new Err), Stream.emit(2).covary[IO])
          .covary[IO]
          .parJoin(4)
          .attempt
          .compile
          .toVector
          .map(it =>
            assert(
              it.collect { case Left(t) => t }
                .exists(_.isInstanceOf[Err])
            )
          )
      }

      test("8") {
        Counter[IO].flatMap { counter =>
          Pull
            .pure(42)
            .covary[IO]
            .handleErrorWith(_ => Pull.eval(counter.increment))
            .flatMap(_ => Pull.raiseError[IO](new Err))
            .stream
            .compile
            .drain
            .intercept[Err] >> counter.get.assertEquals(0L)
        }
      }

      test("9") {
        Counter[IO].flatMap { counter =>
          Pull
            .eval(IO(42))
            .handleErrorWith(_ => Pull.eval(counter.increment))
            .flatMap(_ => Pull.raiseError[IO](new Err))
            .stream
            .compile
            .drain
            .intercept[Err] >> counter.get.assertEquals(0L)
        }
      }

      test("10") {
        Counter[IO].flatMap { counter =>
          Pull
            .eval(IO(42))
            .flatMap { x =>
              Pull
                .pure(x)
                .handleErrorWith(_ => Pull.eval(counter.increment))
                .flatMap(_ => Pull.raiseError[IO](new Err))
            }
            .stream
            .compile
            .drain
            .intercept[Err] >> counter.get.assertEquals(0L)
        }
      }

      test("11") {

        Counter[IO].flatMap { counter =>
          Pull
            .eval(IO(???))
            .handleErrorWith(_ => Pull.eval(counter.increment))
            .flatMap(_ => Pull.done)
            .stream
            .compile
            .drain >> counter.get.assertEquals(1L)
        }
      }

      test("12") {
        Counter[IO].flatMap { counter =>
          Stream
            .bracket(IO.unit)(_ => counter.increment)
            .flatMap(_ => Stream.eval(IO(???)))
            .compile
            .drain
            .intercept[NotImplementedError] >> counter.get.assertEquals(1L)
        }
      }

      test("13") {
        Counter[IO].flatMap { counter =>
          Stream
            .range(0, 10)
            .covary[IO]
            .append(Stream.raiseError[IO](new Err))
            .handleErrorWith(_ => Stream.eval(counter.increment))
            .compile
            .drain >> counter.get.assertEquals(1L)
        }
      }

      test("14") {
        Stream
          .range(0, 3)
          .covary[SyncIO]
          .append(Stream.raiseError[SyncIO](new Err))
          .unchunk
          .pull
          .echo
          .stream
          .compile
          .drain
          .intercept[Err]
      }

      test("15") {
        Counter[IO].flatMap { counter =>
          {
            Stream
              .range(0, 3)
              .covary[IO] ++ Stream.raiseError[IO](new Err)
          }.unchunk.pull.echo
            .handleErrorWith(_ => Pull.eval(counter.increment))
            .stream
            .compile
            .drain >> counter.get.assertEquals(1L)
        }
      }

      test("16 - parJoin CompositeFailure".flaky) {
        Stream(
          Stream.emit(1).covary[IO],
          Stream.raiseError[IO](new Err),
          Stream.raiseError[IO](new Err),
          Stream.raiseError[IO](new Err),
          Stream.emit(2).covary[IO]
        ).covary[IO]
          .parJoin(10)
          .compile
          .toVector
          .attempt
          .map {
            case Left(err: CompositeFailure) =>
              assert(err.all.toList.count(_.isInstanceOf[Err]) == 3)
            case Left(err)    => fail("Expected Left[CompositeFailure]", err)
            case Right(value) => fail(s"Expected Left[CompositeFailure] got Right($value)")
          }
      }
    }
  }

  group("cancelation of compiled streams") {
    def startAndCancelSoonAfter[A](fa: IO[A]): IO[Unit] =
      fa.background.use(_ => IO.sleep(1.second))

    def testCancelation[A](s: Stream[IO, A]): IO[Unit] =
      startAndCancelSoonAfter(s.compile.drain)

    def constantStream: Stream[IO, Int] =
      Stream.constant(1).evalTapChunk(_ => IO.cede)

    test("constant")(testCancelation(constantStream))

    test("bracketed stream") {
      testCancelation(
        Stream.bracket(IO.unit)(_ => IO.unit).flatMap(_ => constantStream)
      )
    }

    test("concurrently") {
      testCancelation {
        constantStream.concurrently(constantStream)
      }
    }

    test("merge") {
      testCancelation {
        constantStream.merge(constantStream)
      }
    }

    test("parJoin") {
      testCancelation {
        Stream(constantStream, constantStream).parJoin(2)
      }
    }

    test("#1236") {
      testCancelation {
        Stream
          .eval(Queue.bounded[IO, Int](1))
          .flatMap { q =>
            Stream(
              Stream
                .unfold(0)(i => (i + 1, i + 1).some)
                .flatMap(i => Stream.sleep_[IO](50.milliseconds) ++ Stream.emit(i))
                .foreach(q.offer),
              Stream.repeatEval(q.take).drain
            ).parJoin(2)
          }
      }
    }

    test("#2072 - stream canceled while resource acquisition is running") {
      for {
        ref <- Ref.of[IO, Boolean](false)
        _ <- testCancelation {
          // This will be canceled after a second, while the acquire is still running
          Stream.bracket(IO.sleep(1100.millis))(_ => ref.set(true)) >> Stream.bracket(IO.unit)(_ =>
            IO.unit
          )
        }
        // Stream cancelation does not back pressure on canceled acquisitions so give time for the acquire to complete here
        _ <- IO.sleep(200.milliseconds)
        released <- ref.get
      } yield assert(released)
    }
  }

  group("map") {
    property("map.toList == toList.map") {
      forAll { (s: Stream[Pure, Int], f: Int => Int) =>
        assertEquals(s.map(f).toList, s.toList.map(f))
      }
    }

    test("regression #1335 - stack safety of map") {
      case class Tree[A](label: A, subForest: Stream[Pure, Tree[A]]) {
        def flatten: Stream[Pure, A] =
          Stream(this.label) ++ this.subForest.flatMap(_.flatten)
      }

      def unfoldTree(seed: Int): Tree[Int] =
        Tree(seed, Stream(seed + 1).map(unfoldTree))

      assertEquals(unfoldTree(1).flatten.take(10).toList, List.tabulate(10)(_ + 1))
    }

    test("regression #2353 - stack safety of map") {
      @tailrec
      def loop(str: Stream[Pure, Int], i: Int): Stream[Pure, Int] =
        if (i == 0) str else loop(str.map((x: Int) => x + 1), i - 1)

      loop(Stream.emit(1), 10000).compile.toList //
    }
  }

  property("mapChunks") {
    forAll { (s: Stream[Pure, Int]) =>
      assertEquals(s.mapChunks(identity).chunks.toList, s.chunks.toList)
    }
  }

  group("raiseError") {
    test("compiled stream fails with an error raised in stream") {
      Stream.raiseError[SyncIO](new Err).compile.drain.intercept[Err]
    }

    test("compiled stream fails with an error if error raised after an append") {
      Stream
        .emit(1)
        .append(Stream.raiseError[IO](new Err))
        .covary[IO]
        .compile
        .drain
        .intercept[Err]
    }

    test("compiled stream does not fail if stream is termianted before raiseError") {
      Stream
        .emit(1)
        .append(Stream.raiseError[IO](new Err))
        .take(1)
        .covary[IO]
        .compile
        .drain
    }
  }

  property("repeat") {
    forAll(
      Gen.chooseNum(1, 200),
      Gen.chooseNum(1, 200).flatMap(i => Gen.listOfN(i, arbitrary[Int]))
    ) { (n: Int, testValues: List[Int]) =>
      assertEquals(
        Stream.emits(testValues).repeat.take(n.toLong).toList,
        List.fill(n / testValues.size + 1)(testValues).flatten.take(n)
      )
    }
  }

  property("repeatN") {
    forAll(
      Gen.chooseNum(1, 200),
      Gen.chooseNum(1, 200).flatMap(i => Gen.listOfN(i, arbitrary[Int]))
    ) { (n: Int, testValues: List[Int]) =>
      assertEquals(
        Stream.emits(testValues).repeatN(n.toLong).toList,
        List.fill(n)(testValues).flatten
      )
    }
  }

  test("resource-append") {
    val res1: Resource[IO, String] = Resource.make(IO.pure("start"))(_ => IO.unit)
    val str: Stream[IO, String] = Stream.resource(res1) ++ Stream.emit("done")
    str.compile.toList.map(it => assertEquals(it, List("start", "done")))
  }

  test("resource") {
    Ref[IO]
      .of(List.empty[String])
      .flatMap { st =>
        def record(s: String): IO[Unit] = st.update(_ :+ s)
        def mkRes(s: String): Resource[IO, Unit] =
          Resource.make(record(s"acquire $s"))(_ => record(s"release $s"))

        // We aim to trigger all the possible cases, and make sure all of them
        // introduce scopes.

        // Allocate
        val res1 = mkRes("1")
        // Bind
        val res2 = mkRes("21") *> mkRes("22")
        // Suspend
        val res3 = Resource.suspend(
          record("suspend").as(mkRes("3"))
        )

        val s = List(res1, res2, res3)
          .foldMap(Stream.resource(_))
          .evalTap(_ => record("use"))
          .append(Stream.exec(record("done")))

        val expected = List(
          "acquire 1",
          "use",
          "release 1",
          "acquire 21",
          "acquire 22",
          "use",
          "release 22",
          "release 21",
          "suspend",
          "acquire 3",
          "use",
          "release 3",
          "done"
        )

        s.compile.drain >> st.get.assertEquals(expected)
      }
  }

  test("resourceWeak") {
    Ref[IO]
      .of(List.empty[String])
      .flatMap { st =>
        def record(s: String): IO[Unit] = st.update(_ :+ s)
        def mkRes(s: String): Resource[IO, Unit] =
          Resource.make(record(s"acquire $s"))(_ => record(s"release $s"))

        // We aim to trigger all the possible cases, and make sure none of them
        // introduce scopes.

        // Allocate
        val res1 = mkRes("1")
        // Bind
        val res2 = mkRes("21") *> mkRes("22")
        // Suspend
        val res3 = Resource.suspend(
          record("suspend").as(mkRes("3"))
        )

        val s = List(res1, res2, res3)
          .foldMap(Stream.resourceWeak(_))
          .evalTap(_ => record("use"))
          .append(Stream.exec(record("done")))

        val expected = List(
          "acquire 1",
          "use",
          "acquire 21",
          "acquire 22",
          "use",
          "suspend",
          "acquire 3",
          "use",
          "done",
          "release 3",
          "release 22",
          "release 21",
          "release 1"
        )

        s.compile.drain >> st.get.assertEquals(expected)
      }
  }

  group("resource safety") {
    test("1") {
      forAllF { (s1: Stream[Pure, Int]) =>
        Counter[IO].flatMap { counter =>
          val x = Stream.bracket(counter.increment)(_ => counter.decrement) >> s1
          val y = Stream.raiseError[IO](new Err)
          x.merge(y)
            .attempt
            .append(y.merge(x).attempt)
            .compile
            .drain >> counter.get.assertEquals(0L)
        }
      }
    }

    test("2a") {
      Counter[IO].flatMap { counter =>
        val s = Stream.raiseError[IO](new Err)
        val b = Stream.bracket(counter.increment)(_ => counter.decrement) >> s
        // subtle test, get different scenarios depending on interleaving:
        // `s` completes with failure before the resource is acquired by `b`
        // `b` has just caught inner error when outer `s` fails
        // `b` fully completes before outer `s` fails

        {
          b.merge(s).compile.drain.attempt >> counter.get.assertEquals(0L)
        }.replicateA(25)
      }
    }

    test("2b") {
      forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        Counter[IO].flatMap { counter =>
          val b1 = Stream.bracket(counter.increment)(_ => counter.decrement) >> s1
          val b2 = Stream.bracket(counter.increment)(_ => counter.decrement) >> s2
          spuriousFail(b1)
            .merge(b2)
            .attempt
            .append(b1.merge(spuriousFail(b2)).attempt)
            .append(spuriousFail(b1).merge(spuriousFail(b2)).attempt)
            .compile
            .drain >> counter.get.assertEquals(0L)
        }
      }
    }

    test("3".ignore) {
      // TODO: Sometimes fails with inner == 1 on final assertion
      forAllF { (s: Stream[Pure, Stream[Pure, Int]], n0: Int) =>
        val n = (n0 % 10).abs + 1
        Counter[IO].flatMap { outer =>
          Counter[IO].flatMap { inner =>
            val s2 = Stream.bracket(outer.increment)(_ => outer.decrement) >> s.map { _ =>
              spuriousFail(Stream.bracket(inner.increment)(_ => inner.decrement) >> s)
            }
            val one = s2.parJoin(n).take(10).attempt
            val two = s2.parJoin(n).attempt
            for {
              _ <- one.append(two).compile.drain
              _ <- outer.get.assertEquals(0L)
              _ <- IO.sleep(50.millis) // Allow time for inner stream to terminate
              _ <- inner.get.assertEquals(0L)
            } yield ()
          }
        }
      }
    }

    test("4") {
      forAllF { (s: Stream[Pure, Int]) =>
        Counter[IO].flatMap { counter =>
          val s2 = Stream.bracket(counter.increment)(_ => counter.decrement) >> spuriousFail(
            s.covary[IO]
          )
          val one = s2.prefetch.attempt
          val two = s2.prefetch.prefetch.attempt
          val three = s2.prefetch.prefetch.prefetch.attempt
          one
            .append(two)
            .append(three)
            .compile
            .drain >> counter.get.assertEquals(0L)
        }
      }
    }

    test("5") {
      forAllF { (s: Stream[Pure, Stream[Pure, Int]]) =>
        SignallingRef[IO, Boolean](false).flatMap { signal =>
          Counter[IO].flatMap { counter =>
            val sleepAndSet = IO.sleep(20.millis) >> signal.set(true)
            Stream
              .exec(sleepAndSet.start.void)
              .append(s.map { _ =>
                Stream
                  .bracket(counter.increment)(_ => counter.decrement)
                  .evalMap(_ => IO.never)
                  .interruptWhen(signal.discrete)
              })
              .parJoinUnbounded
              .compile
              .drain >> counter.get.assertEquals(0L)
          }
        }
      }
    }

    test("6") {
      // simpler version of (5) above which previously failed reliably, checks the case where a
      // stream is interrupted while in the middle of a resource acquire that is immediately followed
      // by a step that never completes!
      SignallingRef[IO, Boolean](false).flatMap { signal =>
        Counter[IO].flatMap { counter =>
          val sleepAndSet = IO.sleep(20.millis) >> signal.set(true)
          Stream
            .exec(sleepAndSet.start.void)
            .append(Stream(Stream(1)).map { inner =>
              Stream
                .bracket(counter.increment >> IO.sleep(2.seconds))(_ => counter.decrement)
                .flatMap(_ => inner)
                .evalMap(_ => IO.never)
                .interruptWhen(signal.discrete)
            })
            .parJoinUnbounded
            .compile
            .drain >> counter.get.assertEquals(0L)
        }
      }
    }

    group("scope") {
      test("1") {
        val c = new java.util.concurrent.atomic.AtomicLong(0)
        val s1 = Stream.emit("a").covary[IO]
        val s2 = Stream
          .bracket(IO(assertEquals(c.incrementAndGet, 1L))) { _ =>
            IO(c.decrementAndGet).void
          }
          .flatMap(_ => Stream.emit("b"))
        (s1.scope ++ s2)
          .take(2)
          .scope
          .repeat
          .take(4)
          .merge(Stream.exec(IO.unit))
          .compile
          .drain >> IO(c.get).assertEquals(0L)
      }

      test("2") {
        Stream
          .eval(Counter[IO])
          .flatMap { counter =>
            Stream(1).flatMap { _ =>
              Stream
                .bracketWeak(counter.increment)(_ => counter.decrement)
                .flatMap(_ => Stream.eval(counter.get)) ++ Stream.eval(counter.get)
            }.scope ++ Stream.eval(counter.get)
          }
          .compile
          .toList
          .assertEquals(List(1L, 1, 0))
      }
    }

    group("take") {
      property("identity") {
        forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
          val n1 = (n0 % 20).abs + 1
          val n = if (negate) -n1 else n1
          assertEquals(s.take(n.toLong).toList, s.toList.take(n))
        }
      }
      test("chunks") {
        val s = Stream(1, 2) ++ Stream(3, 4)
        assertEquals(s.take(3).chunks.map(_.toList).toList, List(List(1, 2), List(3)))
      }
    }

    property("takeRight") {
      forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
        val n1 = (n0 % 20).abs + 1
        val n = if (negate) -n1 else n1
        assertEquals(s.takeRight(n).toList, s.toList.takeRight(n))
      }
    }

    property("takeWhile") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val set = s.toList.take(n).toSet
        assertEquals(s.takeWhile(set).toList, s.toList.takeWhile(set))
      }
    }

    property("takeThrough") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val f = (i: Int) => i % n == 0
        val vec = s.toVector
        val result = vec.takeWhile(f) ++ vec.dropWhile(f).headOption
        assertEquals(s.takeThrough(f).toVector, result, vec.toString)
      }
    }
  }

  group("compile") {
    group("resource") {
      test("concurrently") {
        val prog: Resource[IO, IO[Unit]] =
          Stream
            .eval(Deferred[IO, Unit].product(Deferred[IO, Unit]))
            .flatMap { case (startCondition, waitForStream) =>
              val worker = Stream.eval(startCondition.get) ++ Stream.eval(
                waitForStream.complete(())
              )
              val result = startCondition.complete(()) >> waitForStream.get

              Stream.emit(result).concurrently(worker)
            }
            .compile
            .resource
            .lastOrError
        prog.use(x => x)
      }

      test("onFinalise") {
        val expected = List(
          "stream - start",
          "stream - done",
          "io - done",
          "io - start",
          "resource - start",
          "resource - done"
        )

        Ref[IO]
          .of(List.empty[String])
          .flatMap { st =>
            def record(s: String): IO[Unit] = st.update(_ :+ s)

            def stream =
              Stream
                .emit("stream - start")
                .onFinalize(record("stream - done"))
                .evalMap(x => record(x))
                .compile
                .lastOrError

            def io =
              Stream
                .emit("io - start")
                .onFinalize(record("io - done"))
                .compile
                .lastOrError
                .flatMap(x => record(x))

            def resource =
              Stream
                .emit("resource - start")
                .onFinalize(record("resource - done"))
                .compile
                .resource
                .lastOrError
                .use(x => record(x))

            stream >> io >> resource >> st.get
          }
          .assertEquals(expected)
      }

      test("onFinalizeWeak") {
        Ref[IO]
          .of(List.empty[String])
          .flatMap { st =>
            def record(s: String): IO[Unit] = st.update(_ :+ s)
            Stream
              .emit("emit")
              .onFinalize(record("1")) // This gets closed
              .onFinalize(record("2")) // This gets extended
              .onFinalizeWeak(record("3")) // This joins extended
              .onFinalizeWeak(record("4")) // This joins extended
              .compile
              .resource
              .lastOrError
              .use(x => record(x)) >> st.get
          }
          .assertEquals(List("1", "emit", "2", "3", "4"))
      }

      group("last scope extended, not all scopes") {
        test("1") {
          Ref[IO]
            .of(List.empty[String])
            .flatMap { st =>
              def record(s: String): IO[Unit] = st.update(_ :+ s)
              Stream
                .emit("start")
                .onFinalize(record("first finalize"))
                .onFinalize(record("second finalize"))
                .compile
                .resource
                .lastOrError
                .use(x => record(x)) *> st.get
            }
            .assertEquals(List("first finalize", "start", "second finalize"))
        }
        test("2") {
          Ref[IO]
            .of(List.empty[String])
            .flatMap { st =>
              def record(s: String): IO[Unit] = st.update(_ :+ s)
              (Stream.bracket(IO("a"))(_ => record("first finalize")) ++
                Stream.bracket(IO("b"))(_ => record("second finalize")) ++
                Stream.bracket(IO("c"))(_ => record("third finalize"))).compile.resource.lastOrError
                .use(x => record(x)) *> st.get
            }
            .assertEquals(List("first finalize", "second finalize", "c", "third finalize"))
        }
      }

      test("allocated") {
        Ref[IO]
          .of(false)
          .flatMap { written =>
            val p: IO[(Unit, IO[Unit])] = Stream
              .emit(())
              .onFinalize(written.set(true))
              .compile
              .resource
              .lastOrError
              .allocated
            p >> written.get.assertEquals(false)
          }
      }

      group("interruption") {
        test("1") {
          Stream
            .resource {
              Stream.never[IO].compile.resource.drain
            }
            .interruptAfter(200.millis)
            .drain
            .append(Stream.emit(true))
            .compile
            .lastOrError
            .timeout(2.seconds)
            .assertEquals(true)
        }

        test("2") {
          val p = (Deferred[IO, Outcome[IO, Throwable, Unit]]).flatMap { stop =>
            val r = Stream
              .never[IO]
              .compile
              .resource
              .drain
              .use(_ => IO.unit)
              .guaranteeCase(stop.complete(_).void)

            r.background.use(_ => IO.sleep(200.millis)) >> stop.get
          }
          p.timeout(2.seconds).assertEquals(Outcome.Canceled(): Outcome[IO, Throwable, Unit])
        }
      }
    }
  }

  test("pure pipes cannot be used with effectful streams (#1838)") {
    val p: Pipe[Pure, Int, List[Int]] = in => Stream(in.toList)
    identity(p) // Avoid unused warning
    assert(compileErrors("Stream.eval(IO(1)).through(p)").nonEmpty)
  }
}
