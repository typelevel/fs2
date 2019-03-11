package fs2

import cats.~>
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._
import scala.concurrent.duration._
import org.scalactic.anyvals._

class StreamSpec extends Fs2Spec {
  "Stream" - {
    "++" in forAll { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      (s1 ++ s2).toList shouldBe (s1.toList ++ s2.toList)
    }

    ">>" in forAll { (s: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      (s >> s2).toList shouldBe s.flatMap(_ => s2).toList
    }

    "apply" in { Stream(1, 2, 3).toList shouldBe List(1, 2, 3) }

    "bracket" - {
      sealed trait BracketEvent
      final case object Acquired extends BracketEvent
      final case object Released extends BracketEvent

      def recordBracketEvents[F[_]](events: Ref[F, Vector[BracketEvent]]): Stream[F, Unit] =
        Stream.bracket(events.update(evts => evts :+ Acquired))(_ =>
          events.update(evts => evts :+ Released))

      "single bracket" - {
        def singleBracketTest[F[_]: Sync, A](use: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events)
              .evalMap(_ =>
                events.get.asserting { events =>
                  events shouldBe Vector(Acquired)
              })
              .flatMap(_ => use)
              .compile
              .drain
              .handleErrorWith { case t: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { _ shouldBe Vector(Acquired, Released) }
          } yield ()

        "normal termination" in { singleBracketTest[SyncIO, Unit](Stream.empty) }
        "failure" in { singleBracketTest[SyncIO, Unit](Stream.raiseError[SyncIO](new Err)) }
        "throw from append" in {
          singleBracketTest(Stream(1, 2, 3) ++ ((throw new Err): Stream[SyncIO, Int]))
        }
      }

      "bracket.scope ++ bracket" - {
        def appendBracketTest[F[_]: Sync, A](use1: Stream[F, A], use2: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events).scope
              .flatMap(_ => use1)
              .append(recordBracketEvents(events).flatMap(_ => use2))
              .compile
              .drain
              .handleErrorWith { case t: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { _ shouldBe Vector(Acquired, Released, Acquired, Released) }
          } yield ()

        "normal termination" in { appendBracketTest[SyncIO, Unit](Stream.empty, Stream.empty) }
        "failure" in {
          appendBracketTest[SyncIO, Unit](Stream.empty, Stream.raiseError[SyncIO](new Err))
        }
      }
    }

    "buffer" - {
      "identity" in forAll { (s: Stream[Pure, Int], n: PosInt) =>
        s.buffer(n).toVector shouldBe s.toVector
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
            .asserting(_ => counter shouldBe (n * 2))
        }
      }
    }

    "bufferAll" - {
      "identity" in forAll { (s: Stream[Pure, Int]) =>
        s.bufferAll.toVector shouldBe s.toVector
      }

      "buffer results of evalMap" in forAll { (s: Stream[Pure, Int]) =>
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
            .asserting(_ => counter shouldBe (s.toList.size * 2))
        }
      }
    }

