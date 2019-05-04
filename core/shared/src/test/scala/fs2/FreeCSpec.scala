package fs2
package internal

import cats.Eq
import cats.implicits._
import cats.effect.IO
import cats.effect.laws.discipline.SyncTests
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import org.scalacheck.{Arbitrary, Cogen, Gen}
import Arbitrary.{arbFunction1, arbitrary}
import fs2.internal.FreeC.Result

class FreeCSpec extends Fs2Spec {

  implicit val tc: TestContext = TestContext()

  implicit def arbFreeC[O: Arbitrary: Cogen]: Arbitrary[FreeC[IO, O]] =
    Arbitrary(freeGen[IO, O](4))

  // Adapted from Cats, under MIT License (https://github.com/typelevel/cats/blob/ef05c76d92076f7ba0e1589b8a736cb973a9f610/COPYING)
  // from commit ef05c76d92076f7ba0e1589b8a736cb973a9f610 - only change is addition of Fail nodes and replaced flatMap with transformWith
  private def freeGen[F[_], A](maxDepth: Int)(implicit F: Arbitrary[F[A]], A: Arbitrary[A]): Gen[FreeC[F, A]] = {
    val noBinds = Gen.oneOf(
      A.arbitrary.map(FreeC.pure[F, A](_)),
      F.arbitrary.map(FreeC.eval[F, A](_)),
      arbitrary[Throwable].map(FreeC.raiseError[F, A](_))
    )

    val nextDepth = Gen.chooseNum(1, math.max(1, maxDepth - 1))

    def withBinds = for {
      fDepth <- nextDepth
      freeDepth <- nextDepth
      f <- arbFunction1[Result[A], FreeC[F, A]](Arbitrary(freeGen[F, A](fDepth)), implicitly[Cogen[Unit]].contramap(_ => ())).arbitrary
      freeFA <- freeGen[F, A](freeDepth)
    } yield freeFA.transformWith(f)

    if (maxDepth <= 1) noBinds
    else Gen.oneOf(noBinds, withBinds)
  }

  implicit def eqFreeCAlg[O: Eq]: Eq[FreeC[IO, O]] = Eq.instance((x, y) => Eq.eqv(x.run, y.run))

  "FreeC" - {
    checkAll("Sync[FreeC[F, ?]]", SyncTests[FreeC[IO, ?]].sync[Int, Int, Int])
  }
}
