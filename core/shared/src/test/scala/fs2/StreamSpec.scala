// package fs2

// import cats.~>
// import cats.data._
// import cats.data.Chain
// import cats.effect._
// import cats.effect.concurrent.{Deferred, Ref, Semaphore}
// import cats.effect.laws.util.TestContext
// import cats.implicits._
// import scala.concurrent.duration._
// import scala.concurrent.TimeoutException
// import org.scalactic.anyvals._
// import org.scalatest.{Assertion, Succeeded}
// import fs2.concurrent.{Queue, SignallingRef}

// class StreamSpec extends Fs2Spec {

//     "parJoin" - {
//       "no concurrency" in forAll { (s: Stream[Pure, Int]) =>
//         val expected = s.toList.toSet
//         s.covary[IO]
//           .map(Stream.emit(_).covary[IO])
//           .parJoin(1)
//           .compile
//           .toList
//           .asserting(it => assert(it.toSet == expected))
//       }

//       "concurrency" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
//         val n = n0 % 20 + 1
//         val expected = s.toList.toSet
//         s.covary[IO]
//           .map(Stream.emit(_).covary[IO])
//           .parJoin(n)
//           .compile
//           .toList
//           .asserting(it => assert(it.toSet == expected))
//       }

//       "concurrent flattening" in forAll { (s: Stream[Pure, Stream[Pure, Int]], n0: PosInt) =>
//         val n = n0 % 20 + 1
//         val expected = s.flatten.toList.toSet
//         s.map(_.covary[IO])
//           .covary[IO]
//           .parJoin(n)
//           .compile
//           .toList
//           .asserting(it => assert(it.toSet == expected))
//       }

//       "merge consistency" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
//         val parJoined = Stream(s1.covary[IO], s2).parJoin(2).compile.toList.map(_.toSet)
//         val merged = s1.covary[IO].merge(s2).compile.toList.map(_.toSet)
//         (parJoined, merged).tupled.asserting { case (pj, m) => assert(pj == m) }
//       }

//       "resources acquired in outer stream are released after inner streams complete" in {
//         val bracketed =
//           Stream.bracket(IO(new java.util.concurrent.atomic.AtomicBoolean(true)))(b =>
//             IO(b.set(false))
//           )
//         // Starts an inner stream which fails if the resource b is finalized
//         val s: Stream[IO, Stream[IO, Unit]] = bracketed.map { b =>
//           Stream
//             .eval(IO(b.get))
//             .flatMap(b => if (b) Stream(()) else Stream.raiseError[IO](new Err))
//             .repeat
//             .take(10000)
//         }
//         s.parJoinUnbounded.compile.drain.assertNoException
//       }

//       "run finalizers of inner streams first" in {
//         forAll { (s1: Stream[Pure, Int], bias: Boolean) =>
//           val err = new Err
//           val biasIdx = if (bias) 1 else 0
//           Ref
//             .of[IO, List[String]](Nil)
//             .flatMap { finalizerRef =>
//               Ref.of[IO, List[Int]](Nil).flatMap { runEvidenceRef =>
//                 Deferred[IO, Unit].flatMap { halt =>
//                   def bracketed =
//                     Stream.bracket(IO.unit)(_ => finalizerRef.update(_ :+ "Outer"))

//                   def registerRun(idx: Int): IO[Unit] =
//                     runEvidenceRef.update(_ :+ idx)

//                   def finalizer(idx: Int): IO[Unit] =
//                     // this introduces delay and failure based on bias of the test
//                     if (idx == biasIdx)
//                       IO.sleep(100.millis) >>
//                         finalizerRef.update(_ :+ s"Inner $idx") >>
//                         IO.raiseError(err)
//                     else
//                       finalizerRef.update(_ :+ s"Inner $idx")

//                   val prg0 =
//                     bracketed.flatMap { _ =>
//                       Stream(
//                         Stream.bracket(registerRun(0))(_ => finalizer(0)) >> s1,
//                         Stream.bracket(registerRun(1))(_ => finalizer(1)) >> Stream
//                           .eval_(halt.complete(()))
//                       )
//                     }

//                   prg0.parJoinUnbounded.compile.drain.attempt.flatMap { r =>
//                     finalizerRef.get.flatMap { finalizers =>
//                       runEvidenceRef.get.flatMap { streamRunned =>
//                         IO {
//                           val expectedFinalizers = streamRunned.map { idx =>
//                             s"Inner $idx"
//                           } :+ "Outer"
//                           assert(containSameElements(finalizers, expectedFinalizers))
//                           assert(finalizers.lastOption == Some("Outer"))
//                           if (streamRunned.contains(biasIdx)) assert(r == Left(err))
//                           else assert(r == Right(()))
//                         }
//                       }
//                     }
//                   }
//                 }
//               }
//             }
//             .assertNoException
//         }
//       }

//       "hangs" - {
//         val full = if (isJVM) Stream.constant(42) else Stream.constant(42).evalTap(_ => IO.shift)
//         val hang = Stream.repeatEval(IO.async[Unit](_ => ()))
//         val hang2: Stream[IO, Nothing] = full.drain
//         val hang3: Stream[IO, Nothing] =
//           Stream
//             .repeatEval[IO, Unit](IO.async[Unit](cb => cb(Right(()))) >> IO.shift)
//             .drain

//         "1" in {
//           Stream(full, hang)
//             .parJoin(10)
//             .take(1)
//             .compile
//             .toList
//             .asserting(it => assert(it == List(42)))
//         }
//         "2" in {
//           Stream(full, hang2)
//             .parJoin(10)
//             .take(1)
//             .compile
//             .toList
//             .asserting(it => assert(it == List(42)))
//         }
//         "3" in {
//           Stream(full, hang3)
//             .parJoin(10)
//             .take(1)
//             .compile
//             .toList
//             .asserting(it => assert(it == List(42)))
//         }
//         "4" in {
//           Stream(hang3, hang2, full)
//             .parJoin(10)
//             .take(1)
//             .compile
//             .toList
//             .asserting(it => assert(it == List(42)))
//         }
//       }

//       "outer failed" in {
//         Stream(
//           Stream.sleep_[IO](1.minute),
//           Stream.raiseError[IO](new Err)
//         ).parJoinUnbounded.compile.drain
//           .assertThrows[Err]
//       }

//       "propagate error from inner stream before ++" in {
//         val err = new Err

//         (Stream
//           .emit(Stream.raiseError[IO](err))
//           .parJoinUnbounded ++ Stream.emit(1)).compile.toList.attempt
//           .asserting(it => assert(it == Left(err)))
//       }
//     }

//     "pause" in {
//       Stream
//         .eval(SignallingRef[IO, Boolean](false))
//         .flatMap { pause =>
//           Stream
//             .awakeEvery[IO](10.millis)
//             .scan(0)((acc, _) => acc + 1)
//             .evalMap { n =>
//               if (n % 2 != 0)
//                 pause.set(true) >> ((Stream.sleep_[IO](10.millis) ++ Stream.eval(
//                   pause.set(false)
//                 )).compile.drain).start >> IO
//                   .pure(n)
//               else IO.pure(n)
//             }
//             .take(5)
//             .pauseWhen(pause)
//         }
//         .compile
//         .toList
//         .asserting(it => assert(it == List(0, 1, 2, 3, 4)))
//     }