    "bufferBy" - {
      "identity" in forAll { (s: Stream[Pure, Int]) =>
        s.bufferBy(_ >= 0).toVector shouldBe s.toVector
      }

      "buffer results of evalMap" in forAll { (s: Stream[Pure, Int]) =>
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
            .asserting(_ => counter shouldBe (s.toList.size * 2 + 1))
        }
      }
    }

    "changes" in {
      Stream.empty.covaryOutput[Int].changes.toList shouldBe Nil
      Stream(1, 2, 3, 4).changes.toList shouldBe List(1, 2, 3, 4)
      Stream(1, 1, 2, 2, 3, 3, 4, 3).changes.toList shouldBe List(1, 2, 3, 4, 3)
      Stream("1", "2", "33", "44", "5", "66")
        .changesBy(_.length)
        .toList shouldBe
        List("1", "33", "5", "66")
    }

    "chunk" in {
      forAll { (c: Chunk[Int]) =>
        Stream.chunk(c).toChunk shouldBe c
      }
    }

    "chunkLimit" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val sizeV = s.chunkLimit(n).toVector.map(_.size)
      sizeV.forall(_ <= n) shouldBe true
      sizeV.combineAll shouldBe s.toVector.size
    }

    "chunkN" - {
      "fewer" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val chunkedV = s.chunkN(n, true).toVector
        val unchunkedV = s.toVector
        // All but last list have n0 values
        chunkedV.dropRight(1).forall(_.size == n) shouldBe true
        // Last list has at most n0 values
        chunkedV.lastOption.fold(true)(_.size <= n) shouldBe true
        // Flattened sequence is equal to vector without chunking
        chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) shouldBe unchunkedV
      }

      "no-fewer" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val chunkedV = s.chunkN(n, false).toVector
        val unchunkedV = s.toVector
        val expectedSize = unchunkedV.size - (unchunkedV.size % n)
        // All lists have n0 values
        chunkedV.forall(_.size == n) shouldBe true
        // Flattened sequence is equal to vector without chunking, minus "left over" values that could not fit in a chunk
        chunkedV.foldLeft(Vector.empty[Int])((v, l) => v ++ l.toVector) shouldBe unchunkedV.take(
          expectedSize)
      }
    }

    "chunks" - {
      "chunks.map identity" in forAll { (v: Vector[Vector[Int]]) =>
        val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
        s.chunks.map(_.toVector).toVector shouldBe v.filter(_.nonEmpty)
      }

      "chunks.flatMap(chunk) identity" in forAll { (v: Vector[Vector[Int]]) =>
        val s = if (v.isEmpty) Stream.empty else v.map(Stream.emits).reduce(_ ++ _)
        s.chunks.flatMap(Stream.chunk).toVector shouldBe v.flatten
      }
    }

    "collect" in forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      s.collect(pf).toVector shouldBe s.toVector.collect(pf)
    }

    "collectFirst" in forAll { (s: Stream[Pure, Int]) =>
      val pf: PartialFunction[Int, Int] = { case x if x % 2 == 0 => x }
      s.collectFirst(pf).toVector shouldBe s.collectFirst(pf).toVector
    }

    "delete" in forAll { (s: Stream[Pure, Int], idx0: PosZInt) =>
      val v = s.toVector
      val i = if (v.isEmpty) 0 else v(idx0 % v.size)
      s.delete(_ == i).toVector shouldBe v.diff(Vector(i))
    }

    "drop" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosZInt) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else n0 % v.size
      val n = if (negate) -n1 else n1
      s.drop(n).toVector shouldBe s.toVector.drop(n)
    }

    "dropLast" in forAll { (s: Stream[Pure, Int]) =>
      s.dropLast.toVector shouldBe s.toVector.dropRight(1)
    }

    "dropLastIf" in forAll { (s: Stream[Pure, Int]) =>
      s.dropLastIf(_ => false).toVector shouldBe s.toVector
      s.dropLastIf(_ => true).toVector shouldBe s.toVector.dropRight(1)
    }

    "dropRight" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosZInt) =>
      val v = s.toVector
      val n1 = if (v.isEmpty) 0 else n0 % v.size
      val n = if (negate) -n1 else n1
      s.dropRight(n).toVector shouldBe v.dropRight(n)
    }

    "dropWhile" in forAll { (s: Stream[Pure, Int], n0: PosZInt) =>
      val n = n0 % 20
      val set = s.toVector.take(n).toSet
      s.dropWhile(set).toVector shouldBe s.toVector.dropWhile(set)
    }

    "dropThrough" in forAll { (s: Stream[Pure, Int], n0: PosZInt) =>
      val n = n0 % 20
      val set = s.toVector.take(n).toSet
      s.dropThrough(set).toVector shouldBe {
        val vec = s.toVector.dropWhile(set)
        if (vec.isEmpty) vec else vec.tail
      }
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
          result should have size (1)
          val head = result.head
          head.toMillis should be >= (delay.toMillis - 5)
        }
    }

    "eval" in { Stream.eval(SyncIO(23)).compile.toList.asserting(_ shouldBe List(23)) }

    "evalMapAccumulate" in forAll { (s: Stream[Pure, Int], m: Int, n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (_: Int) % n == 0
      val r = s.covary[IO].evalMapAccumulate(m)((s, i) => IO.pure((s + i, f(i))))
      r.map(_._1).compile.toVector.asserting(_ shouldBe s.toVector.scanLeft(m)(_ + _).tail)
      r.map(_._2).compile.toVector.asserting(_ shouldBe s.toVector.map(f))
    }

    "evalScan" in forAll { (s: Stream[Pure, Int], n: String) =>
      val f: (String, Int) => IO[String] = (a: String, b: Int) => IO.pure(a + b)
      val g = (a: String, b: Int) => a + b
      s.covary[IO].evalScan(n)(f).compile.toVector.asserting(_ shouldBe s.toVector.scanLeft(n)(g))
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
        s =>
          go(0.seconds, s).stream
      }

      val delay = 20.millis
      val draws = (600.millis / delay).min(50) // don't take forever

      val durationsSinceSpike = Stream
        .every[IO](delay)
        .map(d => (d, System.nanoTime.nanos))
        .take(draws.toInt)
        .through(durationSinceLastTrue)

      (IO.shift >> durationsSinceSpike.compile.toVector).unsafeToFuture().map { result =>
        val (head :: tail) = result.toList
        withClue("every always emits true first") { assert(head._1) }
        withClue("true means the delay has passed: " + tail) {
          assert(tail.filter(_._1).map(_._2).forall { _ >= delay })
        }
        withClue("false means the delay has not passed: " + tail) {
          assert(tail.filterNot(_._1).map(_._2).forall { _ <= delay })
        }
      }
    }

    "exists" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (i: Int) => i % n == 0
      s.exists(f).toList shouldBe List(s.toList.exists(f))
    }

    "filter" - {
      "1" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val predicate = (i: Int) => i % n == 0
        s.filter(predicate).toList shouldBe s.toList.filter(predicate)
      }

      "2" in forAll { (s: Stream[Pure, Double]) =>
        val predicate = (i: Double) => i - i.floor < 0.5
        val s2 = s.mapChunks(c => Chunk.doubles(c.toArray))
        s2.filter(predicate).toList shouldBe s2.toList.filter(predicate)
      }

      "3" in forAll { (s: Stream[Pure, Byte]) =>
        val predicate = (b: Byte) => b < 0
        val s2 = s.mapChunks(c => Chunk.bytes(c.toArray))
        s2.filter(predicate).toList shouldBe s2.toList.filter(predicate)
      }

      "4" in forAll { (s: Stream[Pure, Boolean]) =>
        val predicate = (b: Boolean) => !b
        val s2 = s.mapChunks(c => Chunk.booleans(c.toArray))
        s2.filter(predicate).toList shouldBe s2.toList.filter(predicate)
      }
    }

    "find" in forAll { (s: Stream[Pure, Int], i: Int) =>
      val predicate = (item: Int) => item < i
      s.find(predicate).toList shouldBe s.toList.find(predicate).toList
    }

    "flatMap" in forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
      s.flatMap(inner => inner).toList shouldBe s.toList.flatMap(inner => inner.toList)
    }

    "fold" - {
      "1" in forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        s.fold(n)(f).toList shouldBe List(s.toList.foldLeft(n)(f))
      }

      "2" in forAll { (s: Stream[Pure, Int], n: String) =>
        val f = (a: String, b: Int) => a + b
        s.fold(n)(f).toList shouldBe List(s.toList.foldLeft(n)(f))
      }
    }

    "foldMonoid" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        s.foldMonoid.toVector shouldBe Vector(s.toVector.combineAll)
      }

      "2" in forAll { (s: Stream[Pure, Double]) =>
        s.foldMonoid.toVector shouldBe Vector(s.toVector.combineAll)
      }
    }

    "fold1" in forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      s.fold1(f).toVector shouldBe v.headOption.fold(Vector.empty[Int])(h =>
        Vector(v.drop(1).foldLeft(h)(f)))
    }

    "forall" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (i: Int) => i % n == 0
      s.forall(f).toList shouldBe List(s.toList.forall(f))
    }

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val stream: Stream[Fallible, Int] = Stream.fromEither[Fallible](either)
      either match {
        case Left(t)  => stream.toList shouldBe Left(t)
        case Right(i) => stream.toList shouldBe Right(List(i))
      }
    }

    "fromIterator" in forAll { x: List[Int] =>
      Stream
        .fromIterator[SyncIO, Int](x.iterator)
        .compile
        .toList
        .asserting(_ shouldBe x)
    }

    "groupAdjacentBy" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (i: Int) => i % n
      val s1 = s.groupAdjacentBy(f)
      val s2 = s.map(f).changes
      s1.map(_._2).toList.flatMap(_.toList) shouldBe s.toList
      s1.map(_._1).toList shouldBe s2.toList
      s1.map { case (k, vs) => vs.toVector.forall(f(_) == k) }.toList shouldBe s2
        .map(_ => true)
        .toList
    }

    "handleErrorWith" - {

      "1" in forAll { (s: Stream[Pure, Int]) =>
        val s2 = s.covary[Fallible] ++ Stream.raiseError[Fallible](new Err)
        s2.handleErrorWith(_ => Stream.empty).toList shouldBe Right(s.toList)
      }

      "2" in {
        Stream.raiseError[Fallible](new Err).handleErrorWith(_ => Stream(1)).toList shouldBe Right(
          List(1))
      }

      "3" in {
        Stream(1)
          .append(Stream.raiseError[Fallible](new Err))
          .handleErrorWith(_ => Stream(1))
          .toList shouldBe Right(List(1, 1))
      }

      "4 - error in eval" in {
        Stream
          .eval(SyncIO(throw new Err))
          .map(Right(_): Either[Throwable, Int])
          .handleErrorWith(t => Stream.emit(Left(t)).covary[SyncIO])
          .take(1)
          .compile
          .toVector
          .asserting(_.head.swap.toOption.get shouldBe an[Err])
      }

      "5" in {
        Stream
          .raiseError[SyncIO](new Err)
          .handleErrorWith(e => Stream(e))
          .flatMap(Stream.emit)
          .compile
          .toVector
          .asserting { v =>
            v should have size (1)
            v.head shouldBe an[Err]
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
            v should have size (1)
            v.head shouldBe an[Err]
          }
      }

      "7 - parJoin" in {
        Stream(Stream.emit(1).covary[IO], Stream.raiseError[IO](new Err), Stream.emit(2).covary[IO])
          .covary[IO]
          .parJoin(4)
          .attempt
          .compile
          .toVector
          .asserting(_.collect { case Left(t) => t }
            .find(_.isInstanceOf[Err])
            .isDefined shouldBe true)
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
            .asserting(_ => i shouldBe 0)
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
            .asserting(_ => i shouldBe 0)
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
            .asserting(_ => i shouldBe 0)
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
            .asserting(_ => i shouldBe 1)
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
            .asserting(_ => i shouldBe 1)
        }
      }

      "13" in {
        SyncIO.suspend {
          var i = 0
          Stream
            .range(0, 10)
            .covary[SyncIO]
            .append(Stream.raiseError[SyncIO](new Err))
            .handleErrorWith { t =>
              i += 1; Stream.empty
            }
            .compile
            .drain
            .asserting(_ => i shouldBe 1)
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
            .handleErrorWith { t =>
              i += 1; println(i); Pull.done
            }
            .stream
            .compile
            .drain
            .asserting(_ => i shouldBe 1)
        }
      }
    }

    "head" in forAll { (s: Stream[Pure, Int]) =>
      s.head.toList shouldBe s.toList.take(1)
    }

    "interleave" - {
      "interleave left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.interleave(s).toList shouldBe List("1", "A", "1", "B", "1", "C")
        s.interleave(ones).toList shouldBe List("A", "1", "B", "1", "C", "1")
      }

      "interleave both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.interleave(as).take(3).toList shouldBe List("1", "A", "1")
        as.interleave(ones).take(3).toList shouldBe List("A", "1", "A")
      }

      "interleaveAll left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.interleaveAll(s).take(9).toList shouldBe List("1",
                                                           "A",
                                                           "1",
                                                           "B",
                                                           "1",
                                                           "C",
                                                           "1",
                                                           "1",
                                                           "1")
        s.interleaveAll(ones).take(9).toList shouldBe List("A",
                                                           "1",
                                                           "B",
                                                           "1",
                                                           "C",
                                                           "1",
                                                           "1",
                                                           "1",
                                                           "1")
      }

      "interleaveAll both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.interleaveAll(as).take(3).toList shouldBe List("1", "A", "1")
        as.interleaveAll(ones).take(3).toList shouldBe List("A", "1", "A")
      }

      // Uses a small scope to avoid using time to generate too large streams and not finishing
      "interleave is equal to interleaveAll on infinite streams (by step-indexing)" in {
        forAll(intsBetween(0, 100)) { (n: Int) =>
          val ones = Stream.constant("1")
          val as = Stream.constant("A")
          ones.interleaveAll(as).take(n).toVector shouldBe ones
            .interleave(as)
            .take(n)
            .toVector
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
            .asserting(_ shouldBe Nil)
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
            .asserting(_ shouldBe Nil)
        }
      }

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
            .asserting(_ shouldBe s.toList)
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
            result.forall(i => i % 7 != 0) shouldBe true
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
            .asserting(_ shouldBe Nil)
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
          .asserting(_ shouldBe List(5))
      }

      "14a - interrupt evalMap and then resume on append" in {
        pending // Broken
        forAll { s: Stream[Pure, Int] =>
          val interrupt = IO.sleep(50.millis).attempt
          s.covary[IO]
            .interruptWhen(interrupt)
            .evalMap(_ => IO.never)
            .drain
            .append(s)
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
        }
      }

      "14b - interrupt evalMap+collect and then resume on append" in {
        pending // Broken
        forAll { s: Stream[Pure, Int] =>
          val interrupt = IO.sleep(50.millis).attempt
          s.covary[IO]
            .interruptWhen(interrupt)
            .evalMap(_ => IO.never.as(None))
            .append(s.map(Some(_)))
            .collect { case Some(v) => v }
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
        }
      }

      "15 - interruption works when flatMap is followed by collect" in {
        pending // Broken - results in an empty list instead of s.toList
        forAll { s: Stream[Pure, Int] =>
          val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
          s.covary[IO]
            .append(Stream(1))
            .interruptWhen(interrupt)
            .map(i => None)
            .append(s.map(Some(_)))
            .flatMap {
              case None    => Stream.eval(IO.never)
              case Some(i) => Stream.emit(Some(i))
            }
            .collect { case Some(i) => i }
            .compile
            .toList
            .asserting(_ shouldBe s.toList)
        }
      }

      "16 - if a pipe is interrupted, it will not restart evaluation" in {
        def p: Pipe[IO, Int, Int] = {
          def loop(acc: Int, s: Stream[IO, Int]): Pull[IO, Int, Unit] =
            s.pull.uncons1.flatMap {
              case None           => Pull.output1[IO, Int](acc)
              case Some((hd, tl)) => Pull.output1[IO, Int](hd) >> loop(acc + hd, tl)
            }
          in =>
            loop(0, in).stream
        }
        Stream
          .unfold(0)(i => Some((i, i + 1)))
          .flatMap(Stream.emit(_).delayBy[IO](10.millis))
          .interruptWhen(Stream.emit(true).delayBy[IO](150.millis))
          .through(p)
          .compile
          .toList
          .asserting(result =>
            result shouldBe (result.headOption.toList ++ result.tail.filter(_ != 0)))
      }

      "17 - minimal resume on append with pull" in {
        pending // Broken
        val interrupt = IO.sleep(100.millis).attempt
        Stream(1)
          .covary[IO]
          .unchunk
          .interruptWhen(interrupt)
          .pull
          .uncons
          .flatMap {
            case None           => Pull.done
            case Some((hd, tl)) => Pull.eval(IO.never)
          }
          .stream
          .append(Stream(5))
          .compile
          .toList
          .asserting(_ shouldBe List(5))
      }

      "18 - resume with append after evalMap interruption" in {
        pending // Broken
        Stream(1)
          .covary[IO]
          .interruptWhen(IO.sleep(50.millis).attempt)
          .evalMap(_ => IO.never)
          .append(Stream(5))
          .compile
          .toList
          .asserting(_ shouldBe List(5))
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
        pending // Results in an empty list
        forAll { s: Stream[Pure, Int] =>
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
            .asserting(_ shouldBe s.toList)
        }
      }

      "21 - nested-interrupt - interrupt in outer scope interrupts the inner scope" in {
        Stream
          .eval(IO.async[Unit](_ => ()))
          .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
          .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
          .compile
          .toList
          .asserting(_ shouldBe Nil)
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
          .asserting(_ shouldBe List(2))
      }
    }

    "intersperse" in forAll { (s: Stream[Pure, Int], n: Int) =>
      s.intersperse(n).toList shouldBe s.toList.flatMap(i => List(i, n)).dropRight(1)
    }

    "iterate" in {
      Stream.iterate(0)(_ + 1).take(100).toList shouldBe List.iterate(0, 100)(_ + 1)
    }

    "iterateEval" in {
      Stream
        .iterateEval(0)(i => IO(i + 1))
        .take(100)
        .compile
        .toVector
        .asserting(_ shouldBe List.iterate(0, 100)(_ + 1))
    }

    "last" in forAll { (s: Stream[Pure, Int]) =>
      val _ = s.last
      s.last.toList shouldBe List(s.toList.lastOption)
    }

    "lastOr" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      s.lastOr(n).toList shouldBe List(s.toList.lastOption.getOrElse(n))
    }

    "map" - {
      "map.toList == toList.map" in forAll { (s: Stream[Pure, Int], f: Int => Int) =>
        s.map(f).toList shouldBe s.toList.map(f)
      }

      "regression #1335 - stack safety of map" in {

        case class Tree[A](label: A, subForest: Stream[Pure, Tree[A]]) {
          def flatten: Stream[Pure, A] =
            Stream(this.label) ++ this.subForest.flatMap(_.flatten)
        }

        def unfoldTree(seed: Int): Tree[Int] =
          Tree(seed, Stream(seed + 1).map(unfoldTree))

        unfoldTree(1).flatten.take(10).toList shouldBe List.tabulate(10)(_ + 1)
      }
    }

    "mapAccumulate" in forAll { (s: Stream[Pure, Int], m: Int, n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (_: Int) % n == 0
      val r = s.mapAccumulate(m)((s, i) => (s + i, f(i)))

      r.map(_._1).toList shouldBe s.toList.scanLeft(m)(_ + _).tail
      r.map(_._2).toList shouldBe s.toList.map(f)
    }

    "mapAsync" - {
      "same as map" in forAll { s: Stream[Pure, Int] =>
        val f = (_: Int) + 1
        val r = s.covary[IO].mapAsync(16)(i => IO(f(i)))
        r.compile.toVector.asserting(_ shouldBe s.toVector.map(f))
      }

      "exception" in forAll { s: Stream[Pure, Int] =>
        val f = (_: Int) => IO.raiseError[Int](new RuntimeException)
        val r = (s ++ Stream(1)).covary[IO].mapAsync(1)(f).attempt
        r.compile.toVector.asserting(_.size shouldBe 1)
      }
    }

    "mapAsyncUnordered" in forAll { s: Stream[Pure, Int] =>
      val f = (_: Int) + 1
      val r = s.covary[IO].mapAsyncUnordered(16)(i => IO(f(i)))
      r.compile.toVector.asserting(_ should contain theSameElementsAs s.toVector.map(f))
    }

    "mapChunks" in forAll { (s: Stream[Pure, Int]) =>
      s.mapChunks(identity).chunks.toList shouldBe s.chunks.toList
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
                    out.sum shouldBe sum
                }
            }
          }

          "handle errors from observing sink" in {
            forAll { (s: Stream[Pure, Int]) =>
              observer(s.covary[IO])(_ => Stream.raiseError[IO](new Err)).attempt.compile.toList
                .asserting { result =>
                  result should have size (1)
                  result.head
                    .fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
                }
            }
          }

          "propagate error from source" in {
            forAll { (s: Stream[Pure, Int]) =>
              observer(s.drain ++ Stream.raiseError[IO](new Err))(_.drain).attempt.compile.toList
                .asserting { result =>
                  result should have size (1)
                  result.head
                    .fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
                }
            }
          }

          "handle finite observing sink" - {
            "1" in forAll { (s: Stream[Pure, Int]) =>
              observer(s.covary[IO])(_ => Stream.empty).compile.toList.asserting(_ shouldBe Nil)
            }
            "2" in forAll { (s: Stream[Pure, Int]) =>
              observer(Stream(1, 2) ++ s.covary[IO])(_.take(1).drain).compile.toList
                .asserting(_ shouldBe Nil)
            }
          }

          "handle multiple consecutive observations" in {
            forAll { (s: Stream[Pure, Int]) =>
              val sink: Pipe[IO, Int, Unit] = _.evalMap(i => IO.unit)
              observer(observer(s.covary[IO])(sink))(sink).compile.toList
                .asserting(_ shouldBe s.toList)
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
        def apply[F[_]: Concurrent, O](s: Stream[F, O])(
            observation: Pipe[F, O, Unit]): Stream[F, O] =
          s.observe(observation)
      })

      observationTests(
        "observeAsync",
        new Observer {
          def apply[F[_]: Concurrent, O](s: Stream[F, O])(
              observation: Pipe[F, O, Unit]): Stream[F, O] =
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
              .asserting(_ shouldBe List(1))
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
              .asserting(_ shouldBe List(1, 2))
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
            .asserting(r => (r should have).length(0))
        }

        "right" in {
          s.observeEither[Int, String](_.void, _.take(0).void)
            .compile
            .toList
            .asserting(r => (r should have).length(0))
        }
      }
    }

    "prefetch" - {
      "identity" in forAll { (s: Stream[Pure, Int]) =>
        s.covary[IO].prefetch.compile.toList.asserting(_ shouldBe s.toList)
      }

      "timing" in {
        // should finish in about 3-4 seconds
        IO.suspend {
          val start = System.currentTimeMillis
          Stream(1, 2, 3)
            .evalMap(i => IO { Thread.sleep(1000); i })
            .prefetch
            .flatMap { i =>
              Stream.eval(IO { Thread.sleep(1000); i })
            }
            .compile
            .toList
            .asserting { _ =>
              val stop = System.currentTimeMillis
              val elapsed = stop - start
              elapsed should be < 6000L
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
          first should not be second
      }
    }

    "randomSeeded" in {
      val x = Stream.randomSeeded(1L).take(100).toList
      val y = Stream.randomSeeded(1L).take(100).toList
      x shouldBe y
    }

    "range" in {
      Stream.range(0, 100).toList shouldBe List.range(0, 100)
      Stream.range(0, 1).toList shouldBe List.range(0, 1)
      Stream.range(0, 0).toList shouldBe List.range(0, 0)
      Stream.range(0, 101, 2).toList shouldBe List.range(0, 101, 2)
      Stream.range(5, 0, -1).toList shouldBe List.range(5, 0, -1)
      Stream.range(5, 0, 1).toList shouldBe Nil
      Stream.range(10, 50, 0).toList shouldBe Nil
    }

    "ranges" in forAll(intsBetween(1, 101)) { size =>
      Stream
        .ranges(0, 100, size)
        .flatMap { case (i, j) => Stream.emits(i until j) }
        .toVector shouldBe IndexedSeq.range(0, 100)
    }

    "repartition" in {
      Stream("Lore", "m ip", "sum dolo", "r sit amet")
        .repartition(s => Chunk.array(s.split(" ")))
        .toList shouldBe
        List("Lorem", "ipsum", "dolor", "sit", "amet")
      Stream("hel", "l", "o Wor", "ld")
        .repartition(s => Chunk.indexedSeq(s.grouped(2).toVector))
        .toList shouldBe
        List("he", "ll", "o ", "Wo", "rl", "d")
      Stream.empty
        .covaryOutput[String]
        .repartition(_ => Chunk.empty)
        .toList shouldBe List()
      Stream("hello").repartition(_ => Chunk.empty).toList shouldBe List()

      def input = Stream("ab").repeat
      def ones(s: String) = Chunk.vector(s.grouped(1).toVector)
      input.take(2).repartition(ones).toVector shouldBe Vector("a", "b", "a", "b")
      input.take(4).repartition(ones).toVector shouldBe Vector("a",
                                                               "b",
                                                               "a",
                                                               "b",
                                                               "a",
                                                               "b",
                                                               "a",
                                                               "b")
      input.repartition(ones).take(2).toVector shouldBe Vector("a", "b")
      input.repartition(ones).take(4).toVector shouldBe Vector("a", "b", "a", "b")
      Stream
        .emits(input.take(4).toVector)
        .repartition(ones)
        .toVector shouldBe Vector("a", "b", "a", "b", "a", "b", "a", "b")

      Stream(1, 2, 3, 4, 5).repartition(i => Chunk(i, i)).toList shouldBe List(1, 3, 6, 10, 15, 15)

      Stream(1, 10, 100)
        .repartition(i => Chunk.seq(1 to 1000))
        .take(4)
        .toList shouldBe List(1, 2, 3, 4)
    }

    "repeat" in {
      forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
        (n: Int, testValues: List[Int]) =>
          Stream.emits(testValues).repeat.take(n).toList shouldBe List
            .fill(n / testValues.size + 1)(testValues)
            .flatten
            .take(n)
      }
    }

    "repeatN" in {
      forAll(intsBetween(1, 200), lists[Int].havingSizesBetween(1, 200)) {
        (n: Int, testValues: List[Int]) =>
          Stream.emits(testValues).repeatN(n).toList shouldBe List.fill(n)(testValues).flatten
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
            attempts shouldBe 1
            r shouldBe List("success")
          }
        }
      }

      "eventual success" in {
        IO.suspend {
          var failures, successes = 0
          val job = IO {
            if (failures == 5) { successes += 1; "success" } else {
              failures += 1; throw RetryErr()
            }
          }
          Stream.retry(job, 100.millis, x => x, 100).compile.toList.asserting { r =>
            failures shouldBe 5
            successes shouldBe 1
            r shouldBe List("success")
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
              failures shouldBe 5
              msg shouldBe "5"
            case other => fail("Expected a RetryErr")
          }
        }
      }

      "fatal" in {
        IO.suspend {
          var failures, successes = 0
          val job = IO {
            if (failures == 5) { failures += 1; throw RetryErr("fatal") } else if (failures > 5) {
              successes += 1; "success"
            } else { failures += 1; throw RetryErr() }
          }
          val f: Throwable => Boolean = _.getMessage != "fatal"
          Stream.retry(job, 100.millis, x => x, 100, f).compile.drain.attempt.asserting {
            case Left(RetryErr(msg)) =>
              failures shouldBe 6
              successes shouldBe 0
              msg shouldBe "fatal"
            case other => fail("Expected a RetryErr")
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
            getDelays shouldBe List.range(1, maxTries)
          case other => fail("Expected a RetryErr")
        }
      }
    }

    "scan" - {
      "1" in forAll { (s: Stream[Pure, Int], n: Int) =>
        val f = (a: Int, b: Int) => a + b
        s.scan(n)(f).toList shouldBe s.toList.scanLeft(n)(f)
      }

      "2" in {
        val s = Stream(1).map(x => x)
        val f = (a: Int, b: Int) => a + b
        s.scan(0)(f).toList shouldBe s.toList.scanLeft(0)(f)
      }

      "temporal" in {
        val never = Stream.eval(IO.async[Int](_ => ()))
        val s = Stream(1)
        val f = (a: Int, b: Int) => a + b
        val result = s.toList.scanLeft(0)(f)
        s.append(never).scan(0)(f).take(result.size).compile.toList.asserting(_ shouldBe result)
      }
    }

    "scan1" in forAll { (s: Stream[Pure, Int]) =>
      val v = s.toVector
      val f = (a: Int, b: Int) => a + b
      s.scan1(f).toVector shouldBe v.headOption.fold(Vector.empty[Int])(h =>
        v.drop(1).scanLeft(h)(f))
    }

    "scope" - {
      "1" in {
        val c = new java.util.concurrent.atomic.AtomicLong(0)
        val s1 = Stream.emit("a").covary[IO]
        val s2 = Stream
          .bracket(IO { c.incrementAndGet() shouldBe 1L; () }) { _ =>
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
          .asserting(_ => c.get shouldBe 0L)
      }

      "2" in {
        Stream
          .eval(Ref.of[IO, Int](0))
          .flatMap { ref =>
            Stream(1).flatMap { i =>
              Stream
                .bracket(ref.update(_ + 1))(_ => ref.update(_ - 1))
                .flatMap(_ => Stream.eval(ref.get)) ++ Stream.eval(ref.get)
            }.scope ++ Stream.eval(ref.get)
          }
          .compile
          .toList
          .asserting(_ shouldBe List(1, 1, 0))
      }
    }

    "sliding" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      s.sliding(n).toList.map(_.toList) shouldBe s.toList
        .sliding(n)
        .map(_.toList)
        .toList
    }

    "split" - {
      "1" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
        val n = n0 % 20 + 1
        val s2 = s
          .map(x => if (x == Int.MinValue) x + 1 else x)
          .map(_.abs)
          .filter(_ != 0)
        withClue(s"n = $n, s = ${s.toList}, s2 = " + s2.toList) {
          s2.chunkLimit(n)
            .intersperse(Chunk.singleton(0))
            .flatMap(Stream.chunk)
            .split(_ == 0)
            .map(_.toVector)
            .filter(_.nonEmpty)
            .toVector shouldBe s2.chunkLimit(n).filter(_.nonEmpty).map(_.toVector).toVector
        }
      }

      "2" in {
        Stream(1, 2, 0, 0, 3, 0,
          4).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1, 2),
                                                                    Vector(),
                                                                    Vector(3),
                                                                    Vector(4))
        Stream(1, 2, 0, 0, 3, 0).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1,
                                                                                               2),
                                                                                        Vector(),
                                                                                        Vector(3))
        Stream(1, 2, 0, 0, 3, 0,
          0).split(_ == 0).toVector.map(_.toVector) shouldBe Vector(Vector(1, 2),
                                                                    Vector(),
                                                                    Vector(3),
                                                                    Vector())
      }
    }

    "tail" in forAll { (s: Stream[Pure, Int]) =>
      s.tail.toList shouldBe s.toList.drop(1)
    }

    "take" - {
      "identity" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosInt) =>
        val n1 = n0 % 20 + 1
        val n = if (negate) -n1 else n1
        s.take(n).toList shouldBe s.toList.take(n)
      }
      "chunks" in {
        val s = Stream(1, 2) ++ Stream(3, 4)
        s.take(3).chunks.map(_.toList).toList shouldBe List(List(1, 2), List(3))
      }
    }

    "takeRight" in forAll { (s: Stream[Pure, Int], negate: Boolean, n0: PosInt) =>
      val n1 = n0 % 20 + 1
      val n = if (negate) -n1 else n1
      s.takeRight(n).toList shouldBe s.toList.takeRight(n)
    }

    "takeWhile" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val set = s.toList.take(n).toSet
      s.takeWhile(set).toList shouldBe s.toList.takeWhile(set)
    }

    "takeThrough" in forAll { (s: Stream[Pure, Int], n0: PosInt) =>
      val n = n0 % 20 + 1
      val f = (i: Int) => i % n == 0
      val vec = s.toVector
      val result = vec.takeWhile(f) ++ vec.dropWhile(f).headOption
      withClue(vec)(s.takeThrough(f).toVector shouldBe result)
    }

    "translate" - {
      "1 - id" in forAll { (s: Stream[Pure, Int]) =>
        s.covary[SyncIO]
          .flatMap(i => Stream.eval(SyncIO.pure(i)))
          .translate(cats.arrow.FunctionK.id[SyncIO])
          .compile
          .toList
          .asserting(_ shouldBe s.toList)
      }

      "2" in forAll { (s: Stream[Pure, Int]) =>
        s.covary[Function0]
          .flatMap(i => Stream.eval(() => i))
          .flatMap(i => Stream.eval(() => i))
          .translate(new (Function0 ~> SyncIO) {
            def apply[A](thunk: Function0[A]) = SyncIO(thunk())
          })
          .compile
          .toList
          .asserting(_ shouldBe s.toList)
      }

      "3 - ok to have multiple translates" in forAll { (s: Stream[Pure, Int]) =>
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
          .asserting(_ shouldBe s.toList)
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
          .asserting(_ shouldBe List((1, 1)))
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
          .asserting(_ shouldBe List(1, 2))
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
          .asserting(_ shouldBe List(2, 3, 3, 4))
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
          .asserting(_ shouldBe List(1, 2))
      }

      "stack safety" in {
        Stream
          .repeatEval(SyncIO(0))
          .translate(new (SyncIO ~> SyncIO) { def apply[X](x: SyncIO[X]) = SyncIO.suspend(x) })
          .take(1000000)
          .compile
          .drain
          .assertNoException
      }
    }

    "unfold" in {
      Stream
        .unfold((0, 1)) {
          case (f1, f2) =>
            if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
        }
        .map(_._1)
        .toList shouldBe List(0, 1, 1, 2, 3, 5, 8, 13)
    }

    "unfoldChunk" in {
      Stream
        .unfoldChunk(4L) { s =>
          if (s > 0) Some((Chunk.longs(Array[Long](s, s)), s - 1))
          else None
        }
        .toList shouldBe List[Long](4, 4, 3, 3, 2, 2, 1, 1)
    }

    "unfoldEval" in {
      Stream
        .unfoldEval(10)(s => IO.pure(if (s > 0) Some((s, s - 1)) else None))
        .compile
        .toList
        .asserting(_ shouldBe List.range(10, 0, -1))
    }

    "unfoldChunkEval" in {
      Stream
        .unfoldChunkEval(true)(s =>
          SyncIO.pure(if (s) Some((Chunk.booleans(Array[Boolean](s)), false)) else None))
        .compile
        .toList
        .asserting(_ shouldBe List(true))
    }

    "unNone" in forAll { (s: Stream[Pure, Option[Int]]) =>
      s.unNone.chunks.toList shouldBe s.filter(_.isDefined).map(_.get).chunks.toList
    }

    "zip" - {
      "propagate error from closing the root scope" in {
        val s1 = Stream.bracket(IO(1))(_ => IO.unit)
        val s2 = Stream.bracket(IO("a"))(_ => IO.raiseError(new Err))

        val r1 = s1.zip(s2).compile.drain.attempt.unsafeRunSync()
        r1.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
        val r2 = s2.zip(s1).compile.drain.attempt.unsafeRunSync()
        r2.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
      }

      "issue #941 - scope closure issue" in {
        Stream(1, 2, 3)
          .map(_ + 1)
          .repeat
          .zip(Stream(4, 5, 6).map(_ + 1).repeat)
          .take(4)
          .toList shouldBe List((2, 5), (3, 6), (4, 7), (2, 5))
      }

      "zipWith left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zipWith(s)(_ + _).toList shouldBe List("1A", "1B", "1C")
        s.zipWith(ones)(_ + _).toList shouldBe List("A1", "B1", "C1")
      }

      "zipWith both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zipWith(as)(_ + _).take(3).toList shouldBe List("1A", "1A", "1A")
        as.zipWith(ones)(_ + _).take(3).toList shouldBe List("A1", "A1", "A1")
      }

      "zipAllWith left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zipAllWith(s)("2", "Z")(_ + _).take(5).toList shouldBe
          List("1A", "1B", "1C", "1Z", "1Z")
        s.zipAllWith(ones)("Z", "2")(_ + _).take(5).toList shouldBe
          List("A1", "B1", "C1", "Z1", "Z1")
      }

      "zipAllWith both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zipAllWith(as)("2", "Z")(_ + _).take(3).toList shouldBe
          List("1A", "1A", "1A")
        as.zipAllWith(ones)("Z", "2")(_ + _).take(3).toList shouldBe
          List("A1", "A1", "A1")
      }

      "zip left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zip(s).toList shouldBe List("1" -> "A", "1" -> "B", "1" -> "C")
        s.zip(ones).toList shouldBe List("A" -> "1", "B" -> "1", "C" -> "1")
      }

      "zip both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zip(as).take(3).toList shouldBe List("1" -> "A", "1" -> "A", "1" -> "A")
        as.zip(ones).take(3).toList shouldBe List("A" -> "1", "A" -> "1", "A" -> "1")
      }

      "zipAll left/right side infinite" in {
        val ones = Stream.constant("1")
        val s = Stream("A", "B", "C")
        ones.zipAll(s)("2", "Z").take(5).toList shouldBe List("1" -> "A",
                                                              "1" -> "B",
                                                              "1" -> "C",
                                                              "1" -> "Z",
                                                              "1" -> "Z")
        s.zipAll(ones)("Z", "2").take(5).toList shouldBe List("A" -> "1",
                                                              "B" -> "1",
                                                              "C" -> "1",
                                                              "Z" -> "1",
                                                              "Z" -> "1")
      }

      "zipAll both side infinite" in {
        val ones = Stream.constant("1")
        val as = Stream.constant("A")
        ones.zipAll(as)("2", "Z").take(3).toList shouldBe List("1" -> "A", "1" -> "A", "1" -> "A")
        as.zipAll(ones)("Z", "2").take(3).toList shouldBe List("A" -> "1", "A" -> "1", "A" -> "1")
      }

      "zip with scopes" in {
        // this tests that streams opening resources on each branch will close
        // scopes independently.
        val s = Stream(0).scope
        (s ++ s).zip(s).toList shouldBe List((0, 0))
      }

      "issue #1120 - zip with uncons" in {
        // this tests we can properly look up scopes for the zipped streams
        val rangeStream = Stream.emits((0 to 3).toList)
        rangeStream.zip(rangeStream).attempt.map(identity).toVector shouldBe Vector(
          Right((0, 0)),
          Right((1, 1)),
          Right((2, 2)),
          Right((3, 3))
        )
      }
    }

    "zipWithIndex" in forAll { (s: Stream[Pure, Int]) =>
      s.zipWithIndex.toList shouldBe s.toList.zipWithIndex
    }

    "zipWithNext" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        s.zipWithNext.toList shouldBe {
          val xs = s.toList
          xs.zipAll(xs.map(Some(_)).drop(1), -1, None)
        }
      }

      "2" in {
        Stream().zipWithNext.toList shouldBe Nil
        Stream(0).zipWithNext.toList shouldBe List((0, None))
        Stream(0, 1, 2).zipWithNext.toList shouldBe List((0, Some(1)), (1, Some(2)), (2, None))
      }
    }

    "zipWithPrevious" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        s.zipWithPrevious.toList shouldBe {
          val xs = s.toList
          (None +: xs.map(Some(_))).zip(xs)
        }
      }

      "2" in {
        Stream().zipWithPrevious.toList shouldBe Nil
        Stream(0).zipWithPrevious.toList shouldBe List((None, 0))
        Stream(0, 1, 2).zipWithPrevious.toList shouldBe List((None, 0), (Some(0), 1), (Some(1), 2))
      }
    }

    "zipWithPreviousAndNext" - {
      "1" in forAll { (s: Stream[Pure, Int]) =>
        s.zipWithPreviousAndNext.toList shouldBe {
          val xs = s.toList
          val zipWithPrevious = (None +: xs.map(Some(_))).zip(xs)
          val zipWithPreviousAndNext = zipWithPrevious
            .zipAll(xs.map(Some(_)).drop(1), (None, -1), None)
            .map { case ((prev, that), next) => (prev, that, next) }
          zipWithPreviousAndNext
        }
      }

      "2" in {
        Stream().zipWithPreviousAndNext.toList shouldBe Nil
        Stream(0).zipWithPreviousAndNext.toList shouldBe List((None, 0, None))
        Stream(0, 1, 2).zipWithPreviousAndNext.toList shouldBe List((None, 0, Some(1)),
                                                                    (Some(0), 1, Some(2)),
                                                                    (Some(1), 2, None))
      }
    }

    "zipWithScan" in {
      Stream("uno", "dos", "tres", "cuatro")
        .zipWithScan(0)(_ + _.length)
        .toList shouldBe List("uno" -> 0, "dos" -> 3, "tres" -> 6, "cuatro" -> 10)
      Stream().zipWithScan(())((acc, i) => ???).toList shouldBe Nil
    }

    "zipWithScan1" in {
      Stream("uno", "dos", "tres", "cuatro")
        .zipWithScan1(0)(_ + _.length)
        .toList shouldBe List("uno" -> 3, "dos" -> 6, "tres" -> 10, "cuatro" -> 16)
      Stream().zipWithScan1(())((acc, i) => ???).toList shouldBe Nil
    }

    "regressions" - {

      "#1089" in {
        (Stream.chunk(Chunk.bytes(Array.fill(2000)(1.toByte))) ++ Stream.eval(
          IO.async[Byte](_ => ())))
          .take(2000)
          .chunks
          .compile
          .toVector
          .assertNoException
      }

      "#1107 - scope" in {
        Stream(0)
          .covary[IO]
          .scope // Create a source that opens/closes a new scope for every element emitted
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
