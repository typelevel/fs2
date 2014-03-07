package scalaz.stream2

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task
import java.util.concurrent.LinkedBlockingDeque
import Process._
import scalaz.-\/

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


  val bwah = new java.lang.Exception("bwahahahahaa!")

  def die = throw bwah


  property("pure code") = secure {
    import Process._
    var thrown = List[Throwable]()
    def cleanup(t:Throwable) = eval { Task.delay { thrown = thrown :+ t } }.drain
    val src = Process.range(0,10)
    val procs = List(
     ("flatMap-Emit",emit(1).flatMap(_ => die).onHalt(cleanup), bwah, bwah)
     ,("flatMap-Append",(emit(1) ++ emit(2)).flatMap(_ => die) onHalt(cleanup), bwah, bwah)
     , ("flatMap-Append-lzy" , (emit(1) ++ emit(2)).flatMap({ case 1 => emit(1) ; case 2 => die }) onHalt(cleanup), bwah,bwah)
     , ("map-lzy", src.map(i => if (i == 3) die else i).onHalt(cleanup), bwah, bwah)
//      , src.filter(i => if (i == 3) throw End else true).onComplete(cleanup)
//      , src.pipe(process1.lift((i: Int) => if (i == 3) die else true)).onComplete(cleanup)
//      , src.flatMap(i => if (i == 3) die else emit(i)).onComplete(cleanup)
//      , src.onComplete(cleanup).flatMap(i => if (i == 3) die else emit(i))
//      , src.onComplete(cleanup).flatMap(i => if (i == 3) throw End else emit(i))
//      , emit(1) onComplete cleanup onComplete die
//      , (emit(2) append die) onComplete cleanup
//      , (src ++ die) onComplete cleanup
//      , src.onComplete(cleanup) onComplete die
//      , src.fby(die) onComplete cleanup
//      , src.orElse(die) onComplete cleanup
//      , (src append die).orElse(halt,die) onComplete cleanup
    )

    val result = procs.zipWithIndex.map {
      case ((label,proc,exp,cup),idx) =>
//        println(">>>>>"+proc.run.attemptRun)
//        println("~~~~~"+thrown)
        val r = proc.run.attemptRun
        val thrwn = if (thrown.size < idx) None else Some(thrown(idx))
        s"$label r: $r t: $thrwn" |: ( r == -\/(exp) && thrown(idx) == cup)
    }

    result.reduce(_ && _)
  }

}
