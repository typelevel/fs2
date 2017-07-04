package fs2

import scala.concurrent.ExecutionContext
import cats.effect.IO

// Sanity tests - not run as part of unit tests, but these should run forever
// at constant memory.

object ResourceTrackerSanityTest extends App {
  val big = Stream.constant(1).flatMap { n =>
    Stream.bracket(IO(()))(_ => Stream.emits(List(1, 2, 3)), _ => IO(()))
  }
  big.run.unsafeRunSync()
}

object RepeatPullSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = _ repeatPull { _.uncons1.flatMap { case Some((h, t)) => Pull.output1(h) as Some(t); case None => Pull.pure(None) } }
  Stream.constant(1).covary[IO].throughPure(id).run.unsafeRunSync()
}

object RepeatEvalSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = {
    def go(s: Stream[Pure, A]): Pull[Pure, A, Unit] =
      s.pull.uncons1.flatMap { case Some((h, t)) => Pull.output1(h) >> go(t); case None => Pull.done }
    in => go(in).stream
  }
  Stream.repeatEval(IO(1)).throughPure(id).run.unsafeRunSync()
}

object AppendSanityTest extends App {
  (Stream.constant(1).covary[IO] ++ Stream.empty).pull.echo.stream.run.unsafeRunSync()
}

object DrainOnCompleteSanityTest extends App {
  import ExecutionContext.Implicits.global
  val s = Stream.repeatEval(IO(1)).pull.echo.stream.drain ++ Stream.eval_(IO(println("done")))
  (Stream.empty.covary[IO] merge s).run.unsafeRunSync()
}

object ConcurrentJoinSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream.constant(Stream.empty.covary[IO]).covary[IO].join(5).run.unsafeRunSync
}

object DanglingDequeueSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream.eval(async.unboundedQueue[IO,Int]).flatMap { q =>
    Stream.constant(1).flatMap { _ => Stream.empty mergeHaltBoth q.dequeue }
  }.run.unsafeRunSync
}

object AwakeEverySanityTest extends App {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global
  import TestUtil.scheduler
  time.awakeEvery[IO](1.millis).flatMap {
    _ => Stream.eval(IO(()))
  }.run.unsafeRunSync
}

object SignalDiscreteSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream.eval(async.signalOf[IO, Unit](())).flatMap { signal =>
    signal.discrete.evalMap(a => signal.set(a))
  }.run.unsafeRunSync
}

object SignalContinuousSanityTest extends App {
  import ExecutionContext.Implicits.global
  Stream.eval(async.signalOf[IO, Unit](())).flatMap { signal =>
    signal.continuous.evalMap(a => signal.set(a))
  }.run.unsafeRunSync
}

object ConstantEvalSanityTest extends App {
  var cnt = 0
  var start = System.currentTimeMillis
  Stream.constant(()).flatMap { _ => Stream.eval(IO {
    cnt = (cnt + 1) % 1000000
    if (cnt == 0) {
      val now = System.currentTimeMillis
      println("Elapsed: " + (now - start))
      start = now
    }
  }) }.run.unsafeRunSync
}
