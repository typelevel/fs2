package fs2
package benchmark

import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, State, Scope}
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
class StreamBenchmark {

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def leftAssocConcat(N: Int): Int = {
    (1 until N).map(Stream.emit).foldRight(Stream.empty.covaryOutput[Int])(_ ++ _).covary[IO].runLast.unsafeRunSync.get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def rightAssocConcat(N: Int): Int = {
    (0 until N).map(Stream.emit).foldRight(Stream.empty.covaryOutput[Int])(_ ++ _).covary[IO].runLast.unsafeRunSync.get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def leftAssocFlatMap(N: Int): Int = {
    (1 until N).map(Stream.emit).foldLeft(Stream.emit(0))((acc,a) => acc.flatMap(_ => a)).covary[IO].runLast.unsafeRunSync.get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def rightAssocFlatMap(N: Int): Int = {
    (1 until N).map(Stream.emit).reverse.foldLeft(Stream.emit(0))((acc, a) => a.flatMap( _ => acc)).covary[IO].runLast.unsafeRunSync.get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def eval(N: Int): Unit = {
    Stream.repeatEval(IO(())).take(N).runLast.unsafeRunSync.get
  }

  @GenerateN(0, 2, 3, 6, 12, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def runLog(N: Int): Vector[Int] = {
    Stream.emits(0 until N).covary[IO].runLog.unsafeRunSync
  }

  @GenerateN(1, 2, 4, 8, 16, 32, 64, 128, 256)
  @Benchmark
  def unconsPull(N: Int): Int = {
    (Stream.chunk(Chunk.seq(0 to 256000))).repeatPull { s =>
      s.unconsN(N).flatMap {
        case Some((h,t)) => Pull.output(h).as(Some(t))
        case None => Pull.pure(None)
      }
    }.covary[IO].runLast.unsafeRunSync.get
  }

  @GenerateN(1, 10, 100, 1000, 10000)
  @Benchmark @BenchmarkMode(Array(Mode.AverageTime)) @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def emitsThenFlatMap(N: Int): Vector[Int] = {
    Stream.emits(0 until N).flatMap(Stream(_)).toVector
  }
}
