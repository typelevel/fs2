package fs2

import cats.~>
import cats.data.Chain
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._
import scala.concurrent.duration._
import org.scalactic.anyvals._
import org.scalatest.{Assertion, Succeeded}
import fs2.concurrent.{Queue, SignallingRef}

class StreamSpec extends Fs2Spec {
  "Stream" - {
    "++" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      assert((s1 ++ s2).toList == (s1.toList ++ s2.toList))
    }

    ">>" in forAll { (s: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      assert((s >> s2).toList == s.flatMap(_ => s2).toList)
    }

    "apply" in { assert(Stream(1, 2, 3).toList == List(1, 2, 3)) }

    "awakeEvery" - {
      "basic" in {
        Stream
          .awakeEvery[IO](500.millis)
          .map(_.toMillis)
          .take(5)
          .compile
          .toVector
          .asserting { r =>
            r.sliding(2)
              .map { s =>
                (s.head, s.tail.head)
              }
              .map { case (prev, next) => next - prev }
              .foreach { delta =>
                assert(delta +- 150 === 500L)
              }
            Succeeded
          }
      }

      "liveness" in {
        val s = Stream
          .awakeEvery[IO](1.milli)
          .evalMap { _ =>
            IO.async[Unit](cb => executionContext.execute(() => cb(Right(()))))
          }
          .take(200)
        Stream(s, s, s, s, s).parJoin(5).compile.drain.assertNoException
      }
    }

    "bracket" - {
      sealed trait BracketEvent
      final case object Acquired extends BracketEvent
      final case object Released extends BracketEvent

      def recordBracketEvents[F[_]](events: Ref[F, Vector[BracketEvent]]): Stream[F, Unit] =
        Stream.bracket(events.update(evts => evts :+ Acquired))(_ =>
          events.update(evts => evts :+ Released)
        )

      "single bracket" - {
        def singleBracketTest[F[_]: Sync, A](use: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events)
              .evalMap(_ =>
                events.get.asserting { events =>
                  assert(events == Vector(Acquired))
                }
              )
              .flatMap(_ => use)
              .compile
              .drain
              .handleErrorWith { case _: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { it =>
              assert(it == Vector(Acquired, Released))
            }
          } yield ()

        "normal termination" in { singleBracketTest[SyncIO, Unit](Stream.empty) }
        "failure" in { singleBracketTest[SyncIO, Unit](Stream.raiseError[SyncIO](new Err)) }
        "throw from append" in {
          singleBracketTest(Stream(1, 2, 3) ++ ((throw new Err): Stream[SyncIO, Int]))
        }
      }

      "bracket ++ bracket" - {
        def appendBracketTest[F[_]: Sync, A](use1: Stream[F, A], use2: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events)
              .flatMap(_ => use1)
              .append(recordBracketEvents(events).flatMap(_ => use2))
              .compile
              .drain
              .handleErrorWith { case _: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { it =>
              assert(it == Vector(Acquired, Released, Acquired, Released))
            }
          } yield ()

        "normal termination" in { appendBracketTest[SyncIO, Unit](Stream.empty, Stream.empty) }
        "failure" in {
          appendBracketTest[SyncIO, Unit](Stream.empty, Stream.raiseError[SyncIO](new Err))
        }
      }

      "nested" in forAll { (s0: List[Int], finalizerFail: Boolean) =>
        // construct a deeply nested bracket stream in which the innermost stream fails
        // and check that as we unwind the stack, all resources get released
        // Also test for case where finalizer itself throws an error
        Counter[IO].flatMap { counter =>
          val innermost: Stream[IO, Int] =
            if (finalizerFail)
              Stream
                .bracket(counter.increment)(_ => counter.decrement >> IO.raiseError(new Err))
                .drain
            else Stream.raiseError[IO](new Err)
          val nested = s0.foldRight(innermost)((i, inner) =>
            Stream
              .bracket(counter.increment)(_ => counter.decrement)
              .flatMap(_ => Stream(i) ++ inner)
          )
          nested.compile.drain
            .assertThrows[Err]
            .flatMap(_ => counter.get)
            .asserting(it => assert(it == 0L))
        }
      }

      "early termination" in forAll { (s: Stream[Pure, Int], i0: Long, j0: Long, k0: Long) =>
        val i = i0 % 10
        val j = j0 % 10
        val k = k0 % 10
        Counter[IO].flatMap { counter =>
          val bracketed = Stream.bracket(counter.increment)(_ => counter.decrement) >> s
          val earlyTermination = bracketed.take(i)
          val twoLevels = bracketed.take(i).take(j)
          val twoLevels2 = bracketed.take(i).take(i)
          val threeLevels = bracketed.take(i).take(j).take(k)
          val fiveLevels = bracketed.take(i).take(j).take(k).take(j).take(i)
          val all = earlyTermination ++ twoLevels ++ twoLevels2 ++ threeLevels ++ fiveLevels
          all.compile.drain.flatMap(_ => counter.get).asserting(it => assert(it == 0L))
        }
      }

      "finalizer should not be called until necessary" in {
        IO.suspend {
          val buffer = collection.mutable.ListBuffer[String]()
          Stream
            .bracket(IO(buffer += "Acquired")) { _ =>
              buffer += "ReleaseInvoked"
              IO(buffer += "Released").void
            }
            .flatMap { _ =>
              buffer += "Used"
              Stream.emit(())
            }
            .flatMap { s =>
              buffer += "FlatMapped"
              Stream(s)
            }
            .compile
            .toList
            .asserting { _ =>
              assert(
                buffer.toList == List(
                  "Acquired",
                  "Used",
                  "FlatMapped",
                  "ReleaseInvoked",
                  "Released"
                )
              )
            }
        }
      }

      val bracketsInSequence = if (isJVM) 1000000 else 10000
      s"$bracketsInSequence brackets in sequence" in {
        Counter[IO].flatMap { counter =>
          Stream
            .range(0, bracketsInSequence)
            .covary[IO]
            .flatMap { _ =>
              Stream
                .bracket(counter.increment)(_ => counter.decrement)
                .flatMap(_ => Stream(1))
            }
            .compile
            .drain
            .flatMap(_ => counter.get)
            .asserting(it => assert(it == 0))
        }
      }

      "evaluating a bracketed stream multiple times is safe" in {
        val s = Stream
          .bracket(IO.unit)(_ => IO.unit)
          .compile
          .drain
        s.flatMap(_ => s).assertNoException
      }

      "finalizers are run in LIFO order" - {
        "explicit release" in {
          IO.suspend {
            var o: Vector[Int] = Vector.empty
            (0 until 10)
              .foldLeft(Stream.eval(IO(0))) { (acc, i) =>
                Stream.bracket(IO(i))(i => IO { o = o :+ i }).flatMap(_ => acc)
              }
              .compile
              .drain
              .asserting(_ => assert(o == Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
          }
        }

        "scope closure" in {
          IO.suspend {
            var o: Vector[Int] = Vector.empty
            (0 until 10)
              .foldLeft(Stream.emit(1).map(_ => throw new Err): Stream[IO, Int]) { (acc, i) =>
                Stream.emit(i) ++ Stream.bracket(IO(i))(i => IO { o = o :+ i }).flatMap(_ => acc)
              }
              .attempt
              .compile
              .drain
              .asserting(_ => assert(o == Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
          }
        }
      }

      "propagate error from closing the root scope" - {
        val s1 = Stream.bracket(IO(1))(_ => IO.unit)
        val s2 = Stream.bracket(IO("a"))(_ => IO.raiseError(new Err))

        "fail left" in s1.zip(s2).compile.drain.assertThrows[Err]
        "fail right" in s2.zip(s1).compile.drain.assertThrows[Err]
      }

      "handleErrorWith closes scopes" in {
        Ref
          .of[SyncIO, Vector[BracketEvent]](Vector.empty)
          .flatMap { events =>
            recordBracketEvents[SyncIO](events)
              .flatMap(_ => Stream.raiseError[SyncIO](new Err))
              .handleErrorWith(_ => Stream.empty)
              .append(recordBracketEvents[SyncIO](events))
              .compile
              .drain *> events.get
          }
          .asserting(it => assert(it == List(Acquired, Released, Acquired, Released)))
      }
    }

    "bracketCase" - {
      "normal termination" in forAll { (s0: List[Stream[Pure, Int]]) =>
        Counter[IO].flatMap { counter =>
          var ecs: Chain[ExitCase[Throwable]] = Chain.empty
          val s = s0.map { s =>
            Stream
              .bracketCase(counter.increment) { (_, ec) =>
                counter.decrement >> IO { ecs = ecs :+ ec }
              }
              .flatMap(_ => s)
          }
          val s2 = s.foldLeft(Stream.empty: Stream[IO, Int])(_ ++ _)
          s2.append(s2.take(10)).take(10).compile.drain.flatMap(_ => counter.get).asserting {
            count =>
              assert(count == 0L)
              ecs.toList.foreach(it => assert(it == ExitCase.Completed))
              Succeeded
          }
        }
      }

      "failure" in forAll { (s0: List[Stream[Pure, Int]]) =>
        Counter[IO].flatMap { counter =>
          var ecs: Chain[ExitCase[Throwable]] = Chain.empty
          val s = s0.map { s =>
            Stream
              .bracketCase(counter.increment) { (_, ec) =>
                counter.decrement >> IO { ecs = ecs :+ ec }
              }
              .flatMap(_ => s ++ Stream.raiseError[IO](new Err))
          }
          val s2 = s.foldLeft(Stream.empty: Stream[IO, Int])(_ ++ _)
          s2.compile.drain.attempt.flatMap(_ => counter.get).asserting { count =>
            assert(count == 0L)
            ecs.toList.foreach(it => assert(it.isInstanceOf[ExitCase.Error[Throwable]]))
            Succeeded
          }
        }
      }

      "cancelation" in {
        forAll { (s0: Stream[Pure, Int]) =>
          Counter[IO].flatMap { counter =>
            var ecs: Chain[ExitCase[Throwable]] = Chain.empty
            val s =
              Stream
                .bracketCase(counter.increment) { (_, ec) =>
                  counter.decrement >> IO { ecs = ecs :+ ec }
                }
                .flatMap(_ => s0 ++ Stream.never[IO])
            s.compile.drain.start
              .flatMap(f => IO.sleep(50.millis) >> f.cancel)
              .flatMap(_ => counter.get)
              .asserting { count =>
                assert(count == 0L)
                ecs.toList.foreach(it => assert(it == ExitCase.Canceled))
                Succeeded
              }
          }
        }
      }

      "interruption" in {
        forAll { (s0: Stream[Pure, Int]) =>
          Counter[IO].flatMap { counter =>
            var ecs: Chain[ExitCase[Throwable]] = Chain.empty
            val s =
              Stream
                .bracketCase(counter.increment) { (_, ec) =>
                  counter.decrement >> IO { ecs = ecs :+ ec }
                }
                .flatMap(_ => s0 ++ Stream.never[IO])
            s.interruptAfter(50.millis).compile.drain.flatMap(_ => counter.get).asserting { count =>
              assert(count == 0L)
              ecs.toList.foreach(it => assert(it == ExitCase.Canceled))
              Succeeded
            }
          }
        }
      }
    }

    "buffer" - {
      "identity" in forAll { (s: Stream[Pure, Int], n: PosInt) =>
        assert(s.buffer(n).toVector == s.toVector)
      }

      "buffer results of evalMap" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        IO.suspend {
          var counter = 0
          val s2 = s.append(Stream.emits(List.fill(n + 1)(0))).repeat
          s2.evalMap { i =>
              IO { counter += 1; i }
            }
            .buffer(n)
            .take(n + 1)
            .compile
            .drain
            .asserting(_ => assert(counter == (n * 2)))
        }
      }
    }

    "bufferAll" - {
      "identity" in forAll { (s: Stream[Pure, Int]) =>
        assert(s.bufferAll.toVector == s.toVector)
      }

      "buffer results of evalMap" in forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2
        IO.suspend {
          var counter = 0
          s.append(s)
            .evalMap { i =>
              IO { counter += 1; i }
            }
            .bufferAll
            .take(s.toList.size + 1)
            .compile
            .drain
            .asserting(_ => assert(counter == expected))
        }
      }
    }

    "bufferBy" - {
      "identity" in forAll { (s: Stream[Pure, Int]) =>
        assert(s.bufferBy(_ >= 0).toVector == s.toVector)
      }

      "buffer results of evalMap" in forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList.size * 2 + 1
        IO.suspend {
          var counter = 0
          val s2 = s.map(x => if (x == Int.MinValue) x + 1 else x).map(_.abs)
          val s3 = s2.append(Stream.emit(-1)).append(s2).evalMap { i =>
            IO { counter += 1; i }
          }
          s3.bufferBy(_ >= 0)
            .take(s.toList.size + 2)
            .compile
            .drain
            .asserting(_ => assert(counter == expected))
        }
      }
    }

    "cancelation of compiled streams" - {
      def startAndCancelSoonAfter[A](fa: IO[A]): IO[Unit] =
        fa.start.flatMap(fiber => IO.sleep(1.second) >> fiber.cancel)

      def testCancelation[A](s: Stream[IO, A]): IO[Assertion] =
        startAndCancelSoonAfter(s.compile.drain).assertNoException

      def constantStream: Stream[IO, Int] =
        if (isJVM) Stream.constant(1) else Stream.constant(1).evalTap(_ => IO.sleep(1.milli))

      "constant" in testCancelation(constantStream)

      "bracketed stream" in testCancelation(
        Stream.bracket(IO.unit)(_ => IO.unit).flatMap(_ => constantStream)
      )

      "concurrently" in testCancelation {
        constantStream.concurrently(constantStream)
      }

      "merge" in testCancelation {
        constantStream.merge(constantStream)
      }

      "parJoin" in testCancelation {
        Stream(constantStream, constantStream).parJoin(2)
      }

      "#1236" in testCancelation {
        Stream
          .eval(Queue.bounded[IO, Int](1))
          .flatMap { q =>
            Stream(
              Stream
                .unfold(0)(i => (i + 1, i + 1).some)
                .flatMap { i =>
                  Stream.sleep_(50.milliseconds) ++ Stream.emit(i)
                }
                .through(q.enqueue),
              q.dequeue.drain
            ).parJoin(2)
          }
      }
    }

    "changes" in {
      assert(Stream.empty.covaryOutput[Int].changes.toList == Nil)
      assert(Stream(1, 2, 3, 4).changes.toList == List(1, 2, 3, 4))
      assert(Stream(1, 1, 2, 2, 3, 3, 4, 3).changes.toList == List(1, 2, 3, 4, 3))
      val result = Stream("1", "2", "33", "44", "5", "66")
        .changesBy(_.length)
        .toList
      assert(result == List("1", "33", "5", "66"))
    }

    "chunk" in {
      forAll { (c: Chunk[Int]) =>
        assert(Stream.chunk(c).compile.to(Chunk) == c)
      }
    }

    "chunkLimit" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val sizeV = s.chunkLimit(n).toVector.map(_.size)
      assert(sizeV.forall(_ <= n))
      assert(sizeV.combineAll == s.toVector.size)
    }

    "chunkMin" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
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

    "chunkN" - {
      "fewer" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val chunkedV = s.chunkN(n, true).toVector
        val unchunkedV = s.toVector
        // All but last list have n0 values
        assert(chunkedV.dropRight(1).forall(_.size == n))
        // Last list has at most n0 values
        assert(chunkedV.lastOption.fold(true)(_.size <= n))
        // Flattened sequence is equal to vector without chunking
        assert(chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) == unchunkedV)
      }

