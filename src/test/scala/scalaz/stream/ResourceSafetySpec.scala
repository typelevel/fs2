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
    val cleanup = Process.wrap { Task.delay { ok += 1 } }.drain
    val src = Process.range(0,10) 
    val p1 = src.map(i => if (i == 3) die else i).onComplete(cleanup)
    val p2 = src.filter(i => if (i == 3) throw End else true).onComplete(cleanup)
    val p3 = src.pipe(process1.lift((i: Int) => if (i == 3) die else true)).onComplete(cleanup)
    val p4 = src.flatMap(i => if (i == 3) die else emit(i)).onComplete(cleanup)
    val p5 = src.onComplete(cleanup).flatMap(i => if (i == 3) die else emit(i))
    val p6 = src.onComplete(cleanup).flatMap(i => if (i == 3) throw End else emit(i))
    try p1.run.run catch { case e: Throwable => () }
    try p2.run.run catch { case e: Throwable => () }
    try p3.run.run catch { case e: Throwable => () }
    try p4.run.run catch { case e: Throwable => () }
    try p5.run.run catch { case e: Throwable => () }
    try p6.run.run catch { case e: Throwable => () }
    ok ?= 6
  }

  property("eval") = secure {
    var ok = 0
    val cleanup = Process.wrap { Task.delay { ok += 1 } }.drain
    val p = Process.range(0,10).onComplete(cleanup).map(i => if (i == 3) Task.delay(die) else Task.now(i))
    val p2 = Process.range(0,10).onComplete(cleanup).map(i => if (i == 3) Task.delay(throw Process.End) else Task.now(i))
    try p.eval.collect.run catch { case e: Throwable => () }
    try p2.eval.collect.run catch { case e: Throwable => () }
    ok ?= 2
  }
}
