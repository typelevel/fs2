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
//

//

//

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