      "no-fewer" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
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

    "chunks" - {
      "chunks.map identity" in forAll { (v: Vector[Vector[Int]]) =>
        val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
        assert(s.chunks.map(_.toVector).toVector == v.filter(_.nonEmpty))
      }

      "chunks.flatMap(chunk) identity" in forAll { (v: Vector[Vector[Int]]) =>
        val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
        assert(s.chunks.flatMap(Stream.chunk).toVector == v.flatten)
      }
    }

    "collect" in forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      assert(s.collect(pf).toVector == s.toVector.collect(pf))
    }

    "collectFirst" in forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      assert(s.collectFirst(pf).toVector == s.collectFirst(pf).toVector)
    }

    "compile" - {
      "resource" - {
        "concurrently" in {
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
          prog.use(x => x).assertNoException
        }

        "onFinalise" in {
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
            .asserting(it => assert(it == expected))
        }

        "onFinalizeWeak" in {
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
            .asserting(it => assert(it == List("1", "emit", "2", "3", "4")))
        }

        "last scope extended, not all scopes" - {
          "1" in {
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
              .asserting(it => assert(it == List("first finalize", "start", "second finalize")))
          }
          "2" in {
            Ref[IO]
              .of(List.empty[String])
              .flatMap { st =>
                def record(s: String): IO[Unit] = st.update(_ :+ s)
                (Stream.bracket(IO("a"))(_ => record("first finalize")) ++
                  Stream.bracket(IO("b"))(_ => record("second finalize")) ++
                  Stream.bracket(IO("c"))(_ => record("third finalize"))).compile.resource.lastOrError
                  .use(x => record(x)) *> st.get
              }
              .asserting(it =>
                assert(it == List("first finalize", "second finalize", "c", "third finalize"))
              )
          }
        }

        "allocated" in {
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
            .asserting(it => assert(it == false))
        }

        "interruption" - {
          "1" in {
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
              .asserting(it => assert(it == true))
          }

          "2" in {
            val p = (Deferred[IO, ExitCase[Throwable]]).flatMap { stop =>
              val r = Stream
                .never[IO]
                .compile
                .resource
                .drain
                .use { _ =>
                  IO.unit
                }
                .guaranteeCase(stop.complete)

              r.start.flatMap { fiber =>
                IO.sleep(200.millis) >> fiber.cancel >> stop.get
              }
            }
            p.timeout(2.seconds)
              .asserting(it => assert(it == ExitCase.Canceled))
          }
        }
      }
    }

    "concurrently" - {
      "when background stream terminates, overall stream continues" in forAll {
        (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
          val expected = s1.toList
          s1.delayBy[IO](25.millis)
            .concurrently(s2)
            .compile
            .toList
            .asserting(it => assert(it == expected))
      }

      "when background stream fails, overall stream fails" in forAll { (s: Stream[Pure, Int]) =>
        s.delayBy[IO](25.millis)
          .concurrently(Stream.raiseError[IO](new Err))
          .compile
          .drain
          .assertThrows[Err]
      }

      "when primary stream fails, overall stream fails and background stream is terminated" in {
        Stream
          .eval(Semaphore[IO](0))
          .flatMap { semaphore =>
            val bg = Stream.repeatEval(IO(1) *> IO.sleep(50.millis)).onFinalize(semaphore.release)
            val fg = Stream.raiseError[IO](new Err).delayBy(25.millis)
            fg.concurrently(bg)
              .onFinalize(semaphore.acquire)
          }
          .compile
          .drain
          .assertThrows[Err]
      }

      "when primary stream terminates, background stream is terminated" in forAll {
        (s: Stream[Pure, Int]) =>
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              val bg = Stream.repeatEval(IO(1) *> IO.sleep(50.millis)).onFinalize(semaphore.release)
              val fg = s.delayBy[IO](25.millis)
              fg.concurrently(bg)
                .onFinalize(semaphore.acquire)
            }
            .compile
            .drain
            .assertNoException
      }

      "when background stream fails, primary stream fails even when hung" in forAll {
        (s: Stream[Pure, Int]) =>
          Stream
            .eval(Deferred[IO, Unit])
            .flatMap { gate =>
              Stream(1)
                .delayBy[IO](25.millis)
                .append(s)
                .concurrently(Stream.raiseError[IO](new Err))
                .evalTap(_ => gate.get)
            }
            .compile
            .drain
            .assertThrows[Err]
      }

      "run finalizers of background stream and properly handle exception" in forAll {
        s: Stream[Pure, Int] =>
          Ref
            .of[IO, Boolean](false)
            .flatMap { runnerRun =>
              Ref.of[IO, List[String]](Nil).flatMap { finRef =>
                Deferred[IO, Unit].flatMap { halt =>
                  def runner: Stream[IO, Unit] =
                    Stream
                      .bracket(runnerRun.set(true))(_ =>
                        IO.sleep(100.millis) >> // assure this inner finalizer always take longer run than `outer`
                          finRef.update(_ :+ "Inner") >> // signal finalizer invoked
                          IO.raiseError[Unit](new Err) // signal a failure
                      ) >> // flag the concurrently had chance to start, as if the `s` will be empty `runner` may not be evaluated at all.
                      Stream.eval_(halt.complete(())) // immediately interrupt the outer stream

                  Stream
                    .bracket(IO.unit)(_ => finRef.update(_ :+ "Outer"))
                    .flatMap { _ =>
                      s.covary[IO].concurrently(runner)
                    }
                    .interruptWhen(halt.get.attempt)
                    .compile
                    .drain
                    .attempt
                    .flatMap { r =>
                      runnerRun.get.flatMap { runnerStarted =>
                        finRef.get.flatMap { finalizers =>
                          if (runnerStarted) IO {
                            // finalizers shall be called in correct order and
                            // exception shall be thrown
                            assert(finalizers == List("Inner", "Outer"))
                            assert(r.swap.toOption.get.isInstanceOf[Err])
                          } else
                            IO {
                              // still the outer finalizer shall be run, but there is no failure in `s`
                              assert(finalizers == List("Outer"))
                              assert(r == Right(()))
                            }
                        }
                      }
                    }
                }
              }
            }
            .assertNoException
      }
    }

    "debounce" in {
      val delay = 200.milliseconds
      (Stream(1, 2, 3) ++ Stream.sleep[IO](delay * 2) ++ Stream() ++ Stream(4, 5) ++ Stream
        .sleep[IO](delay / 2) ++ Stream(6))
        .debounce(delay)
        .compile
        .toList
        .asserting(it => assert(it == List(3, 6)))
    }

    "delete" in forAll { (s: Stream[Pure, Int], idx0: PosZInt) =>
      val v = s.toVector
      val i = if (v.isEmpty) 0 else v(idx0 % v.size)
      assert(s.delete(_ == i).toVector == v.diff(Vector(i)))
    }

    "drop" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosZInt) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else n0 % v.size
      val n = if (negate) -n1 else n1
      assert(s.drop(n).toVector == s.toVector.drop(n))
    }

    "dropLast" in forAll { (s: Stream[Pure, Int]) =>
      assert(s.dropLast.toVector == s.toVector.dropRight(1))
    }

    "dropLastIf" in forAll { (s: Stream[Pure, Int]) =>
      assert(s.dropLastIf(_ => false).toVector == s.toVector)
      assert(s.dropLastIf(_ => true).toVector == s.toVector.dropRight(1))
    }

    "dropRight" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosZInt) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else n0 % v.size
      val n = if (negate) -n1 else n1
      assert(s.dropRight(n).toVector == v.dropRight(n))
    }

    "dropWhile" in forAll { (s: Stream[Pure, Int], n0: PosZInt) =>
      val n = n0 % 20
      val set = s.toVector.take(n).toSet
      assert(s.dropWhile(set).toVector == s.toVector.dropWhile(set))
    }

    "dropThrough" in forAll { (s: Stream[Pure, Int], n0: PosZInt) =>
      val n = n0 % 20
      val set = s.toVector.take(n).toSet
      val expected = {
        val vec = s.toVector.dropWhile(set)
        if (vec.isEmpty) vec else vec.tail
      }
      assert(s.dropThrough(set).toVector == expected)
    }

    "duration" in {
      val delay = 200.millis
      Stream
        .emit(())
        .append(Stream.eval(IO.sleep(delay)))
        .zip(Stream.duration[IO])
        .drop(1)
        .map(_._2)
        .compile
        .toVector
        .asserting { result =>
          assert(result.size == 1)
          val head = result.head
          assert(head.toMillis >= (delay.toMillis - 5))
        }
    }

    "either" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      val s2List = s2.toList
      s1.covary[IO].either(s2).compile.toList.asserting { result =>
        assert(result.collect { case Left(i)  => i } == s1List)
        assert(result.collect { case Right(i) => i } == s2List)
      }
    }

    "eval" in { Stream.eval(SyncIO(23)).compile.toList.asserting(it => assert(it == List(23))) }

    "evals" - {
      "with List" in {
        Stream.evals(IO(List(1, 2, 3))).compile.toList.asserting(it => assert(it == List(1, 2, 3)))
      }
      "with Chain" in {
        Stream.evals(IO(Chain(4, 5, 6))).compile.toList.asserting(it => assert(it == List(4, 5, 6)))
      }
      "with Option" in {
        Stream.evals(IO(Option(42))).compile.toList.asserting(it => assert(it == List(42)))
      }
    }

    "evalSeq" - {
      "with List" in {
        Stream
          .evalSeq(IO(List(1, 2, 3)))
          .compile
          .toList
          .asserting(it => assert(it == List(1, 2, 3)))
      }
      "with Seq" in {
        Stream.evalSeq(IO(Seq(4, 5, 6))).compile.toList.asserting(it => assert(it == List(4, 5, 6)))
      }
    }

    "evalFilter" - {
      "with effectful const(true)" in forAll { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.evalFilter(_ => IO.pure(true)).compile.toList.asserting(it => assert(it == s1))
      }

      "with effectful const(false)" in forAll { s: Stream[Pure, Int] =>
        s.evalFilter(_ => IO.pure(false)).compile.toList.asserting(it => assert(it.isEmpty))
      }

      "with function that filters out odd elements" in {
        Stream
          .range(1, 10)
          .evalFilter(e => IO(e % 2 == 0))
          .compile
          .toList
          .asserting(it => assert(it == List(2, 4, 6, 8)))
      }
    }

    "evalFilterAsync" - {
      "with effectful const(true)" in forAll { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.covary[IO]
          .evalFilterAsync(5)(_ => IO.pure(true))
          .compile
          .toList
          .asserting(it => assert(it == s1))
      }

      "with effectful const(false)" in forAll { s: Stream[Pure, Int] =>
        s.covary[IO]
          .evalFilterAsync(5)(_ => IO.pure(false))
          .compile
          .toList
          .asserting(it => assert(it.isEmpty))
      }

      "with function that filters out odd elements" in {
        Stream
          .range(1, 10)
          .evalFilterAsync[IO](5)(e => IO(e % 2 == 0))
          .compile
          .toList
          .asserting(it => assert(it == List(2, 4, 6, 8)))
      }

      "filters up to N items in parallel" in {
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
          .asserting(it => assert(it == ((n, 0))))
      }
    }

    "evalFilterNot" - {
      "with effectful const(true)" in forAll { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.evalFilterNot(_ => IO.pure(false)).compile.toList.asserting(it => assert(it == s1))
      }

      "with effectful const(false)" in forAll { s: Stream[Pure, Int] =>
        s.evalFilterNot(_ => IO.pure(true)).compile.toList.asserting(it => assert(it.isEmpty))
      }

      "with function that filters out odd elements" in {
        Stream
          .range(1, 10)
          .evalFilterNot(e => IO(e % 2 == 0))
          .compile
          .toList
          .asserting(it => assert(it == List(1, 3, 5, 7, 9)))
      }
    }

    "evalFilterNotAsync" - {
      "with effectful const(true)" in forAll { s: Stream[Pure, Int] =>
        s.covary[IO]
          .evalFilterNotAsync(5)(_ => IO.pure(true))
          .compile
          .toList
          .asserting(it => assert(it.isEmpty))
      }

      "with effectful const(false)" in forAll { s: Stream[Pure, Int] =>
        val s1 = s.toList
        s.covary[IO]
          .evalFilterNotAsync(5)(_ => IO.pure(false))
          .compile
          .toList
          .asserting(it => assert(it == s1))
      }

      "with function that filters out odd elements" in {
        Stream
          .range(1, 10)
          .evalFilterNotAsync[IO](5)(e => IO(e % 2 == 0))
          .compile
          .toList
          .asserting(it => assert(it == List(1, 3, 5, 7, 9)))
      }

      "filters up to N items in parallel" in {
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
          .asserting(it => assert(it == ((n, 0))))
      }
    }

    "evalMapAccumulate" in forAll { (s: Stream[Pure, Int], m: Int, n0: PosInt) =>
      val sVector = s.toVector
      val n = n0 % 20 + 1
      val f = (_: Int) % n == 0
      val r = s.covary[IO].evalMapAccumulate(m)((s, i) => IO.pure((s + i, f(i))))
      r.map(_._1).compile.toVector.asserting(it => assert(it == sVector.scanLeft(m)(_ + _).tail))
      r.map(_._2).compile.toVector.asserting(it => assert(it == sVector.map(f)))
    }

    "evalScan" in forAll { (s: Stream[Pure, Int], n: String) =>
      val sVector = s.toVector
      val f: (String, Int) => IO[String] = (a: String, b: Int) => IO.pure(a + b)
      val g = (a: String, b: Int) => a + b
      s.covary[IO]
        .evalScan(n)(f)
        .compile
        .toVector
        .asserting(it => assert(it == sVector.scanLeft(n)(g)))
    }

    "every" in {
      flickersOnTravis
      type BD = (Boolean, FiniteDuration)
      val durationSinceLastTrue: Pipe[Pure, BD, BD] = {
        def go(lastTrue: FiniteDuration, s: Stream[Pure, BD]): Pull[Pure, BD, Unit] =
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

      (IO.shift >> durationsSinceSpike.compile.toVector).unsafeToFuture().map { result =>
        val list = result.toList
        withClue("every always emits true first") { assert(list.head._1) }
        withClue(s"true means the delay has passed: ${list.tail}") {
          assert(list.tail.filter(_._1).map(_._2).forall { _ >= delay })
        }
        withClue(s"false means the delay has not passed: ${list.tail}") {
          assert(list.tail.filterNot(_._1).map(_._2).forall { _ <= delay })
        }
      }
    }

    "exists" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (i: Int) => i % n == 0
      assert(s.exists(f).toList == List(s.toList.exists(f)))
    }

    "filter" - {
      "1" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val predicate = (i: Int) => i % n == 0
        assert(s.filter(predicate).toList == s.toList.filter(predicate))
      }

      "2" in forAll { (s: Stream[Pure, Double]) =>
        val predicate = (i: Double) => i - i.floor < 0.5
        val s2 = s.mapChunks(c => Chunk.doubles(c.toArray))
        assert(s2.filter(predicate).toList == s2.toList.filter(predicate))
      }

      "3" in forAll { (s: Stream[Pure, Byte]) =>
        val predicate = (b: Byte) => b < 0
        val s2 = s.mapChunks(c => Chunk.bytes(c.toArray))
        assert(s2.filter(predicate).toList == s2.toList.filter(predicate))
      }

      "4" in forAll { (s: Stream[Pure, Boolean]) =>
        val predicate = (b: Boolean) => !b
        val s2 = s.mapChunks(c => Chunk.booleans(c.toArray))
        assert(s2.filter(predicate).toList == s2.toList.filter(predicate))
      }
    }

    "find" in forAll { (s: Stream[Pure, Int], i: Int) =>
      val predicate = (item: Int) => item < i
      assert(s.find(predicate).toList == s.toList.find(predicate).toList)
    }

    "flatMap" in forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
      assert(s.flatMap(inner => inner).toList == s.toList.flatMap(inner => inner.toList))
    }

    "fold" - {
      "1" in forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        assert(s.fold(n)(f).toList == List(s.toList.foldLeft(n)(f)))
      }

      "2" in forAll { (s: Stream[Pure, Int], n: String) =>
        val f = (a: String, b: Int) => a + b
        assert(s.fold(n)(f).toList == List(s.toList.foldLeft(n)(f)))
      }
    }

    "foldMonoid" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        assert(s.foldMonoid.toVector == Vector(s.toVector.combineAll))
      }

      "2" in forAll { (s: Stream[Pure, Double]) =>
        assert(s.foldMonoid.toVector == Vector(s.toVector.combineAll))
      }
    }

    "fold1" in forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      val expected = v.headOption.fold(Vector.empty[Int])(h => Vector(v.drop(1).foldLeft(h)(f)))
      assert(s.fold1(f).toVector == expected)
    }

    "forall" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (i: Int) => i % n == 0
      assert(s.forall(f).toList == List(s.toList.forall(f)))
    }

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val stream: Stream[Fallible, Int] = Stream.fromEither[Fallible](either)
      either match {
        case Left(t)  => assert(stream.toList == Left(t))
        case Right(i) => assert(stream.toList == Right(List(i)))
      }
    }

    "fromIterator" in forAll { x: List[Int] =>
      Stream
        .fromIterator[SyncIO](x.iterator)
        .compile
        .toList
        .asserting(it => assert(it == x))
    }

    "fromBlockingIterator" in forAll { x: List[Int] =>
      Stream
        .fromBlockingIterator[IO](Blocker.liftExecutionContext(implicitly), x.iterator)
        .compile
        .toList
        .asserting(it => assert(it == x))
    }

    "groupAdjacentBy" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
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

    "groupAdjacentByLimit" - {
      "limiting" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val s1 = s.groupAdjacentByLimit(n)(_ => true)
        assert(s1.map(_._2).toList.map(_.toList) == s.toList.grouped(n).toList)
      }
    }

    "groupWithin" - {
      "should never lose any elements" in forAll {
        (s0: Stream[Pure, PosInt], d0: PosInt, maxGroupSize0: PosZInt) =>
          val maxGroupSize = maxGroupSize0 % 20 + 1
          val d = (d0 % 50).millis
          val s = s0.map(_ % 500)
          val sList = s.toList
          s.covary[IO]
            .evalTap(shortDuration => IO.sleep(shortDuration.micros))
            .groupWithin(maxGroupSize, d)
            .flatMap(s => Stream.emits(s.toList))
            .compile
            .toList
            .asserting(it => assert(it == sList))
      }

      "should never emit empty groups" in forAll {
        (s: Stream[Pure, PosInt], d0: PosInt, maxGroupSize0: PosZInt) =>
          val maxGroupSize = maxGroupSize0 % 20 + 1
          val d = (d0 % 50).millis
          Stream(PosInt(1))
            .append(s)
            .map(_ % 500)
            .covary[IO]
            .evalTap(shortDuration => IO.sleep(shortDuration.micros))
            .groupWithin(maxGroupSize, d)
            .map(_.toList)
            .compile
            .toList
            .asserting(it => assert(it.forall(_.nonEmpty)))
      }

      "should never have more elements than in its specified limit" in forAll {
        (s: Stream[Pure, PosInt], d0: PosInt, maxGroupSize0: PosZInt) =>
          val maxGroupSize = maxGroupSize0 % 20 + 1
          val d = (d0 % 50).millis
          s.map(_ % 500)
            .evalTap(shortDuration => IO.sleep(shortDuration.micros))
            .groupWithin(maxGroupSize, d)
            .map(_.toList.size)
            .compile
            .toList
            .asserting(it => assert(it.forall(_ <= maxGroupSize)))
      }

      "should return a finite stream back in a single chunk given a group size equal to the stream size and an absurdly high duration" in forAll {
        (streamAsList0: List[Int]) =>
          val streamAsList = 0 :: streamAsList0
          Stream
            .emits(streamAsList)
            .covary[IO]
            .groupWithin(streamAsList.size, (Int.MaxValue - 1L).nanoseconds)
            .compile
            .toList
            .asserting(it => assert(it.head.toList == streamAsList))
      }
    }

    "handleErrorWith" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        val s2 = s.covary[Fallible] ++ Stream.raiseError[Fallible](new Err)
        assert(s2.handleErrorWith(_ => Stream.empty).toList == Right(s.toList))
      }

      "2" in {
        val result = Stream.raiseError[Fallible](new Err).handleErrorWith(_ => Stream(1)).toList
        assert(result == Right(List(1)))
      }

      "3" in {
        val result = Stream(1)
          .append(Stream.raiseError[Fallible](new Err))
          .handleErrorWith(_ => Stream(1))
          .toList
        assert(result == Right(List(1, 1)))
      }

      "4 - error in eval" in {
        Stream
          .eval(SyncIO(throw new Err))
          .map(Right(_): Either[Throwable, Int])
          .handleErrorWith(t => Stream.emit(Left(t)).covary[SyncIO])
          .take(1)
          .compile
          .toVector
          .asserting(it => assert(it.head.swap.toOption.get.isInstanceOf[Err]))
      }

      "5" in {
        Stream
          .raiseError[SyncIO](new Err)
          .handleErrorWith(e => Stream(e))
          .flatMap(Stream.emit)
          .compile
          .toVector
          .asserting { v =>
            assert(v.size == 1)
            assert(v.head.isInstanceOf[Err])
          }
      }

      "6" in {
        Stream
          .raiseError[IO](new Err)
          .handleErrorWith(Stream.emit)
          .map(identity)
          .compile
          .toVector
          .asserting { v =>
            assert(v.size == 1)
            assert(v.head.isInstanceOf[Err])
          }
      }

      "7 - parJoin" in {
        Stream(Stream.emit(1).covary[IO], Stream.raiseError[IO](new Err), Stream.emit(2).covary[IO])
          .covary[IO]
          .parJoin(4)
          .attempt
          .compile
          .toVector
          .asserting(it =>
            assert(
              it.collect { case Left(t) => t }
                .exists(_.isInstanceOf[Err])
            )
          )
      }

      "8" in {
        SyncIO.suspend {
          var i = 0
          Pull
            .pure(1)
            .covary[SyncIO]
            .handleErrorWith(_ => { i += 1; Pull.pure(2) })
            .flatMap { _ =>
              Pull.output1(i) >> Pull.raiseError[SyncIO](new Err)
            }
            .stream
            .compile
            .drain
            .assertThrows[Err]
            .asserting(_ => assert(i == 0))
        }
      }

      "9" in {
        SyncIO.suspend {
          var i = 0
          Pull
            .eval(SyncIO(1))
            .handleErrorWith(_ => { i += 1; Pull.pure(2) })
            .flatMap { _ =>
              Pull.output1(i) >> Pull.raiseError[SyncIO](new Err)
            }
            .stream
            .compile
            .drain
            .assertThrows[Err]
            .asserting(_ => assert(i == 0))
        }
      }

      "10" in {
        SyncIO.suspend {
          var i = 0
          Pull
            .eval(SyncIO(1))
            .flatMap { x =>
              Pull
                .pure(x)
                .handleErrorWith(_ => { i += 1; Pull.pure(2) })
                .flatMap { _ =>
                  Pull.output1(i) >> Pull.raiseError[SyncIO](new Err)
                }
            }
            .stream
            .compile
            .drain
            .assertThrows[Err]
            .asserting(_ => assert(i == 0))
        }
      }

      "11" in {
        SyncIO.suspend {
          var i = 0
          Pull
            .eval(SyncIO(???))
            .handleErrorWith(_ => Pull.pure(i += 1))
            .flatMap { _ =>
              Pull.output1(i)
            }
            .stream
            .compile
            .drain
            .asserting(_ => assert(i == 1))
        }
      }

      "12" in {
        SyncIO.suspend {
          var i = 0
          Stream
            .bracket(SyncIO(1))(_ => SyncIO(i += 1))
            .flatMap(_ => Stream.eval(SyncIO(???)))
            .compile
            .drain
            .assertThrows[NotImplementedError]
            .asserting(_ => assert(i == 1))
        }
      }

      "13" in {
        SyncIO.suspend {
          var i = 0
          Stream
            .range(0, 10)
            .covary[SyncIO]
            .append(Stream.raiseError[SyncIO](new Err))
            .handleErrorWith { _ =>
              i += 1; Stream.empty
            }
            .compile
            .drain
            .asserting(_ => assert(i == 1))
        }
      }

      "14" in {
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

      "15" in {
        SyncIO.suspend {
          var i = 0
          (Stream
            .range(0, 3)
            .covary[SyncIO] ++ Stream.raiseError[SyncIO](new Err)).unchunk.pull.echo
            .handleErrorWith { _ =>
              i += 1; Pull.done
            }
            .stream
            .compile
            .drain
            .asserting(_ => assert(i == 1))
        }
      }

      "16 - parJoin CompositeFailure" in {
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
          .asserting({
            case Left(err: CompositeFailure) =>
              assert(err.all.toList.count(_.isInstanceOf[Err]) == 3)
            case Left(err)    => fail("Expected Left[CompositeFailure]", err)
            case Right(value) => fail(s"Expected Left[CompositeFailure] got Right($value)")
          })
      }
    }

    "head" in forAll { (s: Stream[Pure, Int]) =>
      assert(s.head.toList == s.toList.take(1))
    }

    "interleave" - {
      "interleave left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        assert(ones.interleave(s).toList == List("1", "A", "1", "B", "1", "C"))
        assert(s.interleave(ones).toList == List("A", "1", "B", "1", "C", "1"))
      }

      "interleave both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assert(ones.interleave(as).take(3).toList == List("1", "A", "1"))
        assert(as.interleave(ones).take(3).toList == List("A", "1", "A"))
      }

      "interleaveAll left/right side infinite" in {
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

      "interleaveAll both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assert(ones.interleaveAll(as).take(3).toList == List("1", "A", "1"))
        assert(as.interleaveAll(ones).take(3).toList == List("A", "1", "A"))
      }

      // Uses a small scope to avoid using time to generate too large streams and not finishing
      "interleave is equal to interleaveAll on infinite streams (by step-indexing)" in {
        forAll(intsBetween(0, 100)) { (n: Int) =>
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

    "interrupt" - {
      val interruptRepeatCount = if (isJVM) 25 else 1

      "1 - can interrupt a hung eval" in {
        forAll { (s: Stream[Pure, Int]) =>
          val interruptSoon = Stream.sleep_[IO](50.millis).compile.drain.attempt
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              s.covary[IO].evalMap(_ => semaphore.acquire).interruptWhen(interruptSoon)
            }
            .compile
            .toList
            .asserting(it => assert(it == Nil))
        }
      }

      "2 - termination successful when stream doing interruption is hung" in {
        forAll { (s: Stream[Pure, Int]) =>
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              val interrupt = Stream.emit(true) ++ Stream.eval_(semaphore.release)
              s.covary[IO].evalMap(_ => semaphore.acquire).interruptWhen(interrupt)
            }
            .compile
            .toList
            .asserting(it => assert(it == Nil))
        }
      }

      // These IO streams cannot be interrupted on JS b/c they never yield execution
      if (isJVM) {
        "3 - constant stream" in {
          val interruptSoon = Stream.sleep_[IO](20.millis).compile.drain.attempt
          Stream
            .constant(true)
            .covary[IO]
            .interruptWhen(interruptSoon)
            .compile
            .drain
            .assertNoException
            .repeatTest(interruptRepeatCount)
        }

        "4 - interruption of constant stream with a flatMap" in {
          val interrupt =
            Stream.sleep_[IO](20.millis).compile.drain.attempt
          Stream
            .constant(true)
            .covary[IO]
            .interruptWhen(interrupt)
            .flatMap(_ => Stream.emit(1))
            .compile
            .drain
            .assertNoException
            .repeatTest(interruptRepeatCount)
        }

        "5 - interruption of an infinitely recursive stream" in {
          val interrupt =
            Stream.sleep_[IO](20.millis).compile.drain.attempt

          def loop(i: Int): Stream[IO, Int] = Stream.emit(i).covary[IO].flatMap { i =>
            Stream.emit(i) ++ loop(i + 1)
          }

          loop(0)
            .interruptWhen(interrupt)
            .compile
            .drain
            .assertNoException
            .repeatTest(interruptRepeatCount)
        }

        "6 - interruption of an infinitely recursive stream that never emits" in {
          val interrupt =
            Stream.sleep_[IO](20.millis).compile.drain.attempt

          def loop: Stream[IO, Int] =
            Stream.eval(IO.unit) >> loop

          loop
            .interruptWhen(interrupt)
            .compile
            .drain
            .assertNoException
            .repeatTest(interruptRepeatCount)
        }

        "7 - interruption of an infinitely recursive stream that never emits and has no eval" in {
          val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
          def loop: Stream[IO, Int] = Stream.emit(()).covary[IO] >> loop
          loop
            .interruptWhen(interrupt)
            .compile
            .drain
            .assertNoException
            .repeatTest(interruptRepeatCount)
        }

        "8 - interruption of a stream that repeatedly evaluates" in {
          val interrupt =
            Stream.sleep_[IO](20.millis).compile.drain.attempt
          Stream
            .repeatEval(IO.unit)
            .interruptWhen(interrupt)
            .compile
            .drain
            .assertNoException
            .repeatTest(interruptRepeatCount)
        }

        "9 - interruption of the constant drained stream" in {
          val interrupt =
            Stream.sleep_[IO](1.millis).compile.drain.attempt
          Stream
            .constant(true)
            .dropWhile(!_)
            .covary[IO]
            .interruptWhen(interrupt)
            .compile
            .drain
            .assertNoException
            .repeatTest(interruptRepeatCount)
        }

        "10 - terminates when interruption stream is infinitely false" in forAll {
          (s: Stream[Pure, Int]) =>
            s.covary[IO]
              .interruptWhen(Stream.constant(false))
              .compile
              .toList
              .asserting(it => assert(it == s.toList))
        }
      }

      "11 - both streams hung" in forAll { (s: Stream[Pure, Int]) =>
        Stream
          .eval(Semaphore[IO](0))
          .flatMap { barrier =>
            Stream.eval(Semaphore[IO](0)).flatMap { enableInterrupt =>
              val interrupt = Stream.eval(enableInterrupt.acquire) >> Stream.emit(false)
              s.covary[IO]
                .evalMap { i =>
                  // enable interruption and hang when hitting a value divisible by 7
                  if (i % 7 == 0) enableInterrupt.release.flatMap { _ =>
                    barrier.acquire.as(i)
                  } else IO.pure(i)
                }
                .interruptWhen(interrupt)
            }
          }
          .compile
          .toList
          .asserting { result =>
            // as soon as we hit a value divisible by 7, we enable interruption then hang before emitting it,
            // so there should be no elements in the output that are divisible by 7
            // this also checks that interruption works fine even if one or both streams are in a hung state
            assert(result.forall(i => i % 7 != 0))
          }
      }

      "12 - interruption of stream that never terminates in flatMap" in {
        forAll { (s: Stream[Pure, Int]) =>
          val interrupt = Stream.sleep_[IO](50.millis).compile.drain.attempt
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              s.covary[IO].interruptWhen(interrupt) >> Stream.eval_(semaphore.acquire)
            }
            .compile
            .toList
            .asserting(it => assert(it == Nil))
        }
      }

      "12a - minimal interruption of stream that never terminates in flatMap" in {
        Stream(1)
          .covary[IO]
          .interruptWhen(IO.sleep(10.millis).attempt)
          .flatMap(_ => Stream.eval(IO.never))
          .compile
          .drain
          .assertNoException
      }

      "13 - failure from interruption signal will be propagated to main stream even when flatMap stream is hung" in {
        forAll { (s: Stream[Pure, Int]) =>
          val interrupt = Stream.sleep_[IO](50.millis) ++ Stream.raiseError[IO](new Err)
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              Stream(1)
                .append(s)
                .covary[IO]
                .interruptWhen(interrupt)
                .flatMap(_ => Stream.eval_(semaphore.acquire))
            }
            .compile
            .toList
            .assertThrows[Err]
        }
      }

      "14 - minimal resume on append" in {
        Stream
          .eval(IO.never)
          .interruptWhen(IO.sleep(10.millis).attempt)
          .append(Stream(5))
          .compile
          .toList
          .asserting(it => assert(it == List(5)))
      }

      "14a - interrupt evalMap and then resume on append" in {
        forAll { s: Stream[Pure, Int] =>
          val expected = s.toList
          val interrupt = IO.sleep(50.millis).attempt
          s.covary[IO]
            .interruptWhen(interrupt)
            .evalMap(_ => IO.never)
            .drain
            .append(s)
            .compile
            .toList
            .asserting(it => assert(it == expected))
        }
      }

      "14b - interrupt evalMap+collect and then resume on append" in {
        forAll { s: Stream[Pure, Int] =>
          val expected = s.toList
          val interrupt = IO.sleep(50.millis).attempt
          s.covary[IO]
            .interruptWhen(interrupt)
            .evalMap(_ => IO.never.as(None))
            .append(s.map(Some(_)))
            .collect { case Some(v) => v }
            .compile
            .toList
            .asserting(it => assert(it == expected))
        }
      }

      "15 - interruption works when flatMap is followed by collect" in {
        forAll { s: Stream[Pure, Int] =>
          val expected = s.toList
          val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
          s.covary[IO]
            .append(Stream(1))
            .interruptWhen(interrupt)
            .map(_ => None)
            .append(s.map(Some(_)))
            .flatMap {
              case None    => Stream.eval(IO.never)
              case Some(i) => Stream.emit(Some(i))
            }
            .collect { case Some(i) => i }
            .compile
            .toList
            .asserting(it => assert(it == expected))
        }
      }

      "16 - if a pipe is interrupted, it will not restart evaluation" in {
        def p: Pipe[IO, Int, Int] = {
          def loop(acc: Int, s: Stream[IO, Int]): Pull[IO, Int, Unit] =
            s.pull.uncons1.flatMap {
              case None           => Pull.output1[IO, Int](acc)
              case Some((hd, tl)) => Pull.output1[IO, Int](hd) >> loop(acc + hd, tl)
            }
          in => loop(0, in).stream
        }
        Stream
          .unfold(0)(i => Some((i, i + 1)))
          .flatMap(Stream.emit(_).delayBy[IO](10.millis))
          .interruptWhen(Stream.emit(true).delayBy[IO](150.millis))
          .through(p)
          .compile
          .toList
          .asserting(result =>
            assert(result == (result.headOption.toList ++ result.tail.filter(_ != 0)))
          )
      }

      "17 - minimal resume on append with pull" in {
        val interrupt = IO.sleep(100.millis).attempt
        Stream(1)
          .covary[IO]
          .unchunk
          .interruptWhen(interrupt)
          .pull
          .uncons
          .flatMap {
            case None         => Pull.done
            case Some((_, _)) => Pull.eval(IO.never)
          }
          .stream
          .interruptScope
          .append(Stream(5))
          .compile
          .toList
          .asserting(it => assert(it == List(5)))
      }

      "18 - resume with append after evalMap interruption" in {
        Stream(1)
          .covary[IO]
          .interruptWhen(IO.sleep(50.millis).attempt)
          .evalMap(_ => IO.never)
          .append(Stream(5))
          .compile
          .toList
          .asserting(it => assert(it == List(5)))
      }

      "19 - interrupted eval is cancelled" in {
        Deferred[IO, Unit]
          .flatMap { latch =>
            Stream
              .eval(latch.get.guarantee(latch.complete(())))
              .interruptAfter(200.millis)
              .compile
              .drain >> latch.get.as(true)
          }
          .timeout(3.seconds)
          .assertNoException
      }

      "20 - nested-interrupt" in {
        forAll { s: Stream[Pure, Int] =>
          val expected = s.toList
          Stream
            .eval(Semaphore[IO](0))
            .flatMap { semaphore =>
              val interrupt = IO.sleep(50.millis).attempt
              val neverInterrupt = (IO.never: IO[Unit]).attempt
              s.covary[IO]
                .interruptWhen(interrupt)
                .as(None)
                .append(s.map(Option(_)))
                .interruptWhen(neverInterrupt)
                .flatMap {
                  case None    => Stream.eval(semaphore.acquire.as(None))
                  case Some(i) => Stream(Some(i))
                }
                .collect { case Some(i) => i }
            }
            .compile
            .toList
            .asserting(it => assert(it == expected))
        }
      }

      "21 - nested-interrupt - interrupt in outer scope interrupts the inner scope" in {
        Stream
          .eval(IO.async[Unit](_ => ()))
          .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
          .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
          .compile
          .toList
          .asserting(it => assert(it == Nil))
      }

      "22 - nested-interrupt - interrupt in enclosing scope recovers" in {
        Stream
          .eval(IO.async[Unit](_ => ()))
          .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
          .append(Stream(1).delayBy[IO](10.millis))
          .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
          .append(Stream(2))
          .compile
          .toList
          .asserting(it => assert(it == List(2)))
      }
    }

    "intersperse" in forAll { (s: Stream[Pure, Int], n: Int) =>
      assert(s.intersperse(n).toList == s.toList.flatMap(i => List(i, n)).dropRight(1))
    }

    "iterate" in {
      assert(Stream.iterate(0)(_ + 1).take(100).toList == List.iterate(0, 100)(_ + 1))
    }

    "iterateEval" in {
      Stream
        .iterateEval(0)(i => IO(i + 1))
        .take(100)
        .compile
        .toVector
        .asserting(it => assert(it == List.iterate(0, 100)(_ + 1)))
    }

    "last" in forAll { (s: Stream[Pure, Int]) =>
      val _ = s.last
      assert(s.last.toList == List(s.toList.lastOption))
    }

    "lastOr" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      assert(s.lastOr(n).toList == List(s.toList.lastOption.getOrElse(n)))
    }

    "map" - {
      "map.toList == toList.map" in forAll { (s: Stream[Pure, Int], f: Int => Int) =>
        assert(s.map(f).toList == s.toList.map(f))
      }

      "regression #1335 - stack safety of map" in {
        case class Tree[A](label: A, subForest: Stream[Pure, Tree[A]]) {
          def flatten: Stream[Pure, A] =
            Stream(this.label) ++ this.subForest.flatMap(_.flatten)
        }

        def unfoldTree(seed: Int): Tree[Int] =
          Tree(seed, Stream(seed + 1).map(unfoldTree))

        assert(unfoldTree(1).flatten.take(10).toList == List.tabulate(10)(_ + 1))
      }
    }

    "mapAccumulate" in forAll { (s: Stream[Pure, Int], m: Int, n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (_: Int) % n == 0
      val r = s.mapAccumulate(m)((s, i) => (s + i, f(i)))

      assert(r.map(_._1).toList == s.toList.scanLeft(m)(_ + _).tail)
      assert(r.map(_._2).toList == s.toList.map(f))
    }

    "mapAsync" - {
      "same as map" in forAll { s: Stream[Pure, Int] =>
        val f = (_: Int) + 1
        val r = s.covary[IO].mapAsync(16)(i => IO(f(i)))
        val sVector = s.toVector
        r.compile.toVector.asserting(it => assert(it == sVector.map(f)))
      }

      "exception" in forAll { s: Stream[Pure, Int] =>
        val f = (_: Int) => IO.raiseError[Int](new RuntimeException)
        val r = (s ++ Stream(1)).covary[IO].mapAsync(1)(f).attempt
        r.compile.toVector.asserting(it => assert(it.size == 1))
      }
    }

    "mapAsyncUnordered" in forAll { s: Stream[Pure, Int] =>
      val f = (_: Int) + 1
      val r = s.covary[IO].mapAsyncUnordered(16)(i => IO(f(i)))
      val sVector = s.toVector
      r.compile.toVector.asserting(it => assert(containSameElements(it, sVector.map(f))))
    }

    "mapChunks" in forAll { (s: Stream[Pure, Int]) =>
      assert(s.mapChunks(identity).chunks.toList == s.chunks.toList)
    }

    "merge" - {
      "basic" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        val expected = s1.toList.toSet ++ s2.toList.toSet
        s1.merge(s2.covary[IO])
          .compile
          .toList
          .asserting(result => assert(result.toSet == expected))
      }

      "left/right identity" - {
        "1" in forAll { (s1: Stream[Pure, Int]) =>
          val expected = s1.toList
          s1.covary[IO].merge(Stream.empty).compile.toList.asserting(it => assert(it == expected))
        }
        "2" in forAll { (s1: Stream[Pure, Int]) =>
          val expected = s1.toList
          Stream.empty.merge(s1.covary[IO]).compile.toList.asserting(it => assert(it == expected))
        }
      }

      "left/right failure" - {
        "1" in forAll { (s1: Stream[Pure, Int]) =>
          s1.covary[IO].merge(Stream.raiseError[IO](new Err)).compile.drain.assertThrows[Err]
        }

        "2 - never-ending flatMap, failure after emit" in forAll { (s1: Stream[Pure, Int]) =>
          s1.merge(Stream.raiseError[IO](new Err))
            .evalMap(_ => IO.never)
            .compile
            .drain
            .assertThrows[Err]
        }

        if (isJVM) {
          "3 - constant flatMap, failure after emit" in {
            forAll { (s1: Stream[Pure, Int]) =>
              s1.merge(Stream.raiseError[IO](new Err))
                .flatMap(_ => Stream.constant(true))
                .compile
                .drain
                .assertThrows[Err]
            }
          }
        }
      }

      "run finalizers of inner streams first" in forAll {
        (s: Stream[Pure, Int], leftBiased: Boolean) =>
          // tests that finalizers of inner stream are always run before outer finalizer
          // also this will test that when the either side throws an exception in finalizer it is caught
          val err = new Err
          Ref.of[IO, List[String]](Nil).flatMap { finalizerRef =>
            Ref.of[IO, (Boolean, Boolean)]((false, false)).flatMap { sideRunRef =>
              Deferred[IO, Unit].flatMap { halt =>
                def bracketed =
                  Stream.bracket(IO.unit)(_ => finalizerRef.update(_ :+ "Outer"))

                def register(side: String): IO[Unit] =
                  sideRunRef.update {
                    case (left, right) =>
                      if (side == "L") (true, right)
                      else (left, true)
                  }

                def finalizer(side: String): IO[Unit] =
                  // this introduces delay and failure based on bias of the test
                  if (leftBiased && side == "L")
                    IO.sleep(100.millis) >> finalizerRef.update(_ :+ s"Inner $side") >> IO
                      .raiseError(err)
                  else if (!leftBiased && side == "R")
                    IO.sleep(100.millis) >> finalizerRef.update(_ :+ s"Inner $side") >> IO
                      .raiseError(err)
                  else IO.sleep(50.millis) >> finalizerRef.update(_ :+ s"Inner $side")

                val prg0 =
                  bracketed
                    .flatMap { _ =>
                      (Stream.bracket(register("L"))(_ => finalizer("L")) >> s)
                        .merge(
                          Stream.bracket(register("R"))(_ => finalizer("R")) >>
                            Stream
                              .eval(halt.complete(())) // immediately interrupt the outer stream
                        )
                    }
                    .interruptWhen(halt.get.attempt)

                prg0.compile.drain.attempt.flatMap { r =>
                  finalizerRef.get.flatMap { finalizers =>
                    sideRunRef.get.flatMap {
                      case (left, right) =>
                        if (left && right) IO {
                          assert(
                            leftContainsAllOfRight(finalizers, List("Inner L", "Inner R", "Outer"))
                          )
                          assert(finalizers.lastOption == Some("Outer"))
                          assert(r == Left(err))
                        } else if (left) IO {
                          assert(finalizers == List("Inner L", "Outer"))
                          if (leftBiased) assert(r == Left(err))
                          else assert(r == Right(()))
                        } else if (right) IO {
                          assert(finalizers == List("Inner R", "Outer"))
                          if (!leftBiased) assert(r == Left(err))
                          else assert(r == Right(()))
                        } else
                          IO {
                            assert(finalizers == List("Outer"))
                            assert(r == Right(()))
                          }
                    }
                  }
                }
              }
            }
          }
      }

      "hangs" - {
        val full = if (isJVM) Stream.constant(42) else Stream.constant(42).evalTap(_ => IO.shift)
        val hang = Stream.repeatEval(IO.async[Unit](_ => ()))
        val hang2: Stream[IO, Nothing] = full.drain
        val hang3: Stream[IO, Nothing] =
          Stream
            .repeatEval[IO, Unit](IO.async[Unit](cb => cb(Right(()))) >> IO.shift)
            .drain

        "1" in { full.merge(hang).take(1).compile.toList.asserting(it => assert(it == List(42))) }
        "2" in { full.merge(hang2).take(1).compile.toList.asserting(it => assert(it == List(42))) }
        "3" in { full.merge(hang3).take(1).compile.toList.asserting(it => assert(it == List(42))) }
        "4" in { hang.merge(full).take(1).compile.toList.asserting(it => assert(it == List(42))) }
        "5" in { hang2.merge(full).take(1).compile.toList.asserting(it => assert(it == List(42))) }
        "6" in { hang3.merge(full).take(1).compile.toList.asserting(it => assert(it == List(42))) }
      }
    }

    "mergeHaltBoth" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      val s2List = s2.toList
      s1.covary[IO].map(Left(_)).mergeHaltBoth(s2.map(Right(_))).compile.toList.asserting {
        result =>
          assert(
            (result.collect { case Left(a)    => a } == s1List) ||
              (result.collect { case Right(a) => a } == s2List)
          )
      }
    }

    "mergeHaltL" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      s1.covary[IO].map(Left(_)).mergeHaltL(s2.map(Right(_))).compile.toList.asserting { result =>
        assert(result.collect { case Left(a) => a } == s1List)
      }
    }

    "mergeHaltR" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s2List = s2.toList
      s1.covary[IO].map(Left(_)).mergeHaltR(s2.map(Right(_))).compile.toList.asserting { result =>
        assert(result.collect { case Right(a) => a } == s2List)
      }
    }

    "observe/observeAsync" - {
      trait Observer {
        def apply[F[_]: Concurrent, O](s: Stream[F, O])(observation: Pipe[F, O, Unit]): Stream[F, O]
      }

      def observationTests(label: String, observer: Observer): Unit =
        label - {
          "basic functionality" in {
            forAll { (s: Stream[Pure, Int]) =>
              Ref
                .of[IO, Int](0)
                .flatMap { sum =>
                  val ints =
                    observer(s.covary[IO])(_.evalMap(i => sum.update(_ + i))).compile.toList
                  ints.flatMap(out => sum.get.map(out -> _))
                }
                .asserting {
                  case (out, sum) =>
                    assert(out.sum == sum)
                }
            }
          }

          "handle errors from observing sink" in {
            forAll { (s: Stream[Pure, Int]) =>
              observer(s.covary[IO])(_ => Stream.raiseError[IO](new Err)).attempt.compile.toList
                .asserting { result =>
                  assert(result.size == 1)
                  assert(
                    result.head
                      .fold(identity, r => fail(s"expected left but got Right($r)"))
                      .isInstanceOf[Err]
                  )
                }
            }
          }

          "propagate error from source" in {
            forAll { (s: Stream[Pure, Int]) =>
              observer(s.drain ++ Stream.raiseError[IO](new Err))(_.drain).attempt.compile.toList
                .asserting { result =>
                  assert(result.size == 1)
                  assert(
                    result.head
                      .fold(identity, r => fail(s"expected left but got Right($r)"))
                      .isInstanceOf[Err]
                  )
                }
            }
          }

          "handle finite observing sink" - {
            "1" in forAll { (s: Stream[Pure, Int]) =>
              observer(s.covary[IO])(_ => Stream.empty).compile.toList.asserting(it =>
                assert(it == Nil)
              )
            }
            "2" in forAll { (s: Stream[Pure, Int]) =>
              observer(Stream(1, 2) ++ s.covary[IO])(_.take(1).drain).compile.toList
                .asserting(it => assert(it == Nil))
            }
          }

          "handle multiple consecutive observations" in {
            forAll { (s: Stream[Pure, Int]) =>
              val expected = s.toList
              val sink: Pipe[IO, Int, Unit] = _.evalMap(_ => IO.unit)
              observer(observer(s.covary[IO])(sink))(sink).compile.toList
                .asserting(it => assert(it == expected))
            }
          }

          "no hangs on failures" in {
            forAll { (s: Stream[Pure, Int]) =>
              val sink: Pipe[IO, Int, Unit] =
                in => spuriousFail(in.evalMap(i => IO(i))).void
              val src: Stream[IO, Int] = spuriousFail(s.covary[IO])
              src.observe(sink).observe(sink).attempt.compile.drain.assertNoException
            }
          }
        }

      observationTests("observe", new Observer {
        def apply[F[_]: Concurrent, O](
            s: Stream[F, O]
        )(observation: Pipe[F, O, Unit]): Stream[F, O] =
          s.observe(observation)
      })

      observationTests(
        "observeAsync",
        new Observer {
          def apply[F[_]: Concurrent, O](
              s: Stream[F, O]
          )(observation: Pipe[F, O, Unit]): Stream[F, O] =
            s.observeAsync(maxQueued = 10)(observation)
        }
      )

      "observe" - {
        "not-eager" - {
          "1 - do not pull another element before we emit the current" in {
            Stream
              .eval(IO(1))
              .append(Stream.eval(IO.raiseError(new Err)))
              .observe(_.evalMap(_ => IO.sleep(100.millis))) //Have to do some work here, so that we give time for the underlying stream to try pull more
              .take(1)
              .compile
              .toList
              .asserting(it => assert(it == List(1)))
          }

          "2 - do not pull another element before downstream asks" in {
            Stream
              .eval(IO(1))
              .append(Stream.eval(IO.raiseError(new Err)))
              .observe(_.drain)
              .flatMap(_ => Stream.eval(IO.sleep(100.millis)) >> Stream(1, 2)) //Have to do some work here, so that we give time for the underlying stream to try pull more
              .take(2)
              .compile
              .toList
              .asserting(it => assert(it == List(1, 2)))
          }
        }
      }
    }

    "observeEither" - {
      val s = Stream.emits(Seq(Left(1), Right("a"))).repeat.covary[IO]

      "does not drop elements" in {
        val is = Ref.of[IO, Vector[Int]](Vector.empty)
        val as = Ref.of[IO, Vector[String]](Vector.empty)
        val test = for {
          iref <- is
          aref <- as
          iSink = (_: Stream[IO, Int]).evalMap(i => iref.update(_ :+ i))
          aSink = (_: Stream[IO, String]).evalMap(a => aref.update(_ :+ a))
          _ <- s.take(10).observeEither(iSink, aSink).compile.drain
          iResult <- iref.get
          aResult <- aref.get
        } yield {
          assert(iResult.length == 5)
          assert(aResult.length == 5)
        }
        test.assertNoException
      }

      "termination" - {
        "left" in {
          s.observeEither[Int, String](_.take(0).void, _.void)
            .compile
            .toList
            .asserting(r => assert(r.isEmpty))
        }

        "right" in {
          s.observeEither[Int, String](_.void, _.take(0).void)
            .compile
            .toList
            .asserting(r => assert(r.isEmpty))
        }
      }
    }

    "parJoin" - {
      "no concurrency" in forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList.toSet
        s.covary[IO]
          .map(Stream.emit(_).covary[IO])
          .parJoin(1)
          .compile
          .toList
          .asserting(it => assert(it.toSet == expected))
      }

      "concurrency" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val expected = s.toList.toSet
        s.covary[IO]
          .map(Stream.emit(_).covary[IO])
          .parJoin(n)
          .compile
          .toList
          .asserting(it => assert(it.toSet == expected))
      }

      "concurrent flattening" in forAll { (s: Stream[Pure, Stream[Pure, Int]], n0: PosInt) =>
        val n = n0 % 20 + 1
        val expected = s.flatten.toList.toSet
        s.map(_.covary[IO])
          .covary[IO]
          .parJoin(n)
          .compile
          .toList
          .asserting(it => assert(it.toSet == expected))
      }

      "merge consistency" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        val parJoined = Stream(s1.covary[IO], s2).parJoin(2).compile.toList.map(_.toSet)
        val merged = s1.covary[IO].merge(s2).compile.toList.map(_.toSet)
        (parJoined, merged).tupled.asserting { case (pj, m) => assert(pj == m) }
      }

      "resources acquired in outer stream are released after inner streams complete" in {
        val bracketed =
          Stream.bracket(IO(new java.util.concurrent.atomic.AtomicBoolean(true)))(b =>
            IO(b.set(false))
          )
        // Starts an inner stream which fails if the resource b is finalized
        val s: Stream[IO, Stream[IO, Unit]] = bracketed.map { b =>
          Stream
            .eval(IO(b.get))
            .flatMap(b => if (b) Stream(()) else Stream.raiseError[IO](new Err))
            .repeat
            .take(10000)
        }
        s.parJoinUnbounded.compile.drain.assertNoException
      }

      "run finalizers of inner streams first" in {
        forAll { (s1: Stream[Pure, Int], bias: Boolean) =>
          val err = new Err
          val biasIdx = if (bias) 1 else 0
          Ref
            .of[IO, List[String]](Nil)
            .flatMap { finalizerRef =>
              Ref.of[IO, List[Int]](Nil).flatMap { runEvidenceRef =>
                Deferred[IO, Unit].flatMap { halt =>
                  def bracketed =
                    Stream.bracket(IO.unit)(_ => finalizerRef.update(_ :+ "Outer"))

                  def registerRun(idx: Int): IO[Unit] =
                    runEvidenceRef.update(_ :+ idx)

                  def finalizer(idx: Int): IO[Unit] =
                    // this introduces delay and failure based on bias of the test
                    if (idx == biasIdx) {
                      IO.sleep(100.millis) >>
                        finalizerRef.update(_ :+ s"Inner $idx") >>
                        IO.raiseError(err)
                    } else {
                      finalizerRef.update(_ :+ s"Inner $idx")
                    }

                  val prg0 =
                    bracketed.flatMap { _ =>
                      Stream(
                        Stream.bracket(registerRun(0))(_ => finalizer(0)) >> s1,
                        Stream.bracket(registerRun(1))(_ => finalizer(1)) >> Stream
                          .eval_(halt.complete(()))
                      )
                    }

                  prg0.parJoinUnbounded.compile.drain.attempt.flatMap { r =>
                    finalizerRef.get.flatMap { finalizers =>
                      runEvidenceRef.get.flatMap { streamRunned =>
                        IO {
                          val expectedFinalizers = (streamRunned.map { idx =>
                            s"Inner $idx"
                          }) :+ "Outer"
                          assert(containSameElements(finalizers, expectedFinalizers))
                          assert(finalizers.lastOption == Some("Outer"))
                          if (streamRunned.contains(biasIdx)) assert(r == Left(err))
                          else assert(r == Right(()))
                        }
                      }
                    }
                  }
                }
              }
            }
            .assertNoException
        }
      }

      "hangs" - {
        val full = if (isJVM) Stream.constant(42) else Stream.constant(42).evalTap(_ => IO.shift)
        val hang = Stream.repeatEval(IO.async[Unit](_ => ()))
        val hang2: Stream[IO, Nothing] = full.drain
        val hang3: Stream[IO, Nothing] =
          Stream
            .repeatEval[IO, Unit](IO.async[Unit](cb => cb(Right(()))) >> IO.shift)
            .drain

        "1" in {
          Stream(full, hang)
            .parJoin(10)
            .take(1)
            .compile
            .toList
            .asserting(it => assert(it == List(42)))
        }
        "2" in {
          Stream(full, hang2)
            .parJoin(10)
            .take(1)
            .compile
            .toList
            .asserting(it => assert(it == List(42)))
        }
        "3" in {
          Stream(full, hang3)
            .parJoin(10)
            .take(1)
            .compile
            .toList
            .asserting(it => assert(it == List(42)))
        }
        "4" in {
          Stream(hang3, hang2, full)
            .parJoin(10)
            .take(1)
            .compile
            .toList
            .asserting(it => assert(it == List(42)))
        }
      }

      "outer failed" in {
        Stream(Stream.sleep_[IO](1.minute), Stream.raiseError[IO](new Err)).parJoinUnbounded.compile.drain
          .assertThrows[Err]
      }

      "propagate error from inner stream before ++" in {
        val err = new Err

        (Stream
          .emit(Stream.raiseError[IO](err))
          .parJoinUnbounded ++ Stream.emit(1)).compile.toList.attempt
          .asserting(it => assert(it == Left(err)))
      }
    }

    "pause" in {
      Stream
        .eval(SignallingRef[IO, Boolean](false))
        .flatMap { pause =>
          Stream
            .awakeEvery[IO](10.millis)
            .scan(0)((acc, _) => acc + 1)
            .evalMap { n =>
              if (n % 2 != 0)
                pause.set(true) >> ((Stream.sleep_[IO](10.millis) ++ Stream.eval(pause.set(false))).compile.drain).start >> IO
                  .pure(n)
              else IO.pure(n)
            }
            .take(5)
            .pauseWhen(pause)
        }
        .compile
        .toList
        .asserting(it => assert(it == List(0, 1, 2, 3, 4)))
    }

    "prefetch" - {
      "identity" in forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList
        s.covary[IO].prefetch.compile.toList.asserting(it => assert(it == expected))
      }

      "timing" in {
        // should finish in about 3-4 seconds
        IO.suspend {
          val start = System.currentTimeMillis
          Stream(1, 2, 3)
            .evalMap(i => IO.sleep(1.second).as(i))
            .prefetch
            .flatMap { i =>
              Stream.eval(IO.sleep(1.second).as(i))
            }
            .compile
            .toList
            .asserting { _ =>
              val stop = System.currentTimeMillis
              val elapsed = stop - start
              assert(elapsed < 6000L)
            }
        }
      }
    }

    "raiseError" - {
      "compiled stream fails with an error raised in stream" in {
        Stream.raiseError[SyncIO](new Err).compile.drain.assertThrows[Err]
      }

      "compiled stream fails with an error if error raised after an append" in {
        Stream
          .emit(1)
          .append(Stream.raiseError[IO](new Err))
          .covary[IO]
          .compile
          .drain
          .assertThrows[Err]
      }

      "compiled stream does not fail if stream is termianted before raiseError" in {
        Stream
          .emit(1)
          .append(Stream.raiseError[IO](new Err))
          .take(1)
          .covary[IO]
          .compile
          .drain
          .assertNoException
      }
    }

    "random" in {
      val x = Stream.random[SyncIO].take(100).compile.toList
      (x, x).tupled.asserting {
        case (first, second) =>
          assert(first != second)
      }
    }

    "randomSeeded" in {
      val x = Stream.randomSeeded(1L).take(100).toList
      val y = Stream.randomSeeded(1L).take(100).toList
      assert(x == y)
    }

    "range" in {
      assert(Stream.range(0, 100).toList == List.range(0, 100))
      assert(Stream.range(0, 1).toList == List.range(0, 1))
      assert(Stream.range(0, 0).toList == List.range(0, 0))
      assert(Stream.range(0, 101, 2).toList == List.range(0, 101, 2))
      assert(Stream.range(5, 0, -1).toList == List.range(5, 0, -1))
      assert(Stream.range(5, 0, 1).toList == Nil)
      assert(Stream.range(10, 50, 0).toList == Nil)
    }

    "ranges" in forAll(intsBetween(1, 101)) { size =>
      val result = Stream
        .ranges(0, 100, size)
        .flatMap { case (i, j) => Stream.emits(i until j) }
        .toVector
      assert(result == IndexedSeq.range(0, 100))
    }

    "rechunkRandomlyWithSeed" - {
      "is deterministic" in forAll { (s0: Stream[Pure, Int], seed: Long) =>
        def s = s0.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed)
        assert(s.toList == s.toList)
      }

      "does not drop elements" in forAll { (s: Stream[Pure, Int], seed: Long) =>
        assert(s.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed).toList == s.toList)
      }

      "chunk size in interval [inputChunk.size * minFactor, inputChunk.size * maxFactor]" in forAll {
        (s: Stream[Pure, Int], seed: Long) =>
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
          } else Succeeded
      }
    }

    "rechunkRandomly" in forAll { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      s.rechunkRandomly[IO]().compile.toList.asserting(it => assert(it == expected))
    }

    "repartition" in {
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

    "repeat" in {
      forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
        (n: Int, testValues: List[Int]) =>
          assert(
            Stream.emits(testValues).repeat.take(n).toList == List
              .fill(n / testValues.size + 1)(testValues)
              .flatten
              .take(n)
          )
      }
    }

    "repeatN" in {
      forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
        (n: Int, testValues: List[Int]) =>
          assert(Stream.emits(testValues).repeatN(n).toList == List.fill(n)(testValues).flatten)
      }
    }

    "resource" in {
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
        .asserting(it =>
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

    "resourceWeak" in {
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
        .asserting(it =>
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

    "resource safety" - {
      "1" in {
        forAll { (s1: Stream[Pure, Int]) =>
          Counter[IO].flatMap { counter =>
            val x = Stream.bracket(counter.increment)(_ => counter.decrement) >> s1
            val y = Stream.raiseError[IO](new Err)
            x.merge(y)
              .attempt
              .append(y.merge(x).attempt)
              .compile
              .drain
              .flatMap(_ => counter.get)
              .asserting(it => assert(it == 0L))
          }
        }
      }

      "2a" in {
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
            .asserting(it => assert(it == 0L))
            .repeatTest(25)
        }
      }

      "2b" in {
        forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
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
              .asserting(it => assert(it == 0L))
          }
        }
      }

      "3" in {
        pending // TODO: Sometimes fails with inner == 1 on final assertion
        forAll { (s: Stream[Pure, Stream[Pure, Int]], n0: PosInt) =>
          val n = n0 % 10 + 1
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
                .asserting(it => assert(it == 0L))
                .flatMap(_ => IO.sleep(50.millis)) // Allow time for inner stream to terminate
                .flatMap(_ => inner.get)
                .asserting(it => assert(it == 0L))
            }
          }
        }
      }

      "4" in forAll { (s: Stream[Pure, Int]) =>
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
            .asserting(it => assert(it == 0L))
        }
      }

      "5" in {
        forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
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
                .asserting(it => assert(it == 0L))
            }
          }
        }
      }

      "6" in {
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
              .asserting(it => assert(it == 0L))
          }
        }
      }
    }

    "retry" - {
      case class RetryErr(msg: String = "") extends RuntimeException(msg)

      "immediate success" in {
        IO.suspend {
          var attempts = 0
          val job = IO {
            attempts += 1
            "success"
          }
          Stream.retry(job, 1.seconds, x => x, 100).compile.toList.asserting { r =>
            assert(attempts == 1)
            assert(r == List("success"))
          }
        }
      }

      "eventual success" in {
        IO.suspend {
          var failures, successes = 0
          val job = IO {
            if (failures == 5) {
              successes += 1; "success"
            } else {
              failures += 1; throw RetryErr()
            }
          }
          Stream.retry(job, 100.millis, x => x, 100).compile.toList.asserting { r =>
            assert(failures == 5)
            assert(successes == 1)
            assert(r == List("success"))
          }
        }
      }

      "maxRetries" in {
        IO.suspend {
          var failures = 0
          val job = IO {
            failures += 1
            throw RetryErr(failures.toString)
          }
          Stream.retry(job, 100.millis, x => x, 5).compile.drain.attempt.asserting {
            case Left(RetryErr(msg)) =>
              assert(failures == 5)
              assert(msg == "5")
            case _ => fail("Expected a RetryErr")
          }
        }
      }

      "fatal" in {
        IO.suspend {
          var failures, successes = 0
          val job = IO {
            if (failures == 5) {
              failures += 1; throw RetryErr("fatal")
            } else if (failures > 5) {
              successes += 1; "success"
            } else {
              failures += 1; throw RetryErr()
            }
          }
          val f: Throwable => Boolean = _.getMessage != "fatal"
          Stream.retry(job, 100.millis, x => x, 100, f).compile.drain.attempt.asserting {
            case Left(RetryErr(msg)) =>
              assert(failures == 6)
              assert(successes == 0)
              assert(msg == "fatal")
            case _ => fail("Expected a RetryErr")
          }
        }
      }

      "delays" in {
        flickersOnTravis
        val delays = scala.collection.mutable.ListBuffer.empty[Long]
        val unit = 200
        val maxTries = 5
        def getDelays =
          delays
            .synchronized(delays.toList)
            .sliding(2)
            .map(s => (s.tail.head - s.head) / unit)
            .toList

        val job = {
          val start = System.currentTimeMillis()
          IO {
            delays.synchronized { delays += System.currentTimeMillis() - start }
            throw RetryErr()
          }
        }

        Stream.retry(job, unit.millis, _ + unit.millis, maxTries).compile.drain.attempt.asserting {
          case Left(RetryErr(_)) =>
            assert(getDelays == List.range(1, maxTries))
          case _ => fail("Expected a RetryErr")
        }
      }
    }

    "scan" - {
      "1" in forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        assert(s.scan(n)(f).toList == s.toList.scanLeft(n)(f))
      }

      "2" in {
        val s = Stream(1).map(x => x)
        val f = (a: Int, b: Int) => a + b
        assert(s.scan(0)(f).toList == s.toList.scanLeft(0)(f))
      }

      "temporal" in {
        val never = Stream.eval(IO.async[Int](_ => ()))
        val s = Stream(1)
        val f = (a: Int, b: Int) => a + b
        val result = s.toList.scanLeft(0)(f)
        s.append(never)
          .scan(0)(f)
          .take(result.size)
          .compile
          .toList
          .asserting(it => assert(it == result))
      }
    }

    "scan1" in forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      assert(
        s.scan1(f).toVector == v.headOption.fold(Vector.empty[Int])(h => v.drop(1).scanLeft(h)(f))
      )
    }

    "scope" - {
      "1" in {
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
          .asserting(_ => assert(c.get == 0L))
      }

      "2" in {
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
          .asserting(it => assert(it == List(1, 1, 0)))
      }
    }

    "sleep" in {
      val delay = 200.millis
      // force a sync up in duration, then measure how long sleep takes
      val emitAndSleep = Stream(()) ++ Stream.sleep[IO](delay)
      emitAndSleep
        .zip(Stream.duration[IO])
        .drop(1)
        .map(_._2)
        .compile
        .toList
        .asserting(result => assert(result.head >= (delay * 0.95))) // Allow for sleep starting just before duration measurement
    }

    "sliding" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      assert(
        s.sliding(n).toList.map(_.toList) == s.toList
          .sliding(n)
          .map(_.toList)
          .toList
      )
    }

    "split" - {
      "1" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val s2 = s
          .map(x => if (x == Int.MinValue) x + 1 else x)
          .map(_.abs)
          .filter(_ != 0)
        withClue(s"n = $n, s = ${s.toList}, s2 = " + s2.toList) {
          assert(
            s2.chunkLimit(n)
              .intersperse(Chunk.singleton(0))
              .flatMap(Stream.chunk)
              .split(_ == 0)
              .map(_.toVector)
              .filter(_.nonEmpty)
              .toVector == s2.chunkLimit(n).filter(_.nonEmpty).map(_.toVector).toVector
          )
        }
      }

      "2" in {
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

    "switchMap" - {
      "flatMap equivalence when switching never occurs" in forAll { s: Stream[Pure, Int] =>
        val expected = s.toList
        Stream
          .eval(Semaphore[IO](1))
          .flatMap { guard =>
            s.covary[IO]
              .evalTap(_ => guard.acquire) // wait for inner to emit to prevent switching
              .onFinalize(guard.acquire) // outer terminates, wait for last inner to emit
              .switchMap(x => Stream.emit(x).onFinalize(guard.release))
          }
          .compile
          .toList
          .asserting(it => assert(it == expected))
      }

      "inner stream finalizer always runs before switching" in {
        forAll { s: Stream[Pure, Int] =>
          Stream
            .eval(Ref[IO].of(true))
            .flatMap { ref =>
              s.covary[IO].switchMap { _ =>
                Stream.eval(ref.get).flatMap { released =>
                  if (!released) Stream.raiseError[IO](new Err)
                  else
                    Stream
                      .eval(ref.set(false) >> IO.sleep(20.millis))
                      .onFinalize(IO.sleep(100.millis) >> ref.set(true))
                }
              }
            }
            .compile
            .drain
            .assertNoException
        }
      }

      "when primary stream terminates, inner stream continues" in forAll {
        (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
          val expected = s1.last.unNoneTerminate.flatMap(s => s2 ++ Stream(s)).toList
          s1.covary[IO]
            .switchMap(s => Stream.sleep_[IO](25.millis) ++ s2 ++ Stream.emit(s))
            .compile
            .toList
            .asserting(it => assert(it == expected))
      }

      "when inner stream fails, overall stream fails" in forAll { (s0: Stream[Pure, Int]) =>
        val s = Stream(0) ++ s0
        s.delayBy[IO](25.millis)
          .switchMap(_ => Stream.raiseError[IO](new Err))
          .compile
          .drain
          .assertThrows[Err]
      }

      "when primary stream fails, overall stream fails and inner stream is terminated" in {
        Stream
          .eval(Semaphore[IO](0))
          .flatMap { semaphore =>
            Stream(0)
              .append(Stream.raiseError[IO](new Err).delayBy(10.millis))
              .switchMap(_ =>
                Stream.repeatEval(IO(1) *> IO.sleep(10.millis)).onFinalize(semaphore.release)
              )
              .onFinalize(semaphore.acquire)
          }
          .compile
          .drain
          .assertThrows[Err]
      }

      "when inner stream fails, inner stream finalizer run before the primary one" in {
        forAll { (s0: Stream[Pure, Int]) =>
          val s = Stream(0) ++ s0
          Stream
            .eval(Deferred[IO, Boolean])
            .flatMap { verdict =>
              Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
                s.delayBy[IO](25.millis)
                  .onFinalize(innerReleased.get.flatMap(inner => verdict.complete(inner)))
                  .switchMap(_ => Stream.raiseError[IO](new Err).onFinalize(innerReleased.set(true))
                  )
                  .attempt
                  .drain ++
                  Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO.unit))
              }
            }
            .compile
            .drain
            .assertThrows[Err]
        }
      }

      "when primary stream fails, inner stream finalizer run before the primary one" in {
        if (isJVM) flickersOnTravis else pending
        Stream
          .eval(Ref[IO].of(false))
          .flatMap { verdict =>
            Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
              // TODO ideally make sure the inner stream has actually started
              (Stream(1) ++ Stream.sleep_[IO](25.millis) ++ Stream.raiseError[IO](new Err))
                .onFinalize(innerReleased.get.flatMap(inner => verdict.set(inner)))
                .switchMap(_ => Stream.repeatEval(IO(1)).onFinalize(innerReleased.set(true)))
                .attempt
                .drain ++
                Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO(())))
            }
          }
          .compile
          .drain
          .assertThrows[Err]
      }
    }

    "tail" in forAll { (s: Stream[Pure, Int]) =>
      assert(s.tail.toList == s.toList.drop(1))
    }

    "take" - {
      "identity" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosInt) =>
        val n1 = n0 % 20 + 1
        val n = if (negate) -n1 else n1
        assert(s.take(n).toList == s.toList.take(n))
      }
      "chunks" in {
        val s = Stream(1, 2) ++ Stream(3, 4)
        assert(s.take(3).chunks.map(_.toList).toList == List(List(1, 2), List(3)))
      }
    }

    "takeRight" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosInt) =>
      val n1 = n0 % 20 + 1
      val n = if (negate) -n1 else n1
      assert(s.takeRight(n).toList == s.toList.takeRight(n))
    }

    "takeWhile" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val set = s.toList.take(n).toSet
      assert(s.takeWhile(set).toList == s.toList.takeWhile(set))
    }

    "takeThrough" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (i: Int) => i % n == 0
      val vec = s.toVector
      val result = vec.takeWhile(f) ++ vec.dropWhile(f).headOption
      assert(withClue(vec)(s.takeThrough(f).toVector == result))
    }

    "translate" - {
      "1 - id" in forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList
        s.covary[SyncIO]
          .flatMap(i => Stream.eval(SyncIO.pure(i)))
          .translate(cats.arrow.FunctionK.id[SyncIO])
          .compile
          .toList
          .asserting(it => assert(it == expected))
      }

      "2" in forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList
        s.covary[Function0]
          .flatMap(i => Stream.eval(() => i))
          .flatMap(i => Stream.eval(() => i))
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(it => assert(it == expected))
      }

      "3 - ok to have multiple translates" in forAll { (s: Stream[Pure, Int]) =>
        val expected = s.toList
        s.covary[Function0]
          .flatMap(i => Stream.eval(() => i))
          .flatMap(i => Stream.eval(() => i))
          .translate(new (Function0 ~> Some) {
            def apply[A](thunk: Function0[A]) = Some(thunk())
          })
          .flatMap(i => Stream.eval(Some(i)))
          .flatMap(i => Stream.eval(Some(i)))
          .translate(new (Some ~> SyncIO) {
            def apply[A](some: Some[A]) = SyncIO(some.get)
          })
          .compile
          .toList
          .asserting(it => assert(it == expected))
      }

      "4 - ok to translate after zip with effects" in {
        val stream: Stream[Function0, Int] =
          Stream.eval(() => 1)
        stream
          .zip(stream)
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(it => assert(it == List((1, 1))))
      }

      "5 - ok to translate a step leg that emits multiple chunks" in {
        def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
          step match {
            case None       => Pull.done
            case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
          }
        (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
          .flatMap(goStep)
          .stream
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(it => assert(it == List(1, 2)))
      }

      "6 - ok to translate step leg that has uncons in its structure" in {
        def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
          step match {
            case None       => Pull.done
            case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
          }
        (Stream.eval(() => 1) ++ Stream.eval(() => 2))
          .flatMap { a =>
            Stream.emit(a)
          }
          .flatMap { a =>
            Stream.eval(() => a + 1) ++ Stream.eval(() => a + 2)
          }
          .pull
          .stepLeg
          .flatMap(goStep)
          .stream
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(it => assert(it == List(2, 3, 3, 4)))
      }

      "7 - ok to translate step leg that is forced back in to a stream" in {
        def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
          step match {
            case None => Pull.done
            case Some(step) =>
              Pull.output(step.head) >> step.stream.pull.echo
          }
        (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
          .flatMap(goStep)
          .stream
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(it => assert(it == List(1, 2)))
      }

      "stack safety" in {
        Stream
          .repeatEval(SyncIO(0))
          .translate(new (SyncIO ~> SyncIO) { def apply[X](x: SyncIO[X]) = SyncIO.suspend(x) })
          .take(if (isJVM) 1000000 else 10000)
          .compile
          .drain
          .assertNoException
      }
    }

    "translateInterruptible" in {
      Stream
        .eval(IO.never)
        .merge(Stream.eval(IO(1)).delayBy(5.millis).repeat)
        .interruptAfter(10.millis)
        .translateInterruptible(cats.arrow.FunctionK.id[IO])
        .compile
        .drain
        .assertNoException
    }

    "unfold" in {
      assert(
        Stream
          .unfold((0, 1)) {
            case (f1, f2) =>
              if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
          }
          .map(_._1)
          .toList == List(0, 1, 1, 2, 3, 5, 8, 13)
      )
    }

    "unfoldChunk" in {
      assert(
        Stream
          .unfoldChunk(4L) { s =>
            if (s > 0) Some((Chunk.longs(Array[Long](s, s)), s - 1))
            else None
          }
          .toList == List[Long](4, 4, 3, 3, 2, 2, 1, 1)
      )
    }

    "unfoldEval" in {
      Stream
        .unfoldEval(10)(s => IO.pure(if (s > 0) Some((s, s - 1)) else None))
        .compile
        .toList
        .asserting(it => assert(it == List.range(10, 0, -1)))
    }

    "unfoldChunkEval" in {
      Stream
        .unfoldChunkEval(true)(s =>
          SyncIO.pure(
            if (s) Some((Chunk.booleans(Array[Boolean](s)), false))
            else None
          )
        )
        .compile
        .toList
        .asserting(it => assert(it == List(true)))
    }

    "unNone" in forAll { (s: Stream[Pure, Option[Int]]) =>
      assert(s.unNone.chunks.toList == s.filter(_.isDefined).map(_.get).chunks.toList)
    }

    "zip" - {
      "propagate error from closing the root scope" in {
        val s1 = Stream.bracket(IO(1))(_ => IO.unit)
        val s2 = Stream.bracket(IO("a"))(_ => IO.raiseError(new Err))

        val r1 = s1.zip(s2).compile.drain.attempt.unsafeRunSync()
        assert(r1.fold(identity, r => fail(s"expected left but got Right($r)")).isInstanceOf[Err])
        val r2 = s2.zip(s1).compile.drain.attempt.unsafeRunSync()
        assert(r2.fold(identity, r => fail(s"expected left but got Right($r)")).isInstanceOf[Err])
      }

      "issue #941 - scope closure issue" in {
        assert(
          Stream(1, 2, 3)
            .map(_ + 1)
            .repeat
            .zip(Stream(4, 5, 6).map(_ + 1).repeat)
            .take(4)
            .toList == List((2, 5), (3, 6), (4, 7), (2, 5))
        )
      }

      "zipWith left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        assert(ones.zipWith(s)(_ + _).toList == List("1A", "1B", "1C"))
        assert(s.zipWith(ones)(_ + _).toList == List("A1", "B1", "C1"))
      }

      "zipWith both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assert(ones.zipWith(as)(_ + _).take(3).toList == List("1A", "1A", "1A"))
        assert(as.zipWith(ones)(_ + _).take(3).toList == List("A1", "A1", "A1"))
      }

      "zipAllWith left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        assert(
          ones.zipAllWith(s)("2", "Z")(_ + _).take(5).toList ==
            List("1A", "1B", "1C", "1Z", "1Z")
        )
        assert(
          s.zipAllWith(ones)("Z", "2")(_ + _).take(5).toList ==
            List("A1", "B1", "C1", "Z1", "Z1")
        )
      }

      "zipAllWith both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assert(
          ones.zipAllWith(as)("2", "Z")(_ + _).take(3).toList ==
            List("1A", "1A", "1A")
        )
        assert(
          as.zipAllWith(ones)("Z", "2")(_ + _).take(3).toList ==
            List("A1", "A1", "A1")
        )
      }

      "zip left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        assert(ones.zip(s).toList == List("1" -> "A", "1" -> "B", "1" -> "C"))
        assert(s.zip(ones).toList == List("A" -> "1", "B" -> "1", "C" -> "1"))
      }

      "zip both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assert(ones.zip(as).take(3).toList == List("1" -> "A", "1" -> "A", "1" -> "A"))
        assert(as.zip(ones).take(3).toList == List("A" -> "1", "A" -> "1", "A" -> "1"))
      }

      "zipAll left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        assert(
          ones.zipAll(s)("2", "Z").take(5).toList == List(
            "1" -> "A",
            "1" -> "B",
            "1" -> "C",
            "1" -> "Z",
            "1" -> "Z"
          )
        )
        assert(
          s.zipAll(ones)("Z", "2").take(5).toList == List(
            "A" -> "1",
            "B" -> "1",
            "C" -> "1",
            "Z" -> "1",
            "Z" -> "1"
          )
        )
      }

      "zipAll both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        assert(ones.zipAll(as)("2", "Z").take(3).toList == List("1" -> "A", "1" -> "A", "1" -> "A"))
        assert(as.zipAll(ones)("Z", "2").take(3).toList == List("A" -> "1", "A" -> "1", "A" -> "1"))
      }

      "zip with scopes" - {
        "1" in {
          // this tests that streams opening resources on each branch will close
          // scopes independently.
          val s = Stream(0).scope
          assert((s ++ s).zip(s).toList == List((0, 0)))
        }
        def brokenZip[F[_], A, B](s1: Stream[F, A], s2: Stream[F, B]): Stream[F, (A, B)] = {
          def go(s1: Stream[F, A], s2: Stream[F, B]): Pull[F, (A, B), Unit] =
            s1.pull.uncons1.flatMap {
              case Some((hd1, tl1)) =>
                s2.pull.uncons1.flatMap {
                  case Some((hd2, tl2)) =>
                    Pull.output1((hd1, hd2)) >> go(tl1, tl2)
                  case None => Pull.done
                }
              case None => Pull.done
            }
          go(s1, s2).stream
        }
        "2" in {
          val s = Stream(0).scope
          assertThrows[Throwable] { brokenZip(s ++ s, s.zip(s)).compile.toList }
        }
        "3" in {
          Logger[IO]
            .flatMap { logger =>
              def s(tag: String) =
                logger.logLifecycle(tag) >> (logger.logLifecycle(s"$tag - 1") ++ logger
                  .logLifecycle(s"$tag - 2"))
              s("a").zip(s("b")).compile.drain *> logger.get
            }
            .asserting { it =>
              assert(
                it == List(
                  LogEvent.Acquired("a"),
                  LogEvent.Acquired("a - 1"),
                  LogEvent.Acquired("b"),
                  LogEvent.Acquired("b - 1"),
                  LogEvent.Released("a - 1"),
                  LogEvent.Acquired("a - 2"),
                  LogEvent.Released("b - 1"),
                  LogEvent.Acquired("b - 2"),
                  LogEvent.Released("a - 2"),
                  LogEvent.Released("a"),
                  LogEvent.Released("b - 2"),
                  LogEvent.Released("b")
                )
              )
            }
        }
      }

      "issue #1120 - zip with uncons" in {
        // this tests we can properly look up scopes for the zipped streams
        val rangeStream = Stream.emits((0 to 3).toList)
        assert(
          rangeStream.zip(rangeStream).attempt.map(identity).toVector == Vector(
            Right((0, 0)),
            Right((1, 1)),
            Right((2, 2)),
            Right((3, 3))
          )
        )
      }
    }

    "zipWithIndex" in forAll { (s: Stream[Pure, Int]) =>
      assert(s.zipWithIndex.toList == s.toList.zipWithIndex)
    }

    "zipWithNext" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        assert(s.zipWithNext.toList == {
          val xs = s.toList
          xs.zipAll(xs.map(Some(_)).drop(1), -1, None)
        })
      }

      "2" in {
        assert(Stream().zipWithNext.toList == Nil)
        assert(Stream(0).zipWithNext.toList == List((0, None)))
        assert(Stream(0, 1, 2).zipWithNext.toList == List((0, Some(1)), (1, Some(2)), (2, None)))
      }
    }

    "zipWithPrevious" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        assert(s.zipWithPrevious.toList == {
          val xs = s.toList
          (None +: xs.map(Some(_))).zip(xs)
        })
      }

      "2" in {
        assert(Stream().zipWithPrevious.toList == Nil)
        assert(Stream(0).zipWithPrevious.toList == List((None, 0)))
        assert(
          Stream(0, 1, 2).zipWithPrevious.toList == List((None, 0), (Some(0), 1), (Some(1), 2))
        )
      }
    }

    "zipWithPreviousAndNext" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        assert(s.zipWithPreviousAndNext.toList == {
          val xs = s.toList
          val zipWithPrevious = (None +: xs.map(Some(_))).zip(xs)
          val zipWithPreviousAndNext = zipWithPrevious
            .zipAll(xs.map(Some(_)).drop(1), (None, -1), None)
            .map { case ((prev, that), next) => (prev, that, next) }
          zipWithPreviousAndNext
        })
      }

      "2" in {
        assert(Stream().zipWithPreviousAndNext.toList == Nil)
        assert(Stream(0).zipWithPreviousAndNext.toList == List((None, 0, None)))
        assert(
          Stream(0, 1, 2).zipWithPreviousAndNext.toList == List(
            (None, 0, Some(1)),
            (Some(0), 1, Some(2)),
            (Some(1), 2, None)
          )
        )
      }
    }

    "zipWithScan" in {
      assert(
        Stream("uno", "dos", "tres", "cuatro")
          .zipWithScan(0)(_ + _.length)
          .toList == List("uno" -> 0, "dos" -> 3, "tres" -> 6, "cuatro" -> 10)
      )
      assert(Stream().zipWithScan(())((_, _) => ???).toList == Nil)
    }

    "zipWithScan1" in {
      assert(
        Stream("uno", "dos", "tres", "cuatro")
          .zipWithScan1(0)(_ + _.length)
          .toList == List("uno" -> 3, "dos" -> 6, "tres" -> 10, "cuatro" -> 16)
      )
      assert(Stream().zipWithScan1(())((_, _) => ???).toList == Nil)
    }

    "regressions" - {
      "#1089" in {
        (Stream.chunk(Chunk.bytes(Array.fill(2000)(1.toByte))) ++ Stream.eval(
          IO.async[Byte](_ => ())
        )).take(2000).chunks.compile.toVector.assertNoException
      }

      "#1107 - scope" in {
        Stream(0)
          .covary[IO]
          .scope
          .repeat
          .take(10000)
          .flatMap(_ => Stream.empty) // Never emit an element downstream
          .mapChunks(identity) // Use a combinator that calls Stream#pull.uncons
          .compile
          .drain
          .assertNoException
      }

      "#1107 - queue" in {
        Stream
          .range(0, 10000)
          .covary[IO]
          .unchunk
          .prefetch
          .flatMap(_ => Stream.empty)
          .mapChunks(identity)
          .compile
          .drain
          .assertNoException
      }
    }
  }
}
