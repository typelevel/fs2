package scalaz.stream

import org.scalacheck._
import Prop._

import scalaz.concurrent.Task
import java.util.concurrent.LinkedBlockingDeque
import Process._
import scalaz.-\/
import scalaz.\/._


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

  // Additionally we want to be sure that correct causes of termination (exceptions)
  // are propagated down the process


  val bwah = new java.lang.Exception("bwahahahahaa!")
  val boom = new java.lang.Exception("boom!")

  def die = throw bwah


  property("cleanups") = secure {
    import Process._
    var thrown = List[Throwable]()
    def cleanup(t:Throwable) =   { thrown = thrown :+ t ; fail(t) }
    val src = Process.range(0,10)
    val procs = List(
     ("flatMap-Emit",emit(1).flatMap(_ => die).onHalt(cleanup), left(bwah), List(bwah))
     ,("flatMap-Append",(emit(1) ++ emit(2)).flatMap(_ => die) onHalt(cleanup), left(bwah), List(bwah))
     , ("flatMap-Append-lzy" , (emit(1) ++ emit(2)).flatMap({ case 1 => emit(1) ; case 2 => die }) onHalt(cleanup),  left(bwah),List(bwah))
     , ("map-lzy", src.map(i => if (i == 3) die else i).onHalt(cleanup),  left(bwah), List(bwah))
     , ("append-lzy", (src ++ die) onHalt cleanup,  left(bwah), List(bwah))
     , ("pipe-term-p1", src.pipe(fail(bwah)) onHalt cleanup,  left(bwah), List(bwah))
     , ("pipe-term-src", fail(bwah).pipe(process1.id) onHalt cleanup,  left(bwah), List(bwah))
     , ("pipe-cln-src", (src onHalt cleanup).pipe(fail(bwah)) onHalt cleanup ,  left(bwah), List(Kill,bwah))
     , ("pipe-cln-p1", src.pipe(fail(bwah) onHalt cleanup) onHalt cleanup ,  left(bwah), List(bwah,bwah))
     , ("pipe-fail-src-then-p1", (src ++ fail(bwah)).pipe(process1.id[Int] onComplete fail(boom) onHalt cleanup), left(CausedBy(boom, bwah)), List(boom))
     , ("pipe-fail-p1-then-src", (src onComplete fail(bwah) onHalt cleanup).pipe(fail(boom)), left(boom), List(CausedBy(bwah, Kill)))
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
      , ("tee-cln-left", (src onHalt cleanup).zip(fail(bwah)) onHalt cleanup, left(bwah), List(Kill, bwah))
      , ("tee-cln-right", fail(bwah).zip(src onHalt cleanup) onHalt cleanup, left(bwah), List(Kill, bwah))
      , ("tee-cln-down", (src onHalt cleanup).zip(src onHalt cleanup) onHalt cleanup, right(()), List(Continue, Kill, Continue))
      , ("tee-cln-tee", (src onHalt cleanup).tee(src onHalt cleanup)(fail(bwah)) onHalt cleanup, left(bwah), List(Kill, Kill, bwah))
      , ("wye-cln-left", (src onHalt cleanup).wye(fail(bwah))(wye.yip) onHalt cleanup, left(bwah), List(Kill, bwah))
      , ("wye-cln-right", fail(bwah).wye(src onHalt cleanup)(wye.yip) onHalt cleanup, left(bwah), List(Kill, bwah))
      , ("wye-cln-down", (src onHalt cleanup).wye(src onHalt cleanup)(wye.yip) onHalt cleanup, right(()), List(End, End, End))
      , ("wye-cln-wye", (src onHalt cleanup).wye(src onHalt cleanup)(fail(bwah)) onHalt cleanup, left(bwah), List(Kill, Kill, bwah))
    )

    val result = procs.zipWithIndex.map {
      case ((label,proc,exp,cup),idx) =>
//        println(">>>>>"+proc.run.attemptRun)
//        println("~~~~~"+thrown)
        val r = proc.run.attemptRun
        val thrwn = thrown
        thrown = Nil
        s"$label r: $r t: $thrwn" |: ( r == exp && thrwn == cup)
    }

    result.reduce(_ && _)
  }

  property("repeated kill") = secure {
    var cleaned = false
    (emit(1) onComplete eval_(Task.delay(cleaned = true))).kill.kill.kill.run.run
    cleaned
  }
}
