package fs2
package benchmark

import cats.MonadError
import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}
import fs2.PullImpl.{Fail, Interrupted, Value, ViewL}

@State(Scope.Thread)
class PurePullBenchmark {
  val N = 1000000

  @Benchmark
  def nestedMaps = {
    val nestedMapsFreeC =
      (0 to N).foldLeft(Value[Int](0): Pull[IO, INothing, Int]) { (acc, i) =>
        acc.map(_ + i)
      }
    run(nestedMapsFreeC)
  }

  @Benchmark
  def nestedFlatMaps = {
    val nestedFlatMapsFreeC =
      (0 to N).foldLeft(Value[Int](0): Pull[IO, INothing, Int]) { (acc, i) =>
        acc.flatMap(j => Value(i + j))
      }
    run(nestedFlatMapsFreeC)
  }

  private def run[F[_], O, R](
      self: Pull[F, O, R]
  )(implicit F: MonadError[F, Throwable]): F[Option[R]] =
    self.viewL match {
      case Value(r)            => F.pure(Some(r))
      case Fail(e)             => F.raiseError(e)
      case Interrupted(_, err) => err.fold[F[Option[R]]](F.pure(None)) { F.raiseError }
      case _ @ViewL.View(_)    => F.raiseError(new RuntimeException("Never get here)"))
    }
}