//     "prefetch" - {
//       "identity" in forAll { (s: Stream[Pure, Int]) =>
//         val expected = s.toList
//         s.covary[IO].prefetch.compile.toList.asserting(it => assert(it == expected))
//       }

//       "timing" in {
//         // should finish in about 3-4 seconds
//         IO.suspend {
//           val start = System.currentTimeMillis
//           Stream(1, 2, 3)
//             .evalMap(i => IO.sleep(1.second).as(i))
//             .prefetch
//             .flatMap(i => Stream.eval(IO.sleep(1.second).as(i)))
//             .compile
//             .toList
//             .asserting { _ =>
//               val stop = System.currentTimeMillis
//               val elapsed = stop - start
//               assert(elapsed < 6000L)
//             }
//         }
//       }
//     }

//     "raiseError" - {
//       "compiled stream fails with an error raised in stream" in {
//         Stream.raiseError[SyncIO](new Err).compile.drain.assertThrows[Err]
//       }

//       "compiled stream fails with an error if error raised after an append" in {
//         Stream
//           .emit(1)
//           .append(Stream.raiseError[IO](new Err))
//           .covary[IO]
//           .compile
//           .drain
//           .assertThrows[Err]
//       }

//       "compiled stream does not fail if stream is termianted before raiseError" in {
//         Stream
//           .emit(1)
//           .append(Stream.raiseError[IO](new Err))
//           .take(1)
//           .covary[IO]
//           .compile
//           .drain
//           .assertNoException
//       }
//     }

//     "random" in {
//       val x = Stream.random[SyncIO].take(100).compile.toList
//       (x, x).tupled.asserting {
//         case (first, second) =>
//           assert(first != second)
//       }
//     }

//     "randomSeeded" in {
//       val x = Stream.randomSeeded(1L).take(100).toList
//       val y = Stream.randomSeeded(1L).take(100).toList
//       assert(x == y)
//     }

//     "range" in {
//       assert(Stream.range(0, 100).toList == List.range(0, 100))
//       assert(Stream.range(0, 1).toList == List.range(0, 1))
//       assert(Stream.range(0, 0).toList == List.range(0, 0))
//       assert(Stream.range(0, 101, 2).toList == List.range(0, 101, 2))
//       assert(Stream.range(5, 0, -1).toList == List.range(5, 0, -1))
//       assert(Stream.range(5, 0, 1).toList == Nil)
//       assert(Stream.range(10, 50, 0).toList == Nil)
//     }

//     "ranges" in forAll(intsBetween(1, 101)) { size =>
//       val result = Stream
//         .ranges(0, 100, size)
//         .flatMap { case (i, j) => Stream.emits(i until j) }
//         .toVector
//       assert(result == IndexedSeq.range(0, 100))
//     }

//     "rechunkRandomlyWithSeed" - {
//       "is deterministic" in forAll { (s0: Stream[Pure, Int], seed: Long) =>
//         def s = s0.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed)
//         assert(s.toList == s.toList)
//       }

//       "does not drop elements" in forAll { (s: Stream[Pure, Int], seed: Long) =>
//         assert(s.rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed).toList == s.toList)
//       }

//       "chunk size in interval [inputChunk.size * minFactor, inputChunk.size * maxFactor]" in forAll {
//         (s: Stream[Pure, Int], seed: Long) =>
//           val c = s.chunks.toVector
//           if (c.nonEmpty) {
//             val (min, max) = c.tail.foldLeft(c.head.size -> c.head.size) {
//               case ((min, max), c) => Math.min(min, c.size) -> Math.max(max, c.size)
//             }
//             val (minChunkSize, maxChunkSize) = (min * 0.1, max * 2.0)
//             // Last element is dropped as it may not fulfill size constraint
//             val isChunkSizeCorrect = s
//               .rechunkRandomlyWithSeed(minFactor = 0.1, maxFactor = 2.0)(seed)
//               .chunks
//               .map(_.size)
//               .toVector
//               .dropRight(1)
//               .forall { it =>
//                 it >= minChunkSize.toInt &&
//                 it <= maxChunkSize.toInt
//               }
//             assert(isChunkSizeCorrect)
//           } else Succeeded
//       }
//     }

//     "rechunkRandomly" in forAll { (s: Stream[Pure, Int]) =>
//       val expected = s.toList
//       s.rechunkRandomly[IO]().compile.toList.asserting(it => assert(it == expected))
//     }

//     "repartition" in {
//       assert(
//         Stream("Lore", "m ip", "sum dolo", "r sit amet")
//           .repartition(s => Chunk.array(s.split(" ")))
//           .toList ==
//           List("Lorem", "ipsum", "dolor", "sit", "amet")
//       )
//       assert(
//         Stream("hel", "l", "o Wor", "ld")
//           .repartition(s => Chunk.indexedSeq(s.grouped(2).toVector))
//           .toList ==
//           List("he", "ll", "o ", "Wo", "rl", "d")
//       )
//       assert(
//         Stream.empty
//           .covaryOutput[String]
//           .repartition(_ => Chunk.empty)
//           .toList == List()
//       )
//       assert(Stream("hello").repartition(_ => Chunk.empty).toList == List())

//       def input = Stream("ab").repeat
//       def ones(s: String) = Chunk.vector(s.grouped(1).toVector)
//       assert(input.take(2).repartition(ones).toVector == Vector("a", "b", "a", "b"))
//       assert(
//         input.take(4).repartition(ones).toVector == Vector(
//           "a",
//           "b",
//           "a",
//           "b",
//           "a",
//           "b",
//           "a",
//           "b"
//         )
//       )
//       assert(input.repartition(ones).take(2).toVector == Vector("a", "b"))
//       assert(input.repartition(ones).take(4).toVector == Vector("a", "b", "a", "b"))
//       assert(
//         Stream
//           .emits(input.take(4).toVector)
//           .repartition(ones)
//           .toVector == Vector("a", "b", "a", "b", "a", "b", "a", "b")
//       )

//       assert(
//         Stream(1, 2, 3, 4, 5).repartition(i => Chunk(i, i)).toList == List(1, 3, 6, 10, 15, 15)
//       )

//       assert(
//         Stream(1, 10, 100)
//           .repartition(_ => Chunk.seq(1 to 1000))
//           .take(4)
//           .toList == List(1, 2, 3, 4)
//       )
//     }

//     "repeat" in {
//       forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
//         (n: Int, testValues: List[Int]) =>
//           assert(
//             Stream.emits(testValues).repeat.take(n).toList == List
//               .fill(n / testValues.size + 1)(testValues)
//               .flatten
//               .take(n)
//           )
//       }
//     }

//     "repeatN" in {
//       forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
//         (n: Int, testValues: List[Int]) =>
//           assert(Stream.emits(testValues).repeatN(n).toList == List.fill(n)(testValues).flatten)
//       }
//     }

