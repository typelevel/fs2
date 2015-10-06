package scalaz.stream

import Cause._
import org.scalacheck.Prop._
import org.scalacheck.Properties

import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.syntax.equal._
import scalaz.std.anyVal._
import TestInstances.equalProcessTask

import java.util.concurrent.ScheduledExecutorService

//import scalaz.syntax.equal._
//import scalaz.std.anyVal._
//import TestInstances.equalProcessTask

/**
 * We test various termination causes here,
 * including the correct propagation of causes
 * from merging combinator (pipe, tee, wye, njoin) at various scenarios
 */
class CauseSpec extends Properties("cause") {
  implicit val S: ScheduledExecutorService = DefaultScheduler

  property("suspend") = protect {
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
    .&& ("kill" |: { var cause: Cause = End; suspend(source.onHalt { c => cause = c; halt }).kill.run.run; cause == Kill })
  }


  property("pipe.terminated.p1") = protect {
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

  property("pipe.terminated.p1.flatMap.repeatEval") = protect {
    val source = repeatEval(Task.now(1))
    var upReason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source.onHalt{cause => upReason = Some(cause); Halt(cause)}
      .flatMap{ i => Process.range(0,i).bufferAll}
      .pipe(process1.take(2))
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run

    (result == Vector(0,0))
    .&&(upReason == Some(Kill))
    .&&(downReason == Some(End))
  }

  property("pipe.terminated.p1.with.upstream") = protect {
    val source = emitAll(Seq(1,2,3)).toSource
    var upReason: Option[Cause] = None
    var downReason: Option[Cause] = None

    val result =
      source.onHalt{cause => upReason = Some(cause); Halt(cause)}
      .pipe(process1.take(2))
      .onHalt { cause => downReason = Some(cause) ; Halt(cause) }
      .runLog.run


    (result == Vector(1,2))
    .&&(upReason == Some(Kill)) //onHalt which remains in the pipe's source gets Kill
    .&&(downReason == Some(End))
  }


  property("pipe.terminated.upstream") = protect {
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

  property("pipe.killed.upstream") = protect {
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


  property("tee.terminated.tee") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(halt)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector())
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("tee.terminated.tee.onLeft") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitL.repeat)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector(0,1,2))
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("tee.terminated.tee.onRight") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitR.repeat)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,1,2))
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(End))
    .&& (processReason == Some(End))
  }

  property("tee.terminated.left.onLeft") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = halt onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitL.repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector())
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
  }


  property("tee.terminated.right.onRight") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = halt onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)(awaitR.repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector())
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(End))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
  }

  property("tee.terminated.leftAndRight.onLeftAndRight") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10,1,11,2,12))
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill)) //onHalt which remains on the right side gets Kill
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill)) //killed due left input exhausted awaiting left branch
  }

  property("tee.terminated.leftAndRight.onRightAndLeft") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitR[Int] ++ awaitL[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(10,0,11,1,12,2))
    .&& (leftReason == Some(Kill)) //was killed due tee awaited right and that was `End`.
    .&& (rightReason == Some(End))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill)) //killed due right input exhausted awaiting right branch
  }

  property("tee.terminated.downstream") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .take(2)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10))
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill)) //Tee is killed because downstream terminated
  }

  property("tee.kill.left") = protect {
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    var beforePipeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.take(1) ++ Halt(Kill)
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => beforePipeReason = Some(c); Halt(c)}
      .take(2)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector(0,10))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
    .&& (beforePipeReason == Some(Kill))
  }

  property("tee.kill.right") = protect {
    var leftReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    var beforePipeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10).take(1) ++ Halt(Kill)


    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => beforePipeReason = Some(c); Halt(c)}
      .take(2)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10))
    .&& (leftReason == Some(Kill))
    .&& (processReason == Some(End))
    .&& (teeReason == Some(Kill))
    .&& (beforePipeReason == Some(Kill))
  }


  property("tee.pipe.kill") = protect {
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var teeReason: Option[Cause] = None
    var pipeReason: Option[Cause] = None
    var beforePipeReason : Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.take(1) ++ Halt(Kill)
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    //note last onHalt before and after pipe
    val process =
      left.tee(right)((awaitL[Int] ++ awaitR[Int]).repeat onHalt{ c => teeReason = Some(c); Halt(c)})
      .onHalt{ c => beforePipeReason = Some(c); Halt(c)}
      .pipe(process1.id[Int] onHalt {c => pipeReason = Some(c); Halt(c)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run


    (process == Vector(0,10))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(Kill))
    .&& (teeReason == Some(Kill))
    .&& (beforePipeReason == Some(Kill))
    .&& (pipeReason == Some(Kill))
  }


  property("wye.terminated.wye") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(halt)
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector())
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("wye.terminated.wye.onLeft") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10).repeat onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(awaitL.repeat.asInstanceOf[Wye[Int,Int,Int]].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector(0,1,2))
    .&& (leftReason == Some(End))
    .&& (rightReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(End))
  }

  property("wye.terminated.wye.onRight") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.repeat onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(awaitR.repeat.asInstanceOf[Wye[Int,Int,Int]].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process == Vector(10,11,12))
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(End))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("wye.kill.merge.onLeft") = protect {
    var rightReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src ++ Halt(Kill)
    val right = src.map(_ + 10).repeat onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(wye.merge[Int].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process.filter(_ < 10) == Vector(0,1,2))
    .&& (rightReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(Kill))
  }


  property("wye.kill.merge.onRight") = protect {
    var leftReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.repeat onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.map(_ + 10) ++ Halt(Kill)


    val process =
      left.wye(right)(wye.merge[Int].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process.filter(_ >= 10) == Vector(10,11,12))
    .&& (leftReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (processReason == Some(Kill))
  }

  property("wye.kill.downstream") = protect {
    var leftReason: Option[Cause] = None
    var rightReason: Option[Cause] = None
    var pipeReason: Option[Cause] = None
    var processReason: Option[Cause] = None
    var wyeReason: Option[Cause] = None
    val src = Process.range(0,3).toSource
    val left = src.repeat onHalt{ c => leftReason = Some(c); Halt(c)}
    val right = src.repeat onHalt{ c => rightReason = Some(c); Halt(c)}


    val process =
      left.wye(right)(wye.merge[Int].onHalt{rsn => wyeReason=Some(rsn); Halt(rsn)})
      .onHalt{ c => pipeReason = Some(c); Halt(c)}
      .pipe(process1.take(10))
      .onHalt{ c => processReason = Some(c); Halt(c)}
      .runLog.run

    (process.size == 10)
    .&& (leftReason == Some(Kill))
    .&& (rightReason == Some(Kill))
    .&& (wyeReason == Some(Kill))
    .&& (pipeReason == Some(Kill))
    .&& (processReason == Some(End))
  }


  property("pipe, tee, wye does not swallow Kill from outside") = protect {
    val p1 = await1 onHalt { x => Process(1, 2).causedBy(x) }

    // Read from the left then from the right.
    val t: Tee[Int, Int, Int] =
      tee.passL.asInstanceOf[Tee[Int, Int, Int]]
        .onHalt { x => tee.passR.causedBy(x) }

    val inf = repeatEval(Task.delay(()))

    var fromPipe: Option[Cause] = None
    var fromTee: Option[Cause] = None
    var fromWye: Option[Cause] = None

    // pipe
    inf
      .flatMap { _ => (halt |> p1).onHalt { x => fromPipe = Some(x); Halt(x) } }
      .take(4).run.timed(3000).run
    inf
      .flatMap { _ => Process(0, 2, 4).chunkAll /* Pipe is in chunkAll. */ }
      .take(4).run.timed(3000).run

    // tee
    inf
      .flatMap { _ => emit(1).tee(emit(2))(t).onHalt { x => fromTee = Some(x); Halt(x) } }
      .take(3).run.timed(3000).run

    // wye (same test as tee)
    inf
      .flatMap { _ => emit(1).wye(emit(2))(t).onHalt { x => fromWye = Some(x); Halt(x) } }
      .take(3).run.timed(3000).run

    (fromPipe.get == Kill) && (fromTee.get == Kill) && (fromWye.get == Kill)
  }
}
