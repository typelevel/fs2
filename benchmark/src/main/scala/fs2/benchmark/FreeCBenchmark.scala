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
      (0 to N).foldLeft(FreeC.pure[IO, Int](0): FreeC[IO, Int]) { (acc, i) =>
        acc.map(_ + i)
      }
    run(nestedMapsFreeC)
  }

  @Benchmark
  def nestedFlatMaps = {
    val nestedFlatMapsFreeC =
      (0 to N).foldLeft(FreeC.pure[IO, Int](0): FreeC[IO, Int]) { (acc, i) =>
        acc.flatMap(j => FreeC.pure(i + j))
      }
    run(nestedFlatMapsFreeC)
  }

  private def run[F[_], R](self: FreeC[F, R])(implicit F: MonadError[F, Throwable]): F[Option[R]] =
    self.viewL match {
      case Result.Pure(r)             => F.pure(Some(r))
      case Result.Fail(e)             => F.raiseError(e)
      case Result.Interrupted(_, err) => err.fold[F[Option[R]]](F.pure(None)) { F.raiseError }
      case v @ ViewL.View(_)          => F.raiseError(new RuntimeException("Never get here)"))
    }

}
