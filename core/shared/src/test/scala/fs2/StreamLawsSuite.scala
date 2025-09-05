/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.{Applicative, Eq, ~>}
import cats.data.{IdT, OptionT}
import cats.effect.{Concurrent, IO, Ref, Resource}
import cats.effect.testkit.TestInstances
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import cats.mtl.LiftValue
import cats.mtl.laws.discipline.{LiftKindTests, LiftValueTests}
import org.scalacheck.{Arbitrary, Gen}

class StreamLawsSuite extends Fs2Suite with TestInstances {
  implicit val ticker: Ticker = Ticker()

  implicit def eqStream[F[_], O](implicit
      F: Concurrent[F],
      eqFVecEitherThrowO: Eq[F[Vector[Either[Throwable, O]]]]
  ): Eq[Stream[F, O]] =
    Eq.by((_: Stream[F, O]).attempt.compile.toVector)

  private[this] val counter: IO[Ref[IO, Int]] = IO.ref(0)

  implicit val arbitraryScope: Arbitrary[IO ~> IO] =
    Arbitrary {
      Gen.const {
        new (IO ~> IO) {
          def apply[A](fa: IO[A]): IO[A] =
            for {
              ref <- counter
              res <- ref.update(_ + 1) >> fa
            } yield res
        }
      }
    }

  checkAll(
    "MonadError[Stream[F, *], Throwable]",
    MonadErrorTests[Stream[IO, *], Throwable].monadError[Int, Int, Int]
  )
  checkAll(
    "FunctorFilter[Stream[F, *]]",
    FunctorFilterTests[Stream[IO, *]].functorFilter[String, Int, Int]
  )
  checkAll("MonoidK[Stream[F, *]]", MonoidKTests[Stream[IO, *]].monoidK[Int])
  checkAll("Defer[Stream[F, *]]", DeferTests[Stream[IO, *]].defer[Int])
  checkAll(
    "Align[Stream[F, *]]",
    AlignTests[Stream[IO, *]].align[Int, Int, Int, Int]
  )
  checkAll(
    "LiftKind[IO, Stream[IO, *]",
    LiftKindTests[IO, Stream[IO, *]].liftKind[Int, Int]
  )
  checkAll(
    "LiftKind[IO, Stream[OptionT[IO, *], *]",
    LiftKindTests[IO, Stream[OptionT[IO, *], *]].liftKind[Int, Int]
  )
  checkAll(
    "LiftValue[Resource[IO, *], Stream[IO, *]",
    LiftValueTests[Resource[IO, *], Stream[IO, *]].liftValue[Int, Int]
  )
  locally {
    // this is a somewhat silly instance, but we need a
    // `LiftValue[X, Resource[IO, *]]` instance where `X` is not `IO` because
    // that already has a higher priority implicit instance
    implicit val liftIdTResource: LiftValue[IdT[IO, *], Resource[IO, *]] =
      new LiftValue[IdT[IO, *], Resource[IO, *]] {
        val applicativeF: Applicative[IdT[IO, *]] = implicitly
        val applicativeG: Applicative[Resource[IO, *]] = implicitly
        def apply[A](fa: IdT[IO, A]): Resource[IO, A] =
          Resource.eval(fa.value)
      }
    checkAll(
      "LiftValue[IdT[IO, *], Stream[IO, *]] via Resource[IO, *]",
      LiftValueTests[IdT[IO, *], Stream[IO, *]].liftValue[Int, Int]
    )
  }
}
