package fs2

// Sanity tests - not run as part of unit tests, but these should run forever
// at constant memory.
//
object ResourceTrackerSanityTest extends App {
  val big = Stream.constant(1).flatMap { n =>
    Stream.bracket(Task.delay(()))(_ => Stream.emits(List(1, 2, 3)), _ => Task.delay(()))
  }
  big.run.unsafeRun()
}

object RepeatPullSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = _ repeatPull Pull.receive1 { case (h, t) => Pull.output1(h) as t }
  Stream.constant(1).covary[Task].throughPure(id).run.unsafeRun()
}

object RepeatEvalSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = {
    def go: Handle[Pure, A] => Pull[Pure, A, Handle[Pure, A]] =
      _.receive1 { case (h, t) => Pull.output1(h) >> go(t) }
    _ pull go
  }
  Stream.repeatEval(Task.delay(1)).throughPure(id).run.unsafeRun()
}

object AppendSanityTest extends App {
  (Stream.constant(1).covary[Task] ++ Stream.empty).pull(Pull.echo).run.unsafeRun()
}

object OnCompleteSanityTest extends App {
  Stream.constant(1).covary[Task].onComplete(Stream.empty).pull(Pull.echo).run.unsafeRun()
}

object DrainOnCompleteSanityTest extends App {
  import TestUtil.S
  val s = Stream.repeatEval(Task.delay(1)).pull(Pull.echo).drain.onComplete(Stream.eval_(Task.delay(println("done"))))
  (Stream.empty[Task, Unit] merge s).run.unsafeRun()
}

object ConcurrentJoinSanityTest extends App {
  import TestUtil.S
  concurrent.join(5)(Stream.constant(Stream.empty).covary[Task]).run.unsafeRun
}

object DanglingDequeueSanityTest extends App {
  import TestUtil.S
  Stream.eval(async.unboundedQueue[Task,Int]).flatMap { q =>
    Stream.constant(1).flatMap { _ => Stream.empty mergeHaltBoth q.dequeue }
  }.run.unsafeRun
}
