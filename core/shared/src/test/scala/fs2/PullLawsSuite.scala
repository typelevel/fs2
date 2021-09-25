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
import cats.effect.{Concurrent, Deferred, IO}
import cats.effect.laws.SyncTests
import cats.effect.testkit.TestInstances
import cats.syntax.all._

import org.scalacheck.Prop

class PullLawsSuite extends Fs2Suite with TestInstances {

  def toStreamAndResult[F[_]: Concurrent, O, R](pull: Pull[F, O, R]): F[(Stream[F, O], F[R])] =
    Deferred[F, R].map { result =>
      (pull.flatMap(r => Pull.eval(result.complete(r).void)).stream, result.get)
    }

  implicit def pullToProp[O](pull: Pull[IO, O, Boolean])(implicit ticker: Ticker): Prop =
    toStreamAndResult(pull)
      .flatMap { case (stream, result) =>
        stream.compile.drain >> result
      }

  implicit def eqPull[O: Eq, R: Eq](implicit ticker: Ticker): Eq[Pull[IO, O, R]] = {
    def run(p: Pull[IO, O, R]): IO[(List[O], R)] =
      for {
        streamAndResult <- toStreamAndResult(p)
        (stream, result) = streamAndResult
        output <- stream.compile.toList
        r      <- result
      } yield (output, r)

    Eq.instance((x, y) => Eq.eqv(run(x), run(y)))
  }

  {
    implicit val ticker: Ticker = Ticker()
    checkAll("Sync[Pull[F, O, *]]", SyncTests[Pull[IO, Int, *]].sync[Int, Int, Int])
  }
}
