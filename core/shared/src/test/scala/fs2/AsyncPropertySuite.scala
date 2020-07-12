package fs2

import scala.concurrent.Future

import cats.data.State
import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._

import munit.{Location, ScalaCheckSuite}

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.rng.Seed

trait AsyncPropertySupport extends ScalaCheckSuite {

  private def samples[X](
      g: Gen[X],
      params: Gen.Parameters = Gen.Parameters.default
  ): State[Seed, List[X]] = {
    def go(acc: List[X], rem: Int, seed: Seed): (Seed, List[X]) =
      if (rem <= 0) (seed, acc.reverse)
      else {
        val r = g.doPureApply(params, seed)
        r.retrieve match {
          case Some(x) => go(x :: acc, rem - 1, r.seed)
          case None    => go(acc, rem, r.seed)
        }
      }
    State(s => go(Nil, scalaCheckTestParameters.minSuccessfulTests, s))
  }

  private def reportPropertyFailure[F[_]](f: F[Unit], seed: Seed, describe: => String)(implicit
      F: Effect[F], loc: Location
  ): F[Unit] =
    f.handleErrorWith { t =>
      fail(s"Property failed with seed ${seed.toBase64} and params: " + describe, t)
    }

  def forAllAsync[F[_], A](f: A => F[Unit])(implicit arbA: Arbitrary[A], F: Effect[F], loc: Location): Future[Unit] = {
    val seed = Seed.random()
    samples(arbA.arbitrary)
      .runA(seed)
      .value
      .traverse_(a => reportPropertyFailure(f(a), seed, a.toString))
      .toIO
      .unsafeToFuture
  }

  def forAllAsync[F[_], A, B](
      f: (A, B) => F[Unit]
  )(implicit arbA: Arbitrary[A], arbB: Arbitrary[B], F: Effect[F], loc: Location): Future[Unit] = {
    val all = for {
      as <- samples(arbA.arbitrary)
      bs <- samples(arbB.arbitrary)
    } yield as.zip(bs)
    val seed = Seed.random()
    all
      .runA(seed)
      .value
      .traverse_ {
        case (a, b) => reportPropertyFailure(f(a, b), seed, s"($a, $b)")
      }
      .toIO
      .unsafeToFuture
  }

  def forAllAsync[F[_], A, B, C](f: (A, B, C) => F[Unit])(implicit
      arbA: Arbitrary[A],
      arbB: Arbitrary[B],
      arbC: Arbitrary[C],
      F: Effect[F], 
      loc: Location
  ): Future[Unit] = {
    val all = for {
      as <- samples(arbA.arbitrary)
      bs <- samples(arbB.arbitrary)
      cs <- samples(arbC.arbitrary)
    } yield as.zip(bs).zip(cs)
    val seed = Seed.random()
    all
      .runA(seed)
      .value
      .traverse_ {
        case ((a, b), c) => reportPropertyFailure(f(a, b, c), seed, s"($a, $b, $c)")
      }
      .toIO
      .unsafeToFuture
  }

  def forAllAsync[F[_], A, B, C, D](f: (A, B, C, D) => F[Unit])(implicit
      arbA: Arbitrary[A],
      arbB: Arbitrary[B],
      arbC: Arbitrary[C],
      arbD: Arbitrary[D],
      F: Effect[F], 
      loc: Location
  ): Future[Unit] = {
    val all = for {
      as <- samples(arbA.arbitrary)
      bs <- samples(arbB.arbitrary)
      cs <- samples(arbC.arbitrary)
      ds <- samples(arbD.arbitrary)
    } yield as.zip(bs).zip(cs).zip(ds)
    val seed = Seed.random()
    all
      .runA(seed)
      .value
      .traverse_ {
        case (((a, b), c), d) => reportPropertyFailure(f(a, b, c, d), seed, s"($a, $b, $c, $d)")
      }
      .toIO
      .unsafeToFuture
  }

  def forAllAsync[F[_], A, B, C, D, E](f: (A, B, C, D, E) => F[Unit])(implicit
      arbA: Arbitrary[A],
      arbB: Arbitrary[B],
      arbC: Arbitrary[C],
      arbD: Arbitrary[D],
      arbE: Arbitrary[E],
      F: Effect[F], 
      loc: Location
  ): Future[Unit] = {
    val all = for {
      as <- samples(arbA.arbitrary)
      bs <- samples(arbB.arbitrary)
      cs <- samples(arbC.arbitrary)
      ds <- samples(arbD.arbitrary)
      es <- samples(arbE.arbitrary)
    } yield as.zip(bs).zip(cs).zip(ds).zip(es)
    val seed = Seed.random()
    all
      .runA(seed)
      .value
      .traverse_ {
        case ((((a, b), c), d), e) =>
          reportPropertyFailure(f(a, b, c, d, e), seed, s"($a, $b, $c, $d, $e)")
      }
      .toIO
      .unsafeToFuture
  }
}
