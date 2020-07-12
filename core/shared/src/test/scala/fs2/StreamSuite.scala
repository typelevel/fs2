package fs2

import scala.concurrent.duration._

import cats.data.Chain
import cats.effect.{ExitCase, IO, Resource, SyncIO}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import org.scalacheck.Gen
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Prop.forAll

import fs2.concurrent.{Queue, SignallingRef}

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
        assert(sizeV.combineAll == s.toVector.size)
      }
    }

    property("chunkMin") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val chunkedV = s.chunkMin(n, true).toVector
        val withIfSmallerV = s.chunkMin(n, false).toVector
        val unchunkedV = s.toVector
        val smallerSet = s.take(n - 1).toVector
        val smallerN = s.take(n - 1).chunkMin(n, false).toVector
        val smallerY = s.take(n - 1).chunkMin(n, true).toVector
        // All but last list have n values
        assert(chunkedV.dropRight(1).forall(_.size >= n))
        // Equivalent to last chunk with allowFewerTotal
        if (chunkedV.nonEmpty && chunkedV.last.size < n)
          assert(chunkedV.dropRight(1) == withIfSmallerV)
        // Flattened sequence with allowFewerTotal true is equal to vector without chunking
        assert(chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) == unchunkedV)
        // If smaller than Chunk Size and allowFewerTotal false is empty then
        // no elements should be emitted
        assert(smallerN == Vector.empty)
        // If smaller than Chunk Size and allowFewerTotal true is equal to the size
        // of the taken chunk initially
        assert(smallerY.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) == smallerSet)
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
          assert(chunkedV.lastOption.fold(true)(_.size <= n))
          // Flattened sequence is equal to vector without chunking
          assert(chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) == unchunkedV)
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
          assert(left == right)
        }
      }
    }

    group("chunks") {
      property("chunks.map identity") {
        forAll { (v: Vector[Vector[Int]]) =>
          val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
          assert(s.chunks.map(_.toVector).toVector == v.filter(_.nonEmpty))
        }
      }

      property("chunks.flatMap(chunk) identity") {
        forAll { (v: Vector[Vector[Int]]) =>
          val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
          assert(s.chunks.flatMap(Stream.chunk).toVector == v.flatten)
        }
      }
    }

    test("eval") {
      assertEquals(Stream.eval(SyncIO(23)).compile.toList.unsafeRunSync, List(23))
    }

    test("evals") {
      assertEquals(Stream.evals(SyncIO(List(1, 2, 3))).compile.toList.unsafeRunSync, List(1, 2, 3))
      assertEquals(Stream.evals(SyncIO(Chain(4, 5, 6))).compile.toList.unsafeRunSync, List(4, 5, 6))
      assertEquals(Stream.evals(SyncIO(Option(42))).compile.toList.unsafeRunSync, List(42))
    }

    property("flatMap") {
      forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
        assert(s.flatMap(inner => inner).toList == s.toList.flatMap(inner => inner.toList))
      }
    }

    group("handleErrorWith") {
      property("1") {
        forAll { (s: Stream[Pure, Int]) =>
          val s2 = s.covary[Fallible] ++ Stream.raiseError[Fallible](new Err)
          assert(s2.handleErrorWith(_ => Stream.empty).toList == Right(s.toList))
        }
      }

      test("2") {
        val result = Stream.raiseError[Fallible](new Err).handleErrorWith(_ => Stream(1)).toList
        assert(result == Right(List(1)))
      }

      test("3") {
        val result = Stream(1)
          .append(Stream.raiseError[Fallible](new Err))
          .handleErrorWith(_ => Stream(1))
          .toList
        assert(result == Right(List(1, 1)))
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
            assert(v.size == 1)
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
            assert(v.size == 1)
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
        SyncIO.suspend {
          var i = 0
          Pull
            .pure(1)
            .covary[SyncIO]
            .handleErrorWith { _ => i += 1; Pull.pure(2) }
            .flatMap(_ => Pull.output1(i) >> Pull.raiseError[SyncIO](new Err))
            .stream
            .compile
            .drain
            .assertThrows[Err]
            .map(_ => assert(i == 0))
        }
      }

      test("9") {
        SyncIO.suspend {
          var i = 0
          Pull
            .eval(SyncIO(1))
            .handleErrorWith { _ => i += 1; Pull.pure(2) }
            .flatMap(_ => Pull.output1(i) >> Pull.raiseError[SyncIO](new Err))
            .stream
            .compile
            .drain
            .assertThrows[Err]
            .map(_ => assert(i == 0))
        }
      }

      test("10") {
        SyncIO.suspend {
          var i = 0
          Pull
            .eval(SyncIO(1))
            .flatMap { x =>
              Pull
                .pure(x)
                .handleErrorWith { _ => i += 1; Pull.pure(2) }
                .flatMap(_ => Pull.output1(i) >> Pull.raiseError[SyncIO](new Err))
            }
            .stream
            .compile
            .drain
            .assertThrows[Err]
            .map(_ => assert(i == 0))
        }
      }

      test("11") {
        SyncIO.suspend {
          var i = 0
          Pull
            .eval(SyncIO(???))
            .handleErrorWith(_ => Pull.pure(i += 1))
            .flatMap(_ => Pull.output1(i))
            .stream
            .compile
            .drain
            .map(_ => assert(i == 1))
        }
      }

      test("12") {
        SyncIO.suspend {
          var i = 0
          Stream
            .bracket(SyncIO(1))(_ => SyncIO(i += 1))
            .flatMap(_ => Stream.eval(SyncIO(???)))
            .compile
            .drain
            .assertThrows[NotImplementedError]
            .map(_ => assert(i == 1))
        }
      }

      test("13") {
        SyncIO.suspend {
          var i = 0
          Stream
            .range(0, 10)
            .covary[SyncIO]
            .append(Stream.raiseError[SyncIO](new Err))
            .handleErrorWith { _ => i += 1; Stream.empty }
            .compile
            .drain
            .map(_ => assert(i == 1))
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
          .assertThrows[Err]
      }

      test("15") {
        SyncIO.suspend {
          var i = 0
          (Stream
            .range(0, 3)
            .covary[SyncIO] ++ Stream.raiseError[SyncIO](new Err)).unchunk.pull.echo
            .handleErrorWith { _ => i += 1; Pull.done }
            .stream
            .compile
            .drain
            .map(_ => assert(i == 1))
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
          .map({
            case Left(err: CompositeFailure) =>
              assert(err.all.toList.count(_.isInstanceOf[Err]) == 3)
            case Left(err)    => fail("Expected Left[CompositeFailure]", err)
            case Right(value) => fail(s"Expected Left[CompositeFailure] got Right($value)")
          })
      }
    }
  }

  group("cancelation of compiled streams") {
    def startAndCancelSoonAfter[A](fa: IO[A]): IO[Unit] =
      fa.start.flatMap(fiber => IO.sleep(1.second) >> fiber.cancel)

    def testCancelation[A](s: Stream[IO, A]): IO[Unit] =
      startAndCancelSoonAfter(s.compile.drain)

    def constantStream: Stream[IO, Int] =
      if (isJVM) Stream.constant(1) else Stream.constant(1).evalTap(_ => IO.sleep(1.milli))

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
                .flatMap(i => Stream.sleep_(50.milliseconds) ++ Stream.emit(i))
                .through(q.enqueue),
              q.dequeue.drain
            ).parJoin(2)
          }
      }
    }

    group("map") {
      property("map.toList == toList.map") {
        forAll { (s: Stream[Pure, Int], f: Int => Int) =>
          assert(s.map(f).toList == s.toList.map(f))
        }
      }

      test("regression #1335 - stack safety of map") {
        case class Tree[A](label: A, subForest: Stream[Pure, Tree[A]]) {
          def flatten: Stream[Pure, A] =
            Stream(this.label) ++ this.subForest.flatMap(_.flatten)
        }

        def unfoldTree(seed: Int): Tree[Int] =
          Tree(seed, Stream(seed + 1).map(unfoldTree))

        assert(unfoldTree(1).flatten.take(10).toList == List.tabulate(10)(_ + 1))
      }
    }

    property("mapChunks") {
      forAll { (s: Stream[Pure, Int]) =>
        assert(s.mapChunks(identity).chunks.toList == s.chunks.toList)
      }
    }

    group("raiseError") {
      test("compiled stream fails with an error raised in stream") {
        Stream.raiseError[SyncIO](new Err).compile.drain.assertThrows[Err]
      }

      test("compiled stream fails with an error if error raised after an append") {
        Stream
          .emit(1)
          .append(Stream.raiseError[IO](new Err))
          .covary[IO]
          .compile
          .drain
          .assertThrows[Err]
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
        assert(
          Stream.emits(testValues).repeat.take(n).toList == List
            .fill(n / testValues.size + 1)(testValues)
            .flatten
            .take(n)
        )
      }
    }

    property("repeatN") {
      forAll(
        Gen.chooseNum(1, 200),
        Gen.chooseNum(1, 200).flatMap(i => Gen.listOfN(i, arbitrary[Int]))
      ) { (n: Int, testValues: List[Int]) =>
        assert(Stream.emits(testValues).repeatN(n).toList == List.fill(n)(testValues).flatten)
      }
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

          List(res1, res2, res3)
            .foldMap(Stream.resource)
            .evalTap(_ => record("use"))
            .append(Stream.eval_(record("done")))
            .compile
            .drain *> st.get
        }
        .map(it =>
          assert(
            it == List(
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
          )
        )
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

          List(res1, res2, res3)
            .foldMap(Stream.resourceWeak)
            .evalTap(_ => record("use"))
            .append(Stream.eval_(record("done")))
            .compile
            .drain *> st.get
        }
        .map(it =>
          assert(
            it == List(
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
          )
        )
    }
  }

  group("resource safety") {
    test("1") {
      forAllAsync { (s1: Stream[Pure, Int]) =>
        Counter[IO].flatMap { counter =>
          val x = Stream.bracket(counter.increment)(_ => counter.decrement) >> s1
          val y = Stream.raiseError[IO](new Err)
          x.merge(y)
            .attempt
            .append(y.merge(x).attempt)
            .compile
            .drain
            .flatMap(_ => counter.get)
            .map(it => assert(it == 0L))
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
        b.merge(s)
          .compile
          .drain
          .attempt
          .flatMap(_ => counter.get)
          .map(it => assert(it == 0L))
          .replicateA(25)
      }
    }

    test("2b") {
      forAllAsync { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        Counter[IO].flatMap { counter =>
          val b1 = Stream.bracket(counter.increment)(_ => counter.decrement) >> s1
          val b2 = Stream.bracket(counter.increment)(_ => counter.decrement) >> s2
          spuriousFail(b1)
            .merge(b2)
            .attempt
            .append(b1.merge(spuriousFail(b2)).attempt)
            .append(spuriousFail(b1).merge(spuriousFail(b2)).attempt)
            .compile
            .drain
            .flatMap(_ => counter.get)
            .map(it => assert(it == 0L))
        }
      }
    }

    test("3".ignore) {
      // TODO: Sometimes fails with inner == 1 on final assertion
      forAllAsync { (s: Stream[Pure, Stream[Pure, Int]], n0: Int) =>
        val n = (n0 % 10).abs + 1
        Counter[IO].flatMap { outer =>
          Counter[IO].flatMap { inner =>
            val s2 = Stream.bracket(outer.increment)(_ => outer.decrement) >> s.map { _ =>
              spuriousFail(Stream.bracket(inner.increment)(_ => inner.decrement) >> s)
            }
            val one = s2.parJoin(n).take(10).attempt
            val two = s2.parJoin(n).attempt
            one
              .append(two)
              .compile
              .drain
              .flatMap(_ => outer.get)
              .map(it => assert(it == 0L))
              .flatMap(_ => IO.sleep(50.millis)) // Allow time for inner stream to terminate
              .flatMap(_ => inner.get)
              .map(it => assert(it == 0L))
          }
        }
      }
    }

    test("4") {
      forAllAsync { (s: Stream[Pure, Int]) =>
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
            .drain
            .flatMap(_ => counter.get)
            .map(it => assert(it == 0L))
        }
      }
    }

    test("5") {
      forAllAsync { (s: Stream[Pure, Stream[Pure, Int]]) =>
        SignallingRef[IO, Boolean](false).flatMap { signal =>
          Counter[IO].flatMap { counter =>
            val sleepAndSet = IO.sleep(20.millis) >> signal.set(true)
            Stream
              .eval_(sleepAndSet.start)
              .append(s.map { _ =>
                Stream
                  .bracket(counter.increment)(_ => counter.decrement)
                  .evalMap(_ => IO.never)
                  .interruptWhen(signal.discrete)
              })
              .parJoinUnbounded
              .compile
              .drain
              .flatMap(_ => counter.get)
              .map(it => assert(it == 0L))
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
            .eval_(sleepAndSet.start)
            .append(Stream(Stream(1)).map { inner =>
              Stream
                .bracket(counter.increment >> IO.sleep(2.seconds))(_ => counter.decrement)
                .flatMap(_ => inner)
                .evalMap(_ => IO.never)
                .interruptWhen(signal.discrete)
            })
            .parJoinUnbounded
            .compile
            .drain
            .flatMap(_ => counter.get)
            .map(it => assert(it == 0L))
        }
      }
    }

    group("scope") {
      test("1") {
        val c = new java.util.concurrent.atomic.AtomicLong(0)
        val s1 = Stream.emit("a").covary[IO]
        val s2 = Stream
          .bracket(IO { assert(c.incrementAndGet() == 1L); () }) { _ =>
            IO { c.decrementAndGet(); () }
          }
          .flatMap(_ => Stream.emit("b"))
        (s1.scope ++ s2)
          .take(2)
          .scope
          .repeat
          .take(4)
          .merge(Stream.eval_(IO.unit))
          .compile
          .drain
          .map(_ => assert(c.get == 0L))
      }

      test("2") {
        Stream
          .eval(Ref.of[IO, Int](0))
          .flatMap { ref =>
            Stream(1).flatMap { _ =>
              Stream
                .bracketWeak(ref.update(_ + 1))(_ => ref.update(_ - 1))
                .flatMap(_ => Stream.eval(ref.get)) ++ Stream.eval(ref.get)
            }.scope ++ Stream.eval(ref.get)
          }
          .compile
          .toList
          .map(it => assert(it == List(1, 1, 0)))
      }
    }

    group("take") {
      property("identity") {
        forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
          val n1 = (n0 % 20).abs + 1
          val n = if (negate) -n1 else n1
          assert(s.take(n).toList == s.toList.take(n))
        }
      }
      test("chunks") {
        val s = Stream(1, 2) ++ Stream(3, 4)
        assert(s.take(3).chunks.map(_.toList).toList == List(List(1, 2), List(3)))
      }
    }

    property("takeRight") {
      forAll { (s: Stream[Pure, Int], negate: Boolean, n0: Int) =>
        val n1 = (n0 % 20).abs + 1
        val n = if (negate) -n1 else n1
        assert(s.takeRight(n).toList == s.toList.takeRight(n))
      }
    }

    property("takeWhile") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val set = s.toList.take(n).toSet
        assert(s.takeWhile(set).toList == s.toList.takeWhile(set))
      }
    }

    property("takeThrough") {
      forAll { (s: Stream[Pure, Int], n0: Int) =>
        val n = (n0 % 20).abs + 1
        val f = (i: Int) => i % n == 0
        val vec = s.toVector
        val result = vec.takeWhile(f) ++ vec.dropWhile(f).headOption
        assert(s.takeThrough(f).toVector == result, vec.toString)
      }
    }
  }

  group("compile") {
    group("resource") {
      test("concurrently") {
        val prog: Resource[IO, IO[Unit]] =
          Stream
            .eval(Deferred[IO, Unit].product(Deferred[IO, Unit]))
            .flatMap {
              case (startCondition, waitForStream) =>
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
          .map(it => assert(it == expected))
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
          .map(it => assert(it == List("1", "emit", "2", "3", "4")))
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
            .map(it => assert(it == List("first finalize", "start", "second finalize")))
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
            .map(it =>
              assert(it == List("first finalize", "second finalize", "c", "third finalize"))
            )
        }
      }

      test("allocated") {
        Ref[IO]
          .of(false)
          .flatMap { written =>
            Stream
              .emit(())
              .onFinalize(written.set(true))
              .compile
              .resource
              .lastOrError
              .allocated >> written.get
          }
          .map(it => assert(it == false))
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
            .map(it => assert(it == true))
        }

        test("2") {
          val p = (Deferred[IO, ExitCase[Throwable]]).flatMap { stop =>
            val r = Stream
              .never[IO]
              .compile
              .resource
              .drain
              .use(_ => IO.unit)
              .guaranteeCase(stop.complete)

            r.start.flatMap(fiber => IO.sleep(200.millis) >> fiber.cancel >> stop.get)
          }
          p.timeout(2.seconds)
            .map(it => assert(it == ExitCase.Canceled))
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
