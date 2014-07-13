package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.Properties

import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import TestInstances.equalProcessTask

//import scalaz.syntax.equal._
//import scalaz.std.anyVal._
//import TestInstances.equalProcessTask

/**
 * We test various termination causes here,
 * including the correct propagation of causes
 * from merging combinator (pipe, tee, wye, njoin) at various scenarios
 */
object CauseSpec extends Properties("cause") {

  property("suspend") = secure {
    val source = Process(1, 2, 3).toSource
    val halted: Process[Task, Int] = halt
    val failed: Process[Task, Int] = fail(Bwahahaa)

    ("result" |: source.runLog.run == Vector(1,2,3))
    .&& ("source" |: suspend(source) === source)
    .&& ("halt" |: suspend(halted) === halted)
    .&& ("failed" |: suspend(failed) === failed)
    .&& ("take(1)" |: suspend(source.take(1)) === source.take(1))
    .&& ("repeat.take(10)" |: suspend(source.repeat.take(10)) === source.repeat.take(10))
    .&& ("eval" |: suspend(eval(Task.now(1))) === eval(Task.delay(1)))

  }


  property("pipe.terminated.p1") = secure {
    val source = Process(1) ++ Process(2) ++ Process(3).toSource
    var upReason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
    source.onHalt{cause => upReason = Some(cause); Halt(cause)}
    .pipe(process1.take(2))
    .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
    .runLog.run

    (result == Vector(1,2))
    .&&(upReason == Some(Kill))
    .&&(downReason == Some(End))
  }

  property("pipe.terminated.p1.with.upstream") = secure {
    //source is one chunk that means it terminate at same
    //time as the pipe transducer.
    //as such the reason for termination of upstream is End
    val source = emitAll(Seq(1,2,3)).toSource
    var upReason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source.onHalt{cause => upReason = Some(cause); Halt(cause)}
      .pipe(process1.take(2))
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run


    (result == Vector(1,2))
    .&&(upReason == Some(End))
    .&&(downReason == Some(End))
  }

  property("pipe.terminated.upstream") = secure {
    val source = emitAll(Seq(1,2,3)).toSource
    var id1Reason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source
      .pipe(process1.id[Int].onHalt{cause => id1Reason = Some(cause) ; Halt(cause)})
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run

    (result == Vector(1,2,3))
    .&&(id1Reason == Some(Kill))
    .&&(downReason == Some(End))
  }

  property("pipe.killed.upstream") = secure {
    val source = (Process(1) ++ Process(2).kill).toSource
    var id1Reason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source
      .pipe(process1.id[Int].onHalt{cause => id1Reason = Some(cause) ; Halt(cause)})
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run

    (result == Vector(1))
    .&&(id1Reason == Some(Kill))
    .&&(downReason == Some(Kill))
  }


}
