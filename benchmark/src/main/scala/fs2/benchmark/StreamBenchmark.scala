package fs2
package benchmark

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

@State(Scope.Thread)
class StreamBenchmark extends BenchmarkUtils {

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def leftAssocConcat(N: Int): Int = {
    (1 until N).map(Stream.emit).foldRight(Stream.empty[Pure, Int])(_ ++ _).covary[Task].runLast.unsafeRun().get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def rightAssocConcat(N: Int): Int = {
    Chunk.seq((0 until N).map(Stream.emit)).foldRight(Stream.empty[Pure, Int])(_ ++ _).covary[Task].runLast.unsafeRun().get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def leftAssocFlatMap(N: Int): Int = {
    (1 until N).map(Stream.emit).foldLeft(Stream.emit(0))((acc,a) => acc.flatMap(_ => a)).covary[Task].runLast.unsafeRun().get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def rightAssocFlatMap(N: Int): Int = {
    (1 until N).map(Stream.emit).reverse.foldLeft(Stream.emit(0))((acc, a) => a.flatMap( _ => acc)).covary[Task].runLast.unsafeRun().get
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def eval(N: Int): Unit = {
    Stream.repeatEval(Task.delay(())).take(N).runLast.unsafeRun().get
  }

  @GenerateN(1, 2, 4, 8, 16, 32, 64, 128, 256)
  @Benchmark
  def awaitPull(N: Int): Int = {
    (Stream.chunk(Chunk.seq(0 to 256000)).pure).repeatPull { s =>
      for {
        (h,t) <- s.awaitN(N)
        _  <- Pull.output(h.head)
      } yield t
    }.covary[Task].runLast.unsafeRun().get
  }
}
