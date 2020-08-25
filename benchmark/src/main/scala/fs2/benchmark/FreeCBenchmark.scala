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
package benchmark

import cats.MonadError
import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

import fs2.internal.FreeC
import fs2.internal.FreeC.{Result, ViewL}

@State(Scope.Thread)
class FreeCBenchmark {
  val N = 1000000

  @Benchmark
  def nestedMaps = {
    val nestedMapsFreeC =
      (0 to N).foldLeft(Result.Pure[Int](0): FreeC[IO, INothing, Int]) { (acc, i) =>
        acc.map(_ + i)
      }
    run(nestedMapsFreeC)
  }

  @Benchmark
  def nestedFlatMaps = {
    val nestedFlatMapsFreeC =
      (0 to N).foldLeft(Result.Pure[Int](0): FreeC[IO, INothing, Int]) { (acc, i) =>
        acc.flatMap(j => Result.Pure(i + j))
      }
    run(nestedFlatMapsFreeC)
  }

  private def run[F[_], O, R](
      self: FreeC[F, O, R]
  )(implicit F: MonadError[F, Throwable]): F[Option[R]] =
    self.viewL match {
      case Result.Pure(r)             => F.pure(Some(r))
      case Result.Fail(e)             => F.raiseError(e)
      case Result.Interrupted(_, err) => err.fold[F[Option[R]]](F.pure(None))(F.raiseError)
      case _ @ViewL.View(_)           => F.raiseError(new RuntimeException("Never get here)"))
    }
}