//     "resource" in {
//       Ref[IO]
//         .of(List.empty[String])
//         .flatMap { st =>
//           def record(s: String): IO[Unit] = st.update(_ :+ s)
//           def mkRes(s: String): Resource[IO, Unit] =
//             Resource.make(record(s"acquire $s"))(_ => record(s"release $s"))

//           // We aim to trigger all the possible cases, and make sure all of them
//           // introduce scopes.

//           // Allocate
//           val res1 = mkRes("1")
//           // Bind
//           val res2 = mkRes("21") *> mkRes("22")
//           // Suspend
//           val res3 = Resource.suspend(
//             record("suspend").as(mkRes("3"))
//           )

//           List(res1, res2, res3)
//             .foldMap(Stream.resource)
//             .evalTap(_ => record("use"))
//             .append(Stream.eval_(record("done")))
//             .compile
//             .drain *> st.get
//         }
//         .asserting(it =>
//           assert(
//             it == List(
//               "acquire 1",
//               "use",
//               "release 1",
//               "acquire 21",
//               "acquire 22",
//               "use",
//               "release 22",
//               "release 21",
//               "suspend",
//               "acquire 3",
//               "use",
//               "release 3",
//               "done"
//             )
//           )
//         )
//     }

//     "resourceWeak" in {
//       Ref[IO]
//         .of(List.empty[String])
//         .flatMap { st =>
//           def record(s: String): IO[Unit] = st.update(_ :+ s)
//           def mkRes(s: String): Resource[IO, Unit] =
//             Resource.make(record(s"acquire $s"))(_ => record(s"release $s"))

//           // We aim to trigger all the possible cases, and make sure none of them
//           // introduce scopes.

//           // Allocate
//           val res1 = mkRes("1")
//           // Bind
//           val res2 = mkRes("21") *> mkRes("22")
//           // Suspend
//           val res3 = Resource.suspend(
//             record("suspend").as(mkRes("3"))
//           )

//           List(res1, res2, res3)
//             .foldMap(Stream.resourceWeak)
//             .evalTap(_ => record("use"))
//             .append(Stream.eval_(record("done")))
//             .compile
//             .drain *> st.get
//         }
//         .asserting(it =>
//           assert(
//             it == List(
//               "acquire 1",
//               "use",
//               "acquire 21",
//               "acquire 22",
//               "use",
//               "suspend",
//               "acquire 3",
//               "use",
//               "done",
//               "release 3",
//               "release 22",
//               "release 21",
//               "release 1"
//             )
//           )
//         )
//     }

//     "resource safety" - {
//       "1" in {
//         forAll { (s1: Stream[Pure, Int]) =>
//           Counter[IO].flatMap { counter =>
//             val x = Stream.bracket(counter.increment)(_ => counter.decrement) >> s1
//             val y = Stream.raiseError[IO](new Err)
//             x.merge(y)
//               .attempt
//               .append(y.merge(x).attempt)
//               .compile
//               .drain
//               .flatMap(_ => counter.get)
//               .asserting(it => assert(it == 0L))
//           }
//         }
//       }

//       "2a" in {
//         Counter[IO].flatMap { counter =>
//           val s = Stream.raiseError[IO](new Err)
//           val b = Stream.bracket(counter.increment)(_ => counter.decrement) >> s
//           // subtle test, get different scenarios depending on interleaving:
//           // `s` completes with failure before the resource is acquired by `b`
//           // `b` has just caught inner error when outer `s` fails
//           // `b` fully completes before outer `s` fails
//           b.merge(s)
//             .compile
//             .drain
//             .attempt
//             .flatMap(_ => counter.get)
//             .asserting(it => assert(it == 0L))
//             .repeatTest(25)
//         }
//       }

//       "2b" in {
//         forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
//           Counter[IO].flatMap { counter =>
//             val b1 = Stream.bracket(counter.increment)(_ => counter.decrement) >> s1
//             val b2 = Stream.bracket(counter.increment)(_ => counter.decrement) >> s2
//             spuriousFail(b1)
//               .merge(b2)
//               .attempt
//               .append(b1.merge(spuriousFail(b2)).attempt)
//               .append(spuriousFail(b1).merge(spuriousFail(b2)).attempt)
//               .compile
//               .drain
//               .flatMap(_ => counter.get)
//               .asserting(it => assert(it == 0L))
//           }
//         }
//       }

//       "3" in {
//         pending // TODO: Sometimes fails with inner == 1 on final assertion
//         forAll { (s: Stream[Pure, Stream[Pure, Int]], n0: PosInt) =>
//           val n = n0 % 10 + 1
//           Counter[IO].flatMap { outer =>
//             Counter[IO].flatMap { inner =>
//               val s2 = Stream.bracket(outer.increment)(_ => outer.decrement) >> s.map { _ =>
//                 spuriousFail(Stream.bracket(inner.increment)(_ => inner.decrement) >> s)
//               }
//               val one = s2.parJoin(n).take(10).attempt
//               val two = s2.parJoin(n).attempt
//               one
//                 .append(two)
//                 .compile
//                 .drain
//                 .flatMap(_ => outer.get)
//                 .asserting(it => assert(it == 0L))
//                 .flatMap(_ => IO.sleep(50.millis)) // Allow time for inner stream to terminate
//                 .flatMap(_ => inner.get)
//                 .asserting(it => assert(it == 0L))
//             }
//           }
//         }
//       }

//       "4" in forAll { (s: Stream[Pure, Int]) =>
//         Counter[IO].flatMap { counter =>
//           val s2 = Stream.bracket(counter.increment)(_ => counter.decrement) >> spuriousFail(
//             s.covary[IO]
//           )
//           val one = s2.prefetch.attempt
//           val two = s2.prefetch.prefetch.attempt
//           val three = s2.prefetch.prefetch.prefetch.attempt
//           one
//             .append(two)
//             .append(three)
//             .compile
//             .drain
//             .flatMap(_ => counter.get)
//             .asserting(it => assert(it == 0L))
//         }
//       }

//       "5" in {
//         forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
//           SignallingRef[IO, Boolean](false).flatMap { signal =>
//             Counter[IO].flatMap { counter =>
//               val sleepAndSet = IO.sleep(20.millis) >> signal.set(true)
//               Stream
//                 .eval_(sleepAndSet.start)
//                 .append(s.map { _ =>
//                   Stream
//                     .bracket(counter.increment)(_ => counter.decrement)
//                     .evalMap(_ => IO.never)
//                     .interruptWhen(signal.discrete)
//                 })
//                 .parJoinUnbounded
//                 .compile
//                 .drain
//                 .flatMap(_ => counter.get)
//                 .asserting(it => assert(it == 0L))
//             }
//           }
//         }
//       }

//       "6" in {
//         // simpler version of (5) above which previously failed reliably, checks the case where a
//         // stream is interrupted while in the middle of a resource acquire that is immediately followed
//         // by a step that never completes!
//         SignallingRef[IO, Boolean](false).flatMap { signal =>
//           Counter[IO].flatMap { counter =>
//             val sleepAndSet = IO.sleep(20.millis) >> signal.set(true)
//             Stream
//               .eval_(sleepAndSet.start)
//               .append(Stream(Stream(1)).map { inner =>
//                 Stream
//                   .bracket(counter.increment >> IO.sleep(2.seconds))(_ => counter.decrement)
//                   .flatMap(_ => inner)
//                   .evalMap(_ => IO.never)
//                   .interruptWhen(signal.discrete)
//               })
//               .parJoinUnbounded
//               .compile
//               .drain
//               .flatMap(_ => counter.get)
//               .asserting(it => assert(it == 0L))
//           }
//         }
//       }
//     }

