package fs2.benchmark

import cats.syntax.all._
import cats.effect.{Concurrent, IO}
import fs2._
import fs2.concurrent.Queue
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

import scala.concurrent.ExecutionContext
@State(Scope.Thread)
class QueueBenchmark {
  implicit val cs = IO.contextShift(ExecutionContext.global)
  implicit val concurrent = IO.ioConcurrentEffect

  val size = 100000

  @GenerateN(1, 2, 5, 10, 50, 100)
  @Benchmark
  def chunkedQueue10k(N: Int): Unit =
    Queue
      .unbounded[IO, Int]
      .flatMap { q =>
        Concurrent[IO].start(Stream.constant(1, N).take(size).through(q.enqueue).compile.drain) >>
          q.dequeue.take(size).compile.drain
      }
      .unsafeRunSync()
  @GenerateN(1, 2, 5, 10, 50, 100)
  @Benchmark
  def chunkedQueueManually10k(N: Int): Unit =
    Queue
      .unbounded[IO, Chunk[Int]]
      .flatMap { q =>
        Concurrent[IO].start(
          Stream.constant(1, N).take(size).chunks.through(q.enqueue).compile.drain) >>
          q.dequeue.flatMap(Stream.chunk).take(size).compile.drain
      }
      .unsafeRunSync()

}
