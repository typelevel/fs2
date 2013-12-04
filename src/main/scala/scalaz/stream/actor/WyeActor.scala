package scalaz.stream.actor

import java.util.concurrent.atomic.AtomicBoolean
import scala._
import scala.annotation.tailrec
import scalaz._
import scalaz.concurrent.{Strategy, Actor, Task}
import scalaz.stream.Process._
import scalaz.stream.Step
import scalaz.stream.wye.{AwaitBoth, AwaitR, AwaitL}
import scalaz.stream.{Process, wye}

object WyeActor {

  trait WyeSide[A, L, R, O] {
    /** returns next wye after processing the result of the step **/
    def receive(r: \/[Throwable, Step[Task, A]])(y2: Wye[L, R, O]): Wye[L, R, O]
  }

 val Interrupted = new java.lang.Exception("Interrupted to clean") {
    override def fillInStackTrace(): Throwable = this
  }

  sealed trait Msg
  case class Ready[A, L, R, O](from: WyeSide[A, L, R, O], s: Throwable \/ Step[Task, A]) extends Msg
  case class Get[A](cb: (Throwable \/ Seq[A]) => Unit) extends Msg
  case class Done(rsn: Throwable, cb: (Throwable \/ Unit) => Unit) extends Msg

  trait WyeSideOps[A, L, R, O] extends WyeSide[A, L, R, O] {

    // Next step of process that feds into wye.
    // - left contains cleanup in case the step is running to perform any cleanup needed
    // - right contains next step of the process
    var step: (Process[Task,A] \/ Step[Task, A])

    // when this is set to`true`
    // it indicates the running task to be interrupted and cleanup process will start as next step
    // please not there is still slight chance that `cleanup` and last step will run in parallel.
    // to solve this, we need a fix or resolution to https://github.com/scalaz/scalaz/issues/599.
    private val cleanup: AtomicBoolean = new AtomicBoolean(false)

    def feedA(as: Seq[A])(y2: Wye[L, R, O]): Wye[L, R, O]
    def haltA(e: Throwable)(y2: Wye[L, R, O]): Wye[L, R, O]

    //feeds the wye by element or signals halt to wye, producing next state of process
    def receive(r: Throwable \/ Step[Task, A])(y2: Wye[L, R, O]): Wye[L, R, O] = {
      r match {
        case \/-(s) =>
          step = \/-(s)
          s match {
            case Step(\/-(h), Halt(e), c) => haltA(e)(feedA(h)(y2))
            case Step(\/-(h),t,c) => feedA(h)(y2)
            case Step(-\/(e), t, c) => haltA(e)(y2)
          }

        case -\/(e)    =>
          step = \/-(Step.failed(e))
          haltA(e)(y2)
      }
    }

    def isClean: Boolean = step.toOption.exists { case s if s.isCleaned => true ; case _ => false }
    def isHalt: Boolean = step.toOption.exists { case s if s.isHalted => true ; case _ => false }
    def haltedBy: Option[Throwable] = step.toOption.collect {
      case Step(_,Halt(e),Halt(_)) => e
    }

    //returns true when the process is cleaned, or runs the cleanup and returns false
    //if process is running is no-op and returns false
    def runCleanup(a: Actor[Msg], e: Throwable): Boolean =  step match {
        case \/-(s) if s.isCleaned => true
        case \/-(s) => runClean(s.cleanup, e, a); false
        case -\/(c) if cleanup.get == false =>
          a ! Ready(this,\/-(Step.failed(Interrupted))) //this will have to be removed once Task will return error once interrupted in scalaz.task see comment to cleanup val above
          false
        case -\/(c) => false
      }

    def pull(a: Actor[Msg]): Boolean =  step match {
        case \/-(s) if s.isCleaned => false
        case \/-(s) if s.isHalted => runClean(s.cleanup,End,a) ; true
        case \/-(s) => run(s.tail,a) ; true
        case -\/(c) => false // request`s task is in process
      }

    private def runClean(c:Process[Task,A], e: Throwable, actor: Actor[Msg]) : Unit = {
      cleanup.set(true)
      step = -\/(halt)
      c.causedBy(e).run.runAsync { cb => actor ! Ready(this, cb.map(_ => Step.failed(e)))}
    }

    private def run(s: Process[Task, A], actor: Actor[Msg]): Unit = {
      step = -\/(s.cleanup)
      s.runStep.runAsyncInterruptibly ({ cb => actor ! Ready(this, cb) },cleanup)
    }
  }