//     "retry" - {
//       case class RetryErr(msg: String = "") extends RuntimeException(msg)

//       "immediate success" in {
//         IO.suspend {
//           var attempts = 0
//           val job = IO {
//             attempts += 1
//             "success"
//           }
//           Stream.retry(job, 1.seconds, x => x, 100).compile.toList.asserting { r =>
//             assert(attempts == 1)
//             assert(r == List("success"))
//           }
//         }
//       }

//       "eventual success" in {
//         IO.suspend {
//           var failures, successes = 0
//           val job = IO {
//             if (failures == 5) {
//               successes += 1; "success"
//             } else {
//               failures += 1; throw RetryErr()
//             }
//           }
//           Stream.retry(job, 100.millis, x => x, 100).compile.toList.asserting { r =>
//             assert(failures == 5)
//             assert(successes == 1)
//             assert(r == List("success"))
//           }
//         }
//       }

//       "maxRetries" in {
//         IO.suspend {
//           var failures = 0
//           val job = IO {
//             failures += 1
//             throw RetryErr(failures.toString)
//           }
//           Stream.retry(job, 100.millis, x => x, 5).compile.drain.attempt.asserting {
//             case Left(RetryErr(msg)) =>
//               assert(failures == 5)
//               assert(msg == "5")
//             case _ => fail("Expected a RetryErr")
//           }
//         }
//       }

//       "fatal" in {
//         IO.suspend {
//           var failures, successes = 0
//           val job = IO {
//             if (failures == 5) {
//               failures += 1; throw RetryErr("fatal")
//             } else if (failures > 5) {
//               successes += 1; "success"
//             } else {
//               failures += 1; throw RetryErr()
//             }
//           }
//           val f: Throwable => Boolean = _.getMessage != "fatal"
//           Stream.retry(job, 100.millis, x => x, 100, f).compile.drain.attempt.asserting {
//             case Left(RetryErr(msg)) =>
//               assert(failures == 6)
//               assert(successes == 0)
//               assert(msg == "fatal")
//             case _ => fail("Expected a RetryErr")
//           }
//         }
//       }

//       "delays" in {
//         flickersOnTravis
//         val delays = scala.collection.mutable.ListBuffer.empty[Long]
//         val unit = 200
//         val maxTries = 5
//         def getDelays =
//           delays
//             .synchronized(delays.toList)
//             .sliding(2)
//             .map(s => (s.tail.head - s.head) / unit)
//             .toList

//         val job = {
//           val start = System.currentTimeMillis()
//           IO {
//             delays.synchronized(delays += System.currentTimeMillis() - start)
//             throw RetryErr()
//           }
//         }

//         Stream.retry(job, unit.millis, _ + unit.millis, maxTries).compile.drain.attempt.asserting {
//           case Left(RetryErr(_)) =>
//             assert(getDelays == List.range(1, maxTries))
//           case _ => fail("Expected a RetryErr")
//         }
//       }
//     }

//     "scan" - {
//       "1" in forAll { (s: Stream[Pure, Int], n: Int) =>
//         val f = (a: Int, b: Int) => a + b
//         assert(s.scan(n)(f).toList == s.toList.scanLeft(n)(f))
//       }

//       "2" in {
//         val s = Stream(1).map(x => x)
//         val f = (a: Int, b: Int) => a + b
//         assert(s.scan(0)(f).toList == s.toList.scanLeft(0)(f))
//       }

//       "temporal" in {
//         val never = Stream.eval(IO.async[Int](_ => ()))
//         val s = Stream(1)
//         val f = (a: Int, b: Int) => a + b
//         val result = s.toList.scanLeft(0)(f)
//         s.append(never)
//           .scan(0)(f)
//           .take(result.size)
//           .compile
//           .toList
//           .asserting(it => assert(it == result))
//       }
//     }

//     "scan1" in forAll { (s: Stream[Pure, Int]) =>
//       val v = s.toVector
//       val f = (a: Int, b: Int) => a + b
//       assert(
//         s.scan1(f).toVector == v.headOption.fold(Vector.empty[Int])(h => v.drop(1).scanLeft(h)(f))
//       )
//     }

//     "scope" - {
//       "1" in {
//         val c = new java.util.concurrent.atomic.AtomicLong(0)
//         val s1 = Stream.emit("a").covary[IO]
//         val s2 = Stream
//           .bracket(IO { assert(c.incrementAndGet() == 1L); () }) { _ =>
//             IO { c.decrementAndGet(); () }
//           }
//           .flatMap(_ => Stream.emit("b"))
//         (s1.scope ++ s2)
//           .take(2)
//           .scope
//           .repeat
//           .take(4)
//           .merge(Stream.eval_(IO.unit))
//           .compile
//           .drain
//           .asserting(_ => assert(c.get == 0L))
//       }

//       "2" in {
//         Stream
//           .eval(Ref.of[IO, Int](0))
//           .flatMap { ref =>
//             Stream(1).flatMap { _ =>
//               Stream
//                 .bracketWeak(ref.update(_ + 1))(_ => ref.update(_ - 1))
//                 .flatMap(_ => Stream.eval(ref.get)) ++ Stream.eval(ref.get)
//             }.scope ++ Stream.eval(ref.get)
//           }
//           .compile
//           .toList
//           .asserting(it => assert(it == List(1, 1, 0)))
//       }
//     }

//     "sleep" in {
//       val delay = 200.millis
//       // force a sync up in duration, then measure how long sleep takes
//       val emitAndSleep = Stream(()) ++ Stream.sleep[IO](delay)
//       emitAndSleep
//         .zip(Stream.duration[IO])
//         .drop(1)
//         .map(_._2)
//         .compile
//         .toList
//         .asserting(result =>
//           assert(result.head >= (delay * 0.95))
//         ) // Allow for sleep starting just before duration measurement
//     }

//     "sliding" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
//       val n = n0 % 20 + 1
//       assert(
//         s.sliding(n).toList.map(_.toList) == s.toList
//           .sliding(n)
//           .map(_.toList)
//           .toList
//       )
//     }

//     "split" - {
//       "1" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
//         val n = n0 % 20 + 1
//         val s2 = s
//           .map(x => if (x == Int.MinValue) x + 1 else x)
//           .map(_.abs)
//           .filter(_ != 0)
//         withClue(s"n = $n, s = ${s.toList}, s2 = " + s2.toList) {
//           assert(
//             s2.chunkLimit(n)
//               .intersperse(Chunk.singleton(0))
//               .flatMap(Stream.chunk)
//               .split(_ == 0)
//               .map(_.toVector)
//               .filter(_.nonEmpty)
//               .toVector == s2.chunkLimit(n).filter(_.nonEmpty).map(_.toVector).toVector
//           )
//         }
//       }

