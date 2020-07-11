package fs2

import scala.concurrent.duration._

import cats.data.Chain
import cats.effect.{ExitCase, IO, Resource, Sync, SyncIO}
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import org.scalacheck.Prop.forAll

import fs2.concurrent.Queue

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
}
