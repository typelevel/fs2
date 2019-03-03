package fs2

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._

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

    "chunk" in {
      forAll { (c: Chunk[Int]) =>
        Stream.chunk(c).toChunk shouldBe c
      }
    }

    "eval" in { Stream.eval(SyncIO(23)).compile.toList.asserting(_ shouldBe List(23)) }

    "flatMap" in forAll { (s: Stream[Pure, Stream[Pure, Int]]) =>
      s.flatMap(inner => inner).toList shouldBe s.toList.flatMap(inner => inner.toList)
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

    "map" - {
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
    }
  }
}
