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

import cats.Eq
import cats.effect.IO
import cats.effect.testkit.TestInstances
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._

class StreamLawsSuite extends Fs2Suite with TestInstances {
  implicit val ticker: Ticker = Ticker()

  implicit def eqStream[O: Eq]: Eq[Stream[IO, O]] =
    Eq.instance((x, y) =>
      Eq[IO[Vector[Either[Throwable, O]]]]
        .eqv(x.attempt.compile.toVector, y.attempt.compile.toVector)
    )

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
}

