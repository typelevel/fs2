package fs2

import fs2.util.Task

// Sanity tests - not run as part of unit tests, but these should run forever
// at constant memory.
//
object ResourceTrackerSanityTest extends App {
  val big = Stream.constant(1).flatMap { n =>
    Stream.bracket(Task.delay(()))(_ => Stream.emits(List(1, 2, 3)), _ => Task.delay(()))
  }
  big.run.run.unsafeRun
}

object RepeatPullSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = _ repeatPull Pull.receive1 { case h #: t => Pull.output1(h) as t }
  Stream.constant(1).covary[Task].throughp(id).run.run.unsafeRun
}

object RepeatEvalSanityTest extends App {
  def id[A]: Pipe[Pure, A, A] = {
    def go: Stream.Handle[Pure, A] => Pull[Pure, A, Stream.Handle[Pure, A]] =
      _.receive1 { case h #: t => Pull.output1(h) >> go(t) }
    _ pull go
  }
  Stream.repeatEval(Task.delay(1)).throughp(id).run.run.unsafeRun
}

object AppendSanityTest extends App {
  (Stream.constant(1).covary[Task] ++ Stream.empty).pull(Pull.echo).run.run.unsafeRun
}

object OnCompleteSanityTest extends App {
  Stream.constant(1).covary[Task].onComplete(Stream.empty).pull(Pull.echo).run.run.unsafeRun
}

object DrainOnCompleteSanityTest extends App {
  import TestUtil.S
  val s = Stream.repeatEval(Task.delay(1)).pull(Pull.echo).drain.onComplete(Stream.eval_(Task.delay(println("done"))))
  (Stream.empty[Task, Unit] merge s).run.run.unsafeRun
}
