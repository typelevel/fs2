package fs2

import scala.concurrent.ExecutionContext
import cats.effect.IO

// Sanity tests - not run as part of unit tests, but these should run forever
// at constant memory.
//
object ResourceTrackerSanityTest extends App {
  val big = Stream.constant(1).flatMap { n =>
    Stream.bracket(IO(()))(_ => Stream.emits(List(1, 2, 3)), _ => IO(()))
  }
  big.run.unsafeRunSync()
}

object RepeatPullSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = _ repeatPull { _.receive1 { (h, t) => Pull.output1(h) as t } }
  Stream.constant(1).covary[IO].throughPure(id).run.unsafeRunSync()
}

object RepeatEvalSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = {
    def go: Handle[Pure, A] => Pull[Pure, A, Handle[Pure, A]] =
      _.receive1 { case (h, t) => Pull.output1(h) >> go(t) }
    _ pull go
  }
  Stream.repeatEval(IO(1)).throughPure(id).run.unsafeRunSync()
}

object AppendSanityTest extends App {
  (Stream.constant(1).covary[IO] ++ Stream.empty).pull(_.echo).run.unsafeRunSync()
}

object DrainOnCompleteSanityTest extends App {
  import ExecutionContext.Implicits.global
  val s = Stream.repeatEval(IO(1)).pull(_.echo).drain ++ Stream.eval_(IO(println("done")))
  (Stream.empty[IO, Unit] merge s).run.unsafeRunSync()
}

object ConcurrentJoinSanityTest extends App {
  import ExecutionContext.Implicits.global
  concurrent.join(5)(Stream.constant(Stream.empty).covary[IO]).run.unsafeRunSync
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
  Stream.constant(()).flatMap { _ => Stream.eval(IO(())) }.run.unsafeRunSync
}