//       "2" in {
//         assert(
//           Stream(1, 2, 0, 0, 3, 0, 4).split(_ == 0).toVector.map(_.toVector) == Vector(
//             Vector(1, 2),
//             Vector(),
//             Vector(3),
//             Vector(4)
//           )
//         )
//         assert(
//           Stream(1, 2, 0, 0, 3, 0).split(_ == 0).toVector.map(_.toVector) == Vector(
//             Vector(1, 2),
//             Vector(),
//             Vector(3)
//           )
//         )
//         assert(
//           Stream(1, 2, 0, 0, 3, 0, 0).split(_ == 0).toVector.map(_.toVector) == Vector(
//             Vector(1, 2),
//             Vector(),
//             Vector(3),
//             Vector()
//           )
//         )
//       }
//     }

//     "switchMap" - {
//       "flatMap equivalence when switching never occurs" in forAll { s: Stream[Pure, Int] =>
//         val expected = s.toList
//         Stream
//           .eval(Semaphore[IO](1))
//           .flatMap { guard =>
//             s.covary[IO]
//               .evalTap(_ => guard.acquire) // wait for inner to emit to prevent switching
//               .onFinalize(guard.acquire) // outer terminates, wait for last inner to emit
//               .switchMap(x => Stream.emit(x).onFinalize(guard.release))
//           }
//           .compile
//           .toList
//           .asserting(it => assert(it == expected))
//       }

//       "inner stream finalizer always runs before switching" in {
//         forAll { s: Stream[Pure, Int] =>
//           Stream
//             .eval(Ref[IO].of(true))
//             .flatMap { ref =>
//               s.covary[IO].switchMap { _ =>
//                 Stream.eval(ref.get).flatMap { released =>
//                   if (!released) Stream.raiseError[IO](new Err)
//                   else
//                     Stream
//                       .eval(ref.set(false) >> IO.sleep(20.millis))
//                       .onFinalize(IO.sleep(100.millis) >> ref.set(true))
//                 }
//               }
//             }
//             .compile
//             .drain
//             .assertNoException
//         }
//       }

//       "when primary stream terminates, inner stream continues" in forAll {
//         (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
//           val expected = s1.last.unNoneTerminate.flatMap(s => s2 ++ Stream(s)).toList
//           s1.covary[IO]
//             .switchMap(s => Stream.sleep_[IO](25.millis) ++ s2 ++ Stream.emit(s))
//             .compile
//             .toList
//             .asserting(it => assert(it == expected))
//       }

//       "when inner stream fails, overall stream fails" in forAll { (s0: Stream[Pure, Int]) =>
//         val s = Stream(0) ++ s0
//         s.delayBy[IO](25.millis)
//           .switchMap(_ => Stream.raiseError[IO](new Err))
//           .compile
//           .drain
//           .assertThrows[Err]
//       }

//       "when primary stream fails, overall stream fails and inner stream is terminated" in {
//         Stream
//           .eval(Semaphore[IO](0))
//           .flatMap { semaphore =>
//             Stream(0)
//               .append(Stream.raiseError[IO](new Err).delayBy(10.millis))
//               .switchMap(_ =>
//                 Stream.repeatEval(IO(1) *> IO.sleep(10.millis)).onFinalize(semaphore.release)
//               )
//               .onFinalize(semaphore.acquire)
//           }
//           .compile
//           .drain
//           .assertThrows[Err]
//       }

//       "when inner stream fails, inner stream finalizer run before the primary one" in {
//         forAll { (s0: Stream[Pure, Int]) =>
//           val s = Stream(0) ++ s0
//           Stream
//             .eval(Deferred[IO, Boolean])
//             .flatMap { verdict =>
//               Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
//                 s.delayBy[IO](25.millis)
//                   .onFinalize(innerReleased.get.flatMap(inner => verdict.complete(inner)))
//                   .switchMap(_ =>
//                     Stream.raiseError[IO](new Err).onFinalize(innerReleased.set(true))
//                   )
//                   .attempt
//                   .drain ++
//                   Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO.unit))
//               }
//             }
//             .compile
//             .drain
//             .assertThrows[Err]
//         }
//       }

//       "when primary stream fails, inner stream finalizer run before the primary one" in {
//         if (isJVM) flickersOnTravis else pending
//         Stream
//           .eval(Ref[IO].of(false))
//           .flatMap { verdict =>
//             Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
//               // TODO ideally make sure the inner stream has actually started
//               (Stream(1) ++ Stream.sleep_[IO](25.millis) ++ Stream.raiseError[IO](new Err))
//                 .onFinalize(innerReleased.get.flatMap(inner => verdict.set(inner)))
//                 .switchMap(_ => Stream.repeatEval(IO(1)).onFinalize(innerReleased.set(true)))
//                 .attempt
//                 .drain ++
//                 Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO(())))
//             }
//           }
//           .compile
//           .drain
//           .assertThrows[Err]
//       }
//     }

//     "tail" in forAll((s: Stream[Pure, Int]) => assert(s.tail.toList == s.toList.drop(1)))

//     "take" - {
//       "identity" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosInt) =>
//         val n1 = n0 % 20 + 1
//         val n = if (negate) -n1 else n1
//         assert(s.take(n).toList == s.toList.take(n))
//       }
//       "chunks" in {
//         val s = Stream(1, 2) ++ Stream(3, 4)
//         assert(s.take(3).chunks.map(_.toList).toList == List(List(1, 2), List(3)))
//       }
//     }

//     "takeRight" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosInt) =>
//       val n1 = n0 % 20 + 1
//       val n = if (negate) -n1 else n1
//       assert(s.takeRight(n).toList == s.toList.takeRight(n))
//     }

//     "takeWhile" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
//       val n = n0 % 20 + 1
//       val set = s.toList.take(n).toSet
//       assert(s.takeWhile(set).toList == s.toList.takeWhile(set))
//     }

//     "takeThrough" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
//       val n = n0 % 20 + 1
//       val f = (i: Int) => i % n == 0
//       val vec = s.toVector
//       val result = vec.takeWhile(f) ++ vec.dropWhile(f).headOption
//       assert(withClue(vec)(s.takeThrough(f).toVector == result))
//     }

//     "translate" - {
//       "1 - id" in forAll { (s: Stream[Pure, Int]) =>
//         val expected = s.toList
//         s.covary[SyncIO]
//           .flatMap(i => Stream.eval(SyncIO.pure(i)))
//           .translate(cats.arrow.FunctionK.id[SyncIO])
//           .compile
//           .toList
//           .asserting(it => assert(it == expected))
//       }

//       "2" in forAll { (s: Stream[Pure, Int]) =>
//         val expected = s.toList
//         s.covary[Function0]
//           .flatMap(i => Stream.eval(() => i))
//           .flatMap(i => Stream.eval(() => i))
//           .translate(new (Function0 ~> SyncIO) {
//             def apply[A](thunk: Function0[A]) = SyncIO(thunk())
//           })
//           .compile
//           .toList
//           .asserting(it => assert(it == expected))
//       }

