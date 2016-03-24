package fs2

import fs2.util.Task

// Sanity tests - not run as part of unit tests, but these should run forever
// at constant memory.
//
object ResourceTrackerSanityTest extends App {
  val big = Stream.constant(1).flatMap { n =>
    Stream.bracket(Task.delay(()))(_ => Stream.emits(List(1, 2, 3)), _ => Task.delay(()))
  }
  big.run.run.run
}

object RepeatPullSanityTest extends App {
  def id[A]: Process1[A, A] = _ repeatPull Pull.receive1 { case h #: t => Pull.output1(h) as t }
  Stream.constant(1).pipe(id).covary[Task].run.run.run
}

object RepeatEvalSanityTest extends App {
  def id[A]: Process1[A, A] = {
    def go: Stream.Handle[Pure, A] => Pull[Pure, A, Stream.Handle[Pure, A]] =
      _.receive1 { case h #: t => Pull.output1(h) >> go(t) }
    _ pull go
  }
  Stream.repeatEval(Task.delay(1)).pipe(id).covary[Task].run.run.run
}

object AppendSanityTest extends App {
  val src = Stream.constant(1).covary[Task] ++ Stream.empty
  val prg = src.pull(Pull.echo)
  prg.run.run.run
}

object OnCompleteSanityTest extends App {
  Stream.constant(1).covary[Task].onComplete(Stream.empty).pull(Pull.echo)
  .run.run.run
}