  /**
   * Actor that backs the `wye`. Actor is reading non-deterministically from both sides
   * and interprets wye to produce output stream.
   *
   * @param pl left process
   * @param pr right process
   * @param y  wye to control queueing and merging
   * @param S  strategy, preferably executor service
   * @tparam L Type of left process element
   * @tparam R Type of right process elements
   * @tparam O Output type of resulting process
   * @return Process with merged elements.
   */
  def wyeActor[L, R, O](pl: Process[Task, L], pr: Process[Task, R])(y: Wye[L, R, O])(S: Strategy): Process[Task, O] = {

    //current state of the wye
    var yy: Wye[L, R, O] = y

    //cb to be completed for `out` side
    var out: Option[(Throwable \/ Seq[O]) => Unit] = None

    //forward referenced actor
    var a: Actor[Msg] = null

    //Bias for reading from either left or right.
    var leftBias: Boolean = true

    case class LeftWyeSide(var step: (Process[Task,L] \/ Step[Task, L])) extends WyeSideOps[L, L, R, O] {
      def feedA(as: Seq[L])(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.feedL(as)(y2)
      def haltA(e: Throwable)(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.haltL(e)(y2)
      override def toString: String = "Left"
    }

    case class RightWyeSide(var step: (Process[Task,R] \/ Step[Task, R])) extends WyeSideOps[R, L, R, O] {
      def feedA(as: Seq[R])(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.feedR(as)(y2)
      def haltA(e: Throwable)(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.haltR(e)(y2)
      override def toString: String = "Right"
    }

    val L: LeftWyeSide = LeftWyeSide(\/-(Step.fromProcess(pl)))
    val R: RightWyeSide = RightWyeSide(\/-(Step.fromProcess(pr)))

    //switches right and left to cleanup (if not yet switched) and runs the cleanup
    def tryCleanup(e: Throwable): Boolean = {
      val l = L.runCleanup(a, e) && L.isClean
      val r = R.runCleanup(a, e) && R.isClean
      l && r
    }

    def completeOut(cb: (Throwable \/ Seq[O]) => Unit, r: Throwable \/ Seq[O]): Unit = {
      out = None
      S(cb(r))
    }

    @tailrec
    def tryCompleteOut(cb: (Throwable \/ Seq[O]) => Unit, y2: Wye[L, R, O]): Wye[L, R, O] = {
      y2.unemit match {
        case (h, ny) if h.nonEmpty =>
          completeOut(cb, \/-(h))
          ny

        case (_, ny@Halt(e))  =>
          if (tryCleanup(e)) completeOut(cb, -\/(e))
          ny

        case (_, ny@AwaitL(_, _, _)) =>   L.haltedBy match {
            case Some(e) => tryCompleteOut(cb, ny.killBy(e))
            case None    => L.pull(a); ny
          }

        case (_, ny@AwaitR(_, _, _)) =>   R.haltedBy match {
            case Some(e) => tryCompleteOut(cb, ny.killBy(e))
            case None    => R.pull(a); ny
          }

        case (_, ny@AwaitBoth(_, _, _)) =>
          if (L.isHalt && R.isHalt) {
            tryCompleteOut(cb, ny.killBy(L.haltedBy.get))
          } else {
            if (leftBias) { L.pull(a); R.pull(a) }
            else { R.pull(a); L.pull(a) }
            ny
          }

        case (_, Emit(_, _)) =>
          val e = new Exception("Impossible: Emit after unemit?")
          completeOut(cb,-\/(e))
          Halt(e)
      }
    }

    a = Actor.actor[Msg]({
      case Ready(side: WyeSide[Any, L, R, O]@unchecked, stepr) =>
        leftBias = side == R
        val ny = side.receive(stepr)(yy)
        yy = out match {
          case Some(cb) => tryCompleteOut(cb,ny)
          case None     => ny
        }

      case get: Get[O@unchecked] =>
        out = Some(get.cb)
        yy = tryCompleteOut(get.cb, yy)

      case Done(rsn, cb) =>
        val cbOut = cb compose ((_: Throwable \/ Seq[O]) => \/-(()))
        out = Some(cbOut)
        yy = tryCompleteOut(cbOut, yy.killBy(rsn))

    })(S)

    repeatEval(Task.async[Seq[O]](cb => a ! Get(cb))).flatMap(emitSeq(_)) onComplete
      suspend(eval(Task.async[Unit](cb => a ! Done(End, cb)))).drain
  }

}