//       "3 - ok to have multiple translates" in forAll { (s: Stream[Pure, Int]) =>
//         val expected = s.toList
//         s.covary[Function0]
//           .flatMap(i => Stream.eval(() => i))
//           .flatMap(i => Stream.eval(() => i))
//           .translate(new (Function0 ~> Some) {
//             def apply[A](thunk: Function0[A]) = Some(thunk())
//           })
//           .flatMap(i => Stream.eval(Some(i)))
//           .flatMap(i => Stream.eval(Some(i)))
//           .translate(new (Some ~> SyncIO) {
//             def apply[A](some: Some[A]) = SyncIO(some.get)
//           })
//           .compile
//           .toList
//           .asserting(it => assert(it == expected))
//       }

//       "4 - ok to translate after zip with effects" in {
//         val stream: Stream[Function0, Int] =
//           Stream.eval(() => 1)
//         stream
//           .zip(stream)
//           .translate(new (Function0 ~> SyncIO) {
//             def apply[A](thunk: Function0[A]) = SyncIO(thunk())
//           })
//           .compile
//           .toList
//           .asserting(it => assert(it == List((1, 1))))
//       }

//       "5 - ok to translate a step leg that emits multiple chunks" in {
//         def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
//           step match {
//             case None       => Pull.done
//             case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
//           }
//         (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
//           .flatMap(goStep)
//           .stream
//           .translate(new (Function0 ~> SyncIO) {
//             def apply[A](thunk: Function0[A]) = SyncIO(thunk())
//           })
//           .compile
//           .toList
//           .asserting(it => assert(it == List(1, 2)))
//       }

//       "6 - ok to translate step leg that has uncons in its structure" in {
//         def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
//           step match {
//             case None       => Pull.done
//             case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
//           }
//         (Stream.eval(() => 1) ++ Stream.eval(() => 2))
//           .flatMap(a => Stream.emit(a))
//           .flatMap(a => Stream.eval(() => a + 1) ++ Stream.eval(() => a + 2))
//           .pull
//           .stepLeg
//           .flatMap(goStep)
//           .stream
//           .translate(new (Function0 ~> SyncIO) {
//             def apply[A](thunk: Function0[A]) = SyncIO(thunk())
//           })
//           .compile
//           .toList
//           .asserting(it => assert(it == List(2, 3, 3, 4)))
//       }

//       "7 - ok to translate step leg that is forced back in to a stream" in {
//         def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
//           step match {
//             case None => Pull.done
//             case Some(step) =>
//               Pull.output(step.head) >> step.stream.pull.echo
//           }
//         (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
//           .flatMap(goStep)
//           .stream
//           .translate(new (Function0 ~> SyncIO) {
//             def apply[A](thunk: Function0[A]) = SyncIO(thunk())
//           })
//           .compile
//           .toList
//           .asserting(it => assert(it == List(1, 2)))
//       }

//       "stack safety" in {
//         Stream
//           .repeatEval(SyncIO(0))
//           .translate(new (SyncIO ~> SyncIO) { def apply[X](x: SyncIO[X]) = SyncIO.suspend(x) })
//           .take(if (isJVM) 1000000 else 10000)
//           .compile
//           .drain
//           .assertNoException
//       }
//     }

//     "translateInterruptible" in {
//       Stream
//         .eval(IO.never)
//         .merge(Stream.eval(IO(1)).delayBy(5.millis).repeat)
//         .interruptAfter(10.millis)
//         .translateInterruptible(cats.arrow.FunctionK.id[IO])
//         .compile
//         .drain
//         .assertNoException
//     }

//     "unfold" in {
//       assert(
//         Stream
//           .unfold((0, 1)) {
//             case (f1, f2) =>
//               if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
//           }
//           .map(_._1)
//           .toList == List(0, 1, 1, 2, 3, 5, 8, 13)
//       )
//     }

//     "unfoldChunk" in {
//       assert(
//         Stream
//           .unfoldChunk(4L) { s =>
//             if (s > 0) Some((Chunk.longs(Array[Long](s, s)), s - 1))
//             else None
//           }
//           .toList == List[Long](4, 4, 3, 3, 2, 2, 1, 1)
//       )
//     }

//     "unfoldEval" in {
//       Stream
//         .unfoldEval(10)(s => IO.pure(if (s > 0) Some((s, s - 1)) else None))
//         .compile
//         .toList
//         .asserting(it => assert(it == List.range(10, 0, -1)))
//     }

//     "unfoldChunkEval" in {
//       Stream
//         .unfoldChunkEval(true)(s =>
//           SyncIO.pure(
//             if (s) Some((Chunk.booleans(Array[Boolean](s)), false))
//             else None
//           )
//         )
//         .compile
//         .toList
//         .asserting(it => assert(it == List(true)))
//     }

//     "unNone" in forAll { (s: Stream[Pure, Option[Int]]) =>
//       assert(s.unNone.chunks.toList == s.filter(_.isDefined).map(_.get).chunks.toList)
//     }

//     "align" - {

//       "align" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
//         assert(s1.align(s2).toList == s1.toList.align(s2.toList))
//       }

//       "left side empty and right side populated" in {
//         val empty = Stream.empty
//         val s = Stream("A", "B", "C")
//         assert(
//           empty.align(s).take(3).toList == List(Ior.Right("A"), Ior.Right("B"), Ior.Right("C"))
//         )
//       }

//       "right side empty and left side populated" in {
//         val empty = Stream.empty
//         val s = Stream("A", "B", "C")
//         assert(
//           s.align(empty).take(3).toList == List(Ior.Left("A"), Ior.Left("B"), Ior.Left("C"))
//         )
//       }

//       "values in both sides" in {
//         val ones = Stream.constant("1")
//         val s = Stream("A", "B", "C")
//         assert(
//           s.align(ones).take(4).toList == List(
//             Ior.Both("A", "1"),
//             Ior.Both("B", "1"),
//             Ior.Both("C", "1"),
//             Ior.Right("1")
//           )
//         )
//       }

//       "extra values in right" in {
//         val nums = Stream("1", "2", "3", "4", "5")
//         val s = Stream("A", "B", "C")
//         assert(
//           s.align(nums).take(5).toList == List(
//             Ior.Both("A", "1"),
//             Ior.Both("B", "2"),
//             Ior.Both("C", "3"),
//             Ior.Right("4"),
//             Ior.Right("5")
//           )
//         )
//       }

//       "extra values in left" in {
//         val nums = Stream("1", "2", "3", "4", "5")
//         val s = Stream("A", "B", "C")
//         assert(
//           nums.align(s).take(5).toList == List(
//             Ior.Both("1", "A"),
//             Ior.Both("2", "B"),
//             Ior.Both("3", "C"),
//             Ior.Left("4"),
//             Ior.Left("5")
//           )
//         )
//       }

//     }

//     "zip" - {
//       "propagate error from closing the root scope" in {
//         val s1 = Stream.bracket(IO(1))(_ => IO.unit)
//         val s2 = Stream.bracket(IO("a"))(_ => IO.raiseError(new Err))

//         val r1 = s1.zip(s2).compile.drain.attempt.unsafeRunSync()
//         assert(r1.fold(identity, r => fail(s"expected left but got Right($r)")).isInstanceOf[Err])
//         val r2 = s2.zip(s1).compile.drain.attempt.unsafeRunSync()
//         assert(r2.fold(identity, r => fail(s"expected left but got Right($r)")).isInstanceOf[Err])
//       }

