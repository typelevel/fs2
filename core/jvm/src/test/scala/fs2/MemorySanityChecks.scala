package fs2

import scala.concurrent.ExecutionContext
import cats.effect.{ContextShift, IO, Timer}
import fs2.concurrent.{Queue, SignallingRef, Topic}

// Sanity tests - not run as part of unit tests, but these should run forever
// at constant memory.
object GroupWithinSanityTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  import scala.concurrent.duration._

  Stream
    .eval(IO.never)
    .covary[IO]
    .groupWithin(Int.MaxValue, 1.millis)
    .compile
    .drain
    .unsafeRunSync()
}

object GroupWithinSanityTest2 extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  import scala.concurrent.duration._

  val a: Stream[IO, Chunk[Int]] = Stream
    .eval(IO.never)
    .covary[IO]
    .groupWithin(Int.MaxValue, 1.second)
    .interruptAfter(100.millis) ++ a

  a.compile.drain
    .unsafeRunSync()
}

object TopicContinuousPublishSanityTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  Topic[IO, Int](-1)
    .flatMap(topic => Stream.repeatEval(topic.publish1(1)).compile.drain)
    .unsafeRunSync()
}

object ResourceTrackerSanityTest extends App {
  val big = Stream.constant(1).flatMap { n =>
    Stream.bracket(IO(()))(_ => IO(())).flatMap(_ => Stream.emits(List(1, 2, 3)))
  }
  big.compile.drain.unsafeRunSync()
}

object RepeatPullSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = _.repeatPull {
    _.uncons1.flatMap {
      case Some((h, t)) => Pull.output1(h).as(Some(t));
      case None         => Pull.pure(None)
    }
  }
  Stream.constant(1).covary[IO].through(id[Int]).compile.drain.unsafeRunSync()
}

object RepeatEvalSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = {
    def go(s: Stream[Pure, A]): Pull[Pure, A, Unit] =
      s.pull.uncons1.flatMap {
        case Some((h, t)) => Pull.output1(h) >> go(t); case None => Pull.done
      }
    in =>
      go(in).stream
  }
  Stream.repeatEval(IO(1)).through(id[Int]).compile.drain.unsafeRunSync()
}

object AppendSanityTest extends App {
  (Stream.constant(1).covary[IO] ++ Stream.empty).pull.echo.stream.compile.drain
    .unsafeRunSync()
}

object DrainOnCompleteSanityTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  val s = Stream.repeatEval(IO(1)).pull.echo.stream.drain ++ Stream.eval_(IO(println("done")))
  Stream.empty.covary[IO].merge(s).compile.drain.unsafeRunSync()
}

object ParJoinSanityTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  Stream
    .constant(Stream.empty[IO])
    .parJoin(5)
    .compile
    .drain
    .unsafeRunSync
}

object DanglingDequeueSanityTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  Stream
    .eval(Queue.unbounded[IO, Int])
    .flatMap { q =>
      Stream.constant(1).flatMap { _ =>
        Stream.empty.mergeHaltBoth(q.dequeue)
      }
    }
    .compile
    .drain
    .unsafeRunSync
}

object AwakeEverySanityTest extends App {
  import scala.concurrent.duration._
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.Implicits.global)
  Stream
    .awakeEvery[IO](1.millis)
    .flatMap { _ =>
      Stream.eval(IO(()))
    }
    .compile
    .drain
    .unsafeRunSync
}

object SignalDiscreteSanityTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  Stream
    .eval(SignallingRef[IO, Unit](()))
    .flatMap { signal =>
      signal.discrete.evalMap(a => signal.set(a))
    }
    .compile
    .drain
    .unsafeRunSync
}

object SignalContinuousSanityTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  Stream
    .eval(SignallingRef[IO, Unit](()))
    .flatMap { signal =>
      signal.continuous.evalMap(a => signal.set(a))
    }
    .compile
    .drain
    .unsafeRunSync
}

object ConstantEvalSanityTest extends App {
  var cnt = 0
  var start = System.currentTimeMillis
  Stream
    .constant(())
    .flatMap { _ =>
      Stream.eval(IO {
        cnt = (cnt + 1) % 1000000
        if (cnt == 0) {
          val now = System.currentTimeMillis
          println("Elapsed: " + (now - start))
          start = now
        }
      })
    }
    .compile
    .drain
    .unsafeRunSync
}

object RecursiveFlatMapTest extends App {
  def loop: Stream[IO, Unit] = Stream(()).covary[IO].flatMap(_ => loop)
  loop.compile.drain.unsafeRunSync
}

object EvalFlatMapMapTest extends App {
  Stream
    .eval(IO(()))
    .flatMap(_ => Stream.emits(Seq()))
    .map(x => x)
    .repeat
    .compile
    .drain
    .unsafeRunSync()
}

object QueueTest extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  Stream
    .eval(Queue.bounded[IO, Either[Throwable, Option[Int]]](10))
    .flatMap { queue =>
      queue
        .dequeueChunk(Int.MaxValue)
        .rethrow
        .unNoneTerminate
        .concurrently(
          Stream
            .constant(1, 128)
            .covary[IO]
            .noneTerminate
            .attempt
            .evalMap(queue.enqueue1(_))
        )
        .evalMap(_ => IO.unit)
    }
    .compile
    .drain
    .unsafeRunSync()
}

object ProgressMerge extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  val progress = Stream.constant(1, 128).covary[IO]
  progress.merge(progress).compile.drain.unsafeRunSync()
}

object HungMerge extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  val hung = Stream.eval(IO.async[Int](_ => ()))
  val progress = Stream.constant(1, 128).covary[IO]
  hung.merge(progress).compile.drain.unsafeRunSync()
}

object ZipThenBindThenParJoin extends App {
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.Implicits.global)
  import scala.concurrent.duration._

  val sources: Stream[IO, Stream[IO, Int]] = Stream(Stream.empty).repeat

  Stream
    .fixedDelay[IO](1.milliseconds)
    .zip(sources)
    .flatMap {
      case (_, s) =>
        s.map(Stream.constant(_).covary[IO]).parJoinUnbounded
    }
    .compile
    .drain
    .unsafeRunSync()
}
