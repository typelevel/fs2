package fs2.tagless

import fs2.{Chunk, ChunkGen, Pure}

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._

import org.scalatest.{Assertion, FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class TaglessSpec extends FreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with ChunkGen {

  implicit class Asserting[F[_], A](private val self: F[A]) {
    def asserting(f: A => Assertion)(implicit F: Sync[F]): F[Assertion] =
      self.flatMap(a => F.delay(f(a)))
  }

  private def runSyncIO[A](ioa: SyncIO[A]): A = ioa.unsafeRunSync

  class Err extends RuntimeException("Simulated exception")

  "Stream" - {
    "apply" in { Stream(1, 2, 3).toList shouldBe List(1, 2, 3) }
    "chunk" in { forAll { (c: Chunk[Int]) => Stream.chunk(c).toChunk shouldBe c } }
    "eval" in runSyncIO { Stream.eval(SyncIO(23)).compile.toList.asserting(_ shouldBe List(23)) }

    "bracket" - {
      sealed trait BracketEvent
      final case object Acquired extends BracketEvent
      final case object Released extends BracketEvent

      def recordBracketEvents[F[_]](events: Ref[F, Vector[BracketEvent]]): Stream[F, Unit] =
        Stream.bracket(events.update(evts => evts :+ Acquired))(_ => events.update(evts => evts :+ Released))

      "single bracket" - {
        def singleBracketTest[F[_]: Sync, A](use: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events).
              evalMap(_ => events.get.asserting { events => events shouldBe Vector(Acquired) }).
              flatMap(_ => use).
              compile.drain.handleErrorWith { case t: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { _ shouldBe Vector(Acquired, Released) }
          } yield ()

        "normal termination" in runSyncIO { singleBracketTest[SyncIO, Unit](Stream.empty) }
        "failure" in runSyncIO { singleBracketTest[SyncIO, Unit](Stream.raiseError[SyncIO](new Err)) }
        "throw from append" in runSyncIO { singleBracketTest(Stream(1, 2, 3) ++ ((throw new Err): Stream[SyncIO, Int])) }
      }

      "bracket.scope ++ bracket" - {
        def appendBracketTest[F[_]: Sync, A](use1: Stream[F, A], use2: Stream[F, A]): F[Unit] =
          for {
            events <- Ref.of[F, Vector[BracketEvent]](Vector.empty)
            _ <- recordBracketEvents(events).scope.flatMap(_ => use1).append(recordBracketEvents(events).flatMap(_ => use2)).
              compile.drain.handleErrorWith { case t: Err => Sync[F].pure(()) }
            _ <- events.get.asserting { _ shouldBe Vector(Acquired, Released, Acquired, Released) }
          } yield ()

        "normal termination" in runSyncIO { appendBracketTest[SyncIO, Unit](Stream.empty, Stream.empty) }
        "failure" in runSyncIO { appendBracketTest[SyncIO, Unit](Stream.empty, Stream.raiseError[SyncIO](new Err)) }
      }
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
          .toList
      }
    }
  }
}
