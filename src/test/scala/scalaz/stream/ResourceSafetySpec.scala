package scalaz.stream

import scalaz.{Equal, Nondeterminism}
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import scalaz.std.list._
import scalaz.std.list.listSyntax._
import scalaz.syntax.traverse._

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task

object ResourceSafetySpec extends Properties("resource-safety") {
  
  // Tests to ensure resource safety in a variety of scenarios
  // We want to guarantee that `Process` cleanup actions get run
  // even if exceptions occur:
  //   * In pure code, `src.map(_ => sys.error("bwahahaha!!!")`
  //   * In deterministic effectful code, i.e. `src.through(chan)`, where 
  //      * effects produced by `src` may result in exceptions
  //      * `chan` may throw exceptions before generating each effect,
  //         or in the effect itself
  //   * In nondeterminstic effectful code
  
  def die = sys.error("bwahahahahaa!")

  property("pure code") = secure {
    import Process._
    var ok = 0
    val cleanup = Process.eval { Task.delay { ok += 1 } }.drain
    val src = Process.range(0,10) 
    val procs = List(
      src.map(i => if (i == 3) die else i).onComplete(cleanup),
      src.filter(i => if (i == 3) throw End else true).onComplete(cleanup),
      src.pipe(process1.lift((i: Int) => if (i == 3) die else true)).onComplete(cleanup),
      src.flatMap(i => if (i == 3) die else emit(i)).onComplete(cleanup),
      src.onComplete(cleanup).flatMap(i => if (i == 3) die else emit(i)),
      src.onComplete(cleanup).flatMap(i => if (i == 3) throw End else emit(i)),
      emit(1) onComplete cleanup onComplete die,
      (emit(2) append die) onComplete cleanup,
      (src ++ die) onComplete cleanup,
      src.onComplete(cleanup) onComplete die
    )
    procs.foreach { p => 
      try p.run.run 
      catch { case e: Throwable => () }
    }
    ok ?= procs.length
  }

  property("eval") = secure {
    var ok = 0
    val cleanup = Process.eval { Task.delay { ok += 1 } }.drain
    val p = Process.range(0,10).onComplete(cleanup).map(i => if (i == 3) Task.delay(die) else Task.now(i))
    val p2 = Process.range(0,10).onComplete(cleanup).map(i => if (i == 3) Task.delay(throw Process.End) else Task.now(i))
    try p.eval.runLog.run catch { case e: Throwable => () }
    try p2.eval.runLog.run catch { case e: Throwable => () }
    ok ?= 2
  }

  property("handle") = secure { 
    case object Err extends RuntimeException 
    val tasks = Process(Task(1), Task(2), Task(throw Err), Task(3))
    (try { tasks.eval.pipe(processes.sum).runLog.run; false }
     catch { case Err => true }) &&
    (tasks.eval.pipe(processes.sum).
      handle { case e: Throwable => Process.emit(-6) }.
      runLog.run.last == -6)
  }

}
