package fs2.tagless

import fs2.{Chunk, ChunkGen}

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
      def bracketTest[F[_]: Sync, A](use: Stream[F, A]): F[Unit] =
        for {
          acquires <- Ref.of[F, Long](0)
          releases <- Ref.of[F, Long](0)
          _ <- Stream.bracket(acquires.update(_ + 1))(_ => releases.update(_ + 1)).
            evalMap(_ => (acquires.get, releases.get).tupled.asserting { case (totalAcquires, totalReleases) =>
              totalAcquires shouldBe 1
              totalReleases shouldBe 0
            }).
            flatMap(_ => use).
            compile.drain.handleErrorWith { case t: Err => Sync[F].pure(()) }
          _ <- (acquires.get, releases.get).tupled.asserting { case (totalAcquires, totalReleases) =>
            totalAcquires shouldBe 1
            totalReleases shouldBe 1
          }
        } yield ()

      "normal termination" in runSyncIO { bracketTest[SyncIO, Unit](Stream.empty) }
      "failure" in runSyncIO { bracketTest[SyncIO, Unit](Stream.raiseError[SyncIO](new Err)) }
      "throw from append" in runSyncIO { bracketTest(Stream(1, 2, 3) ++ ((throw new Err): Stream[SyncIO, Int])) }
    }
  }
}