//       "issue #941 - scope closure issue" in {
//         assert(
//           Stream(1, 2, 3)
//             .map(_ + 1)
//             .repeat
//             .zip(Stream(4, 5, 6).map(_ + 1).repeat)
//             .take(4)
//             .toList == List((2, 5), (3, 6), (4, 7), (2, 5))
//         )
//       }

//       "zipWith left/right side infinite" in {
//         val ones = Stream.constant("1")
//         val s = Stream("A", "B", "C")
//         assert(ones.zipWith(s)(_ + _).toList == List("1A", "1B", "1C"))
//         assert(s.zipWith(ones)(_ + _).toList == List("A1", "B1", "C1"))
//       }

//       "zipWith both side infinite" in {
//         val ones = Stream.constant("1")
//         val as = Stream.constant("A")
//         assert(ones.zipWith(as)(_ + _).take(3).toList == List("1A", "1A", "1A"))
//         assert(as.zipWith(ones)(_ + _).take(3).toList == List("A1", "A1", "A1"))
//       }

//       "zipAllWith left/right side infinite" in {
//         val ones = Stream.constant("1")
//         val s = Stream("A", "B", "C")
//         assert(
//           ones.zipAllWith(s)("2", "Z")(_ + _).take(5).toList ==
//             List("1A", "1B", "1C", "1Z", "1Z")
//         )
//         assert(
//           s.zipAllWith(ones)("Z", "2")(_ + _).take(5).toList ==
//             List("A1", "B1", "C1", "Z1", "Z1")
//         )
//       }

//       "zipAllWith both side infinite" in {
//         val ones = Stream.constant("1")
//         val as = Stream.constant("A")
//         assert(
//           ones.zipAllWith(as)("2", "Z")(_ + _).take(3).toList ==
//             List("1A", "1A", "1A")
//         )
//         assert(
//           as.zipAllWith(ones)("Z", "2")(_ + _).take(3).toList ==
//             List("A1", "A1", "A1")
//         )
//       }

//       "zip left/right side infinite" in {
//         val ones = Stream.constant("1")
//         val s = Stream("A", "B", "C")
//         assert(ones.zip(s).toList == List("1" -> "A", "1" -> "B", "1" -> "C"))
//         assert(s.zip(ones).toList == List("A" -> "1", "B" -> "1", "C" -> "1"))
//       }

//       "zip both side infinite" in {
//         val ones = Stream.constant("1")
//         val as = Stream.constant("A")
//         assert(ones.zip(as).take(3).toList == List("1" -> "A", "1" -> "A", "1" -> "A"))
//         assert(as.zip(ones).take(3).toList == List("A" -> "1", "A" -> "1", "A" -> "1"))
//       }

//       "zipAll left/right side infinite" in {
//         val ones = Stream.constant("1")
//         val s = Stream("A", "B", "C")
//         assert(
//           ones.zipAll(s)("2", "Z").take(5).toList == List(
//             "1" -> "A",
//             "1" -> "B",
//             "1" -> "C",
//             "1" -> "Z",
//             "1" -> "Z"
//           )
//         )
//         assert(
//           s.zipAll(ones)("Z", "2").take(5).toList == List(
//             "A" -> "1",
//             "B" -> "1",
//             "C" -> "1",
//             "Z" -> "1",
//             "Z" -> "1"
//           )
//         )
//       }

//       "zipAll both side infinite" in {
//         val ones = Stream.constant("1")
//         val as = Stream.constant("A")
//         assert(ones.zipAll(as)("2", "Z").take(3).toList == List("1" -> "A", "1" -> "A", "1" -> "A"))
//         assert(as.zipAll(ones)("Z", "2").take(3).toList == List("A" -> "1", "A" -> "1", "A" -> "1"))
//       }

//       "zip with scopes" - {
//         "1" in {
//           // this tests that streams opening resources on each branch will close
//           // scopes independently.
//           val s = Stream(0).scope
//           assert((s ++ s).zip(s).toList == List((0, 0)))
//         }
//         def brokenZip[F[_], A, B](s1: Stream[F, A], s2: Stream[F, B]): Stream[F, (A, B)] = {
//           def go(s1: Stream[F, A], s2: Stream[F, B]): Pull[F, (A, B), Unit] =
//             s1.pull.uncons1.flatMap {
//               case Some((hd1, tl1)) =>
//                 s2.pull.uncons1.flatMap {
//                   case Some((hd2, tl2)) =>
//                     Pull.output1((hd1, hd2)) >> go(tl1, tl2)
//                   case None => Pull.done
//                 }
//               case None => Pull.done
//             }
//           go(s1, s2).stream
//         }
//         "2" in {
//           val s = Stream(0).scope
//           assertThrows[Throwable](brokenZip(s ++ s, s.zip(s)).compile.toList)
//         }
//         "3" in {
//           Logger[IO]
//             .flatMap { logger =>
//               def s(tag: String) =
//                 logger.logLifecycle(tag) >> (logger.logLifecycle(s"$tag - 1") ++ logger
//                   .logLifecycle(s"$tag - 2"))
//               s("a").zip(s("b")).compile.drain *> logger.get
//             }
//             .asserting { it =>
//               assert(
//                 it == List(
//                   LogEvent.Acquired("a"),
//                   LogEvent.Acquired("a - 1"),
//                   LogEvent.Acquired("b"),
//                   LogEvent.Acquired("b - 1"),
//                   LogEvent.Released("a - 1"),
//                   LogEvent.Acquired("a - 2"),
//                   LogEvent.Released("b - 1"),
//                   LogEvent.Acquired("b - 2"),
//                   LogEvent.Released("a - 2"),
//                   LogEvent.Released("a"),
//                   LogEvent.Released("b - 2"),
//                   LogEvent.Released("b")
//                 )
//               )
//             }
//         }
//       }

//       "issue #1120 - zip with uncons" in {
//         // this tests we can properly look up scopes for the zipped streams
//         val rangeStream = Stream.emits((0 to 3).toList)
//         assert(
//           rangeStream.zip(rangeStream).attempt.map(identity).toVector == Vector(
//             Right((0, 0)),
//             Right((1, 1)),
//             Right((2, 2)),
//             Right((3, 3))
//           )
//         )
//       }
//     }

//     "parZip" - {
//       "parZip outputs the same results as zip" in forAll {
//         (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
//           val par = s1.covary[IO].parZip(s2)
//           val seq = s1.zip(s2)
//           par.compile.toList.asserting(result => assert(result == seq.toList))
//       }

//       "parZip evaluates effects with bounded concurrency" in {
//         // various shenanigans to support TestContext in our current test setup
//         val contextShiftIO = ()
//         val timerIO = ()
//         val (_, _) = (contextShiftIO, timerIO)
//         val env = TestContext()
//         implicit val ctx: ContextShift[IO] = env.contextShift[IO](IO.ioEffect)
//         implicit val timer: Timer[IO] = env.timer[IO]

