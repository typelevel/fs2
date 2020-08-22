package fs2.benchmark

import cats.syntax.all._
import cats.effect.{Concurrent, ContextShift, IO}
import fs2._
import fs2.concurrent.Queue
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

import scala.concurrent.ExecutionContext
@State(Scope.Thread)
class QueueBenchmark {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val concurrent: Concurrent[IO] = IO.ioConcurrentEffect

  val size = 100000

  @Param(Array("1", "2", "5", "10", "50", "100"))
  var n: Int = _

  @Benchmark
  def chunkedQueue10k(): Unit =
    Queue
      .unbounded[IO, Int]
      .flatMap { q =>
        Concurrent[IO].start(Stream.constant(1, n).take(size).through(q.enqueue).compile.drain) >>
          q.dequeue.take(size).compile.drain
      }
      .unsafeRunSync()

  @Benchmark
  def chunkedQueueManually10k(): Unit =
    Queue
      .unbounded[IO, Chunk[Int]]
      .flatMap { q =>
        Concurrent[IO].start(
          Stream.constant(1, n).take(size).chunks.through(q.enqueue).compile.drain
        ) >>
          q.dequeue.flatMap(Stream.chunk).take(size).compile.drain
      }
      .unsafeRunSync()
}
