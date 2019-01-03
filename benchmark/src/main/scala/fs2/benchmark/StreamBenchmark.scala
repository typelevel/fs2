package fs2
package benchmark

import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, Scope, State}
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
class StreamBenchmark {

  @GenerateN(1, 10, 100, 1000, 10000, 100000)
  @Benchmark
  def leftAssocConcat(N: Int): Int =
    (0 until N)
      .map(Stream.emit)
      .foldRight(Stream.empty.covaryOutput[Int])(_ ++ _)
      .covary[IO]
      .compile
      .last
      .unsafeRunSync
      .get

  @GenerateN(1, 10, 100, 1000, 10000, 100000)
  @Benchmark
  def rightAssocConcat(N: Int): Int =
    (0 until N)
      .map(Stream.emit)
      .foldRight(Stream.empty.covaryOutput[Int])(_ ++ _)
      .covary[IO]
      .compile
      .last
      .unsafeRunSync
      .get

  @GenerateN(1, 10, 100, 1000, 10000, 100000)
  @Benchmark
  def leftAssocFlatMap(N: Int): Int =
    (0 until N)
      .map(Stream.emit)
      .foldLeft(Stream.emit(0))((acc, a) => acc.flatMap(_ => a))
      .covary[IO]
      .compile
      .last
      .unsafeRunSync
      .get

  @GenerateN(1, 10, 100, 1000, 10000, 100000)
  @Benchmark
  def rightAssocFlatMap(N: Int): Int =
    (0 until N)
      .map(Stream.emit)
      .reverse
      .foldLeft(Stream.emit(0))((acc, a) => a.flatMap(_ => acc))
      .covary[IO]
      .compile
      .last
      .unsafeRunSync
      .get

  @GenerateN(1, 10, 100, 1000, 10000, 100000)
  @Benchmark
  def eval(N: Int): Unit =
    Stream.repeatEval(IO(())).take(N).compile.last.unsafeRunSync.get

  @GenerateN(1, 10, 100, 1000, 10000, 100000)
  @Benchmark
  def toVector(N: Int): Vector[Int] =
    Stream.emits(0 until N).covary[IO].compile.toVector.unsafeRunSync

  @GenerateN(8, 256)
  @Benchmark
  def unconsPull(N: Int): Int =
    (Stream
      .chunk(Chunk.seq(0 to 2560)))
      .repeatPull { s =>
        s.unconsN(N).flatMap {
          case Some((h, t)) => Pull.output(h).as(Some(t))
          case None         => Pull.pure(None)
        }
      }
      .covary[IO]
      .compile
      .last
      .unsafeRunSync
      .get

  @GenerateN(1, 10, 100, 1000, 10000)
  @Benchmark @BenchmarkMode(Array(Mode.AverageTime)) @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def emitsThenFlatMap(N: Int): Vector[Int] =
    Stream.emits(0 until N).flatMap(Stream(_)).toVector
}
