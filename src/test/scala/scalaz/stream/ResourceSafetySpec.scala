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
    var ok = 0
    val cleanup = Process.wrap { Task.delay { ok += 1 } }.drain
    val src = Process.range(0,10) 
    val p1 = src.map(i => if ((i%3) == 0) die else i).onComplete(cleanup)
    val p2 = src.filter(i => if ((i%3) == 0) die else true).onComplete(cleanup)
    val p3 = src.pipe(process1.lift((i: Int) => if ((i%3) == 0) die else true)).onComplete(cleanup)
    try { List(p1.run, p2.run, p3.run).sequence.run }
    catch { case e: Throwable => () }
    ok ?= 3
  }

  property("eval") = secure {
    var ok = 0
    val cleanup = Process.wrap { Task.delay { ok += 1 } }.drain
    val p = Process.range(0,10).onComplete(cleanup).map(i => if (i == 3) Task.delay(die) else Task.now(i))
    try { p.eval.collect.run }
    catch { case e => () }
    ok ?= 1
  }
}
