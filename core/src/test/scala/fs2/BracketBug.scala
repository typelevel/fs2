package fs2

import TestUtil._
import fs2.util.Task

object BracketBug extends App {

  def logBracket[A]: A => Stream[Task, A] = a =>
    Stream.bracket(Task.delay { println("Acquiring $a"); 12 })(
      _ => Stream.emit(a),
      _ => Task.delay(println(s"Releasing " + a)))

  println{
    Stream(3).flatMap(logBracket).map {
      n => if (n > 2) sys.error("bad") else n
    }.run.run.unsafeAttemptRun
  }
}
