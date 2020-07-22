package fs2

import cats.effect.{ExitCode, IO, IOApp}
import fs2.concurrent.InspectableQueue

object Nop extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val m = 100000
    val s = Stream.emits(1 to m)
    val expected = (1 to m).toList
    Stream
      .eval(InspectableQueue.noneTerminated[IO, Int])
      .flatMap { q =>
        s.noneTerminate.evalMap {
          case Some(x) => q.enqueue1(Some(x))
          case None    => q.enqueue1(None)
        }.drain ++ q.dequeueChunk(Int.MaxValue)
      }
      .compile
      .toList
      .map { result =>
        println(result.intersect(expected))
        assert(result == expected)
        ExitCode.Success
      }
  }
}