//         // track progress of the computation
//         @volatile var lhs: Int = 0
//         @volatile var rhs: Int = 0
//         @volatile var output: Vector[(String, Int)] = Vector()

//         // synchronises lhs and rhs to test both sides of the race in parZip
//         def parZipRace[A, B](lhs: Stream[IO, A], rhs: Stream[IO, B]) = {
//           val rate = Stream(1, 2).repeat
//           val skewedRate = Stream(2, 1).repeat
//           def sync[C]: Pipe2[IO, C, Int, C] =
//             (in, rate) => rate.evalMap(n => IO.sleep(n.seconds)).zipRight(in)

//           lhs.through2(rate)(sync).parZip(rhs.through2(skewedRate)(sync))
//         }

//         val stream = parZipRace(
//           Stream("a", "b", "c").evalTap(_ => IO { lhs = lhs + 1 }),
//           Stream(1, 2, 3).evalTap(_ => IO { rhs = rhs + 1 })
//         ).evalTap(x => IO { output = output :+ x })

//         val result = stream.compile.toVector.unsafeToFuture()

//         // lhsAt, rhsAt and output at time T = [1s, 2s, ..]
//         val snapshots = Vector(
//           (1, 0, Vector()),
//           (1, 1, Vector("a" -> 1)),
//           (1, 2, Vector("a" -> 1)),
//           (2, 2, Vector("a" -> 1, "b" -> 2)),
//           (3, 2, Vector("a" -> 1, "b" -> 2)),
//           (3, 3, Vector("a" -> 1, "b" -> 2, "c" -> 3))
//         )

//         snapshots.foreach { snapshot =>
//           env.tick(1.second)
//           assert((lhs, rhs, output) == snapshot)
//         }

//         env.tick(1.second)
//         result.map(r => assert(r == snapshots.last._3))
//       }
//     }

//     "zipWithIndex" in forAll { (s: Stream[Pure, Int]) =>
//       assert(s.zipWithIndex.toList == s.toList.zipWithIndex)
//     }

//     "zipWithNext" - {
//       "1" in forAll { (s: Stream[Pure, Int]) =>
//         assert(s.zipWithNext.toList == {
//           val xs = s.toList
//           xs.zipAll(xs.map(Some(_)).drop(1), -1, None)
//         })
//       }

//       "2" in {
//         assert(Stream().zipWithNext.toList == Nil)
//         assert(Stream(0).zipWithNext.toList == List((0, None)))
//         assert(Stream(0, 1, 2).zipWithNext.toList == List((0, Some(1)), (1, Some(2)), (2, None)))
//       }
//     }

//     "zipWithPrevious" - {
//       "1" in forAll { (s: Stream[Pure, Int]) =>
//         assert(s.zipWithPrevious.toList == {
//           val xs = s.toList
//           (None +: xs.map(Some(_))).zip(xs)
//         })
//       }

//       "2" in {
//         assert(Stream().zipWithPrevious.toList == Nil)
//         assert(Stream(0).zipWithPrevious.toList == List((None, 0)))
//         assert(
//           Stream(0, 1, 2).zipWithPrevious.toList == List((None, 0), (Some(0), 1), (Some(1), 2))
//         )
//       }
//     }

//     "zipWithPreviousAndNext" - {
//       "1" in forAll { (s: Stream[Pure, Int]) =>
//         assert(s.zipWithPreviousAndNext.toList == {
//           val xs = s.toList
//           val zipWithPrevious = (None +: xs.map(Some(_))).zip(xs)
//           val zipWithPreviousAndNext = zipWithPrevious
//             .zipAll(xs.map(Some(_)).drop(1), (None, -1), None)
//             .map { case ((prev, that), next) => (prev, that, next) }
//           zipWithPreviousAndNext
//         })
//       }

//       "2" in {
//         assert(Stream().zipWithPreviousAndNext.toList == Nil)
//         assert(Stream(0).zipWithPreviousAndNext.toList == List((None, 0, None)))
//         assert(
//           Stream(0, 1, 2).zipWithPreviousAndNext.toList == List(
//             (None, 0, Some(1)),
//             (Some(0), 1, Some(2)),
//             (Some(1), 2, None)
//           )
//         )
//       }
//     }

//     "zipWithScan" in {
//       assert(
//         Stream("uno", "dos", "tres", "cuatro")
//           .zipWithScan(0)(_ + _.length)
//           .toList == List("uno" -> 0, "dos" -> 3, "tres" -> 6, "cuatro" -> 10)
//       )
//       assert(Stream().zipWithScan(())((_, _) => ???).toList == Nil)
//     }

//     "zipWithScan1" in {
//       assert(
//         Stream("uno", "dos", "tres", "cuatro")
//           .zipWithScan1(0)(_ + _.length)
//           .toList == List("uno" -> 3, "dos" -> 6, "tres" -> 10, "cuatro" -> 16)
//       )
//       assert(Stream().zipWithScan1(())((_, _) => ???).toList == Nil)
//     }

//     "regressions" - {
//       "#1089" in {
//         (Stream.chunk(Chunk.bytes(Array.fill(2000)(1.toByte))) ++ Stream.eval(
//           IO.async[Byte](_ => ())
//         )).take(2000).chunks.compile.toVector.assertNoException
//       }

//       "#1107 - scope" in {
//         Stream(0)
//           .covary[IO]
//           .scope
//           .repeat
//           .take(10000)
//           .flatMap(_ => Stream.empty) // Never emit an element downstream
//           .mapChunks(identity) // Use a combinator that calls Stream#pull.uncons
//           .compile
//           .drain
//           .assertNoException
//       }

//       "#1107 - queue" in {
//         Stream
//           .range(0, 10000)
//           .covary[IO]
//           .unchunk
//           .prefetch
//           .flatMap(_ => Stream.empty)
//           .mapChunks(identity)
//           .compile
//           .drain
//           .assertNoException
//       }
//     }
//   }

//   "withTimeout" - {
//     "timeout never-ending stream" in {
//       Stream.never[IO].timeout(100.millis).compile.drain.assertThrows[TimeoutException]
//     }

//     "not trigger timeout on successfully completed stream" in {
//       Stream.sleep(10.millis).timeout(1.second).compile.drain.assertNoException
//     }

//     "compose timeouts d1 and d2 when d1 < d2" in {
//       val d1 = 20.millis
//       val d2 = 30.millis
//       (Stream.sleep(10.millis).timeout(d1) ++ Stream.sleep(30.millis))
//         .timeout(d2)
//         .compile
//         .drain
//         .assertThrows[TimeoutException]
//     }

//     "compose timeouts d1 and d2 when d1 > d2" in {
//       val d1 = 40.millis
//       val d2 = 30.millis
//       (Stream.sleep(10.millis).timeout(d1) ++ Stream.sleep(25.millis))
//         .timeout(d2)
//         .compile
//         .drain
//         .assertThrows[TimeoutException]
//     }
//   }

//   "pure pipes cannot be used with effectful streams (#1838)" in {
//     val p: Pipe[Pure, Int, List[Int]] = in => Stream(in.toList)
//     identity(p) // Avoid unused warning
//     assertDoesNotCompile("Stream.eval(IO(1)).through(p)")
//   }
// }
