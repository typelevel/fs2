package scalaz.stream.actor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala._
import scala.annotation.tailrec
import scalaz._
import scalaz.concurrent.{Future, Strategy, Actor, Task}
import scalaz.stream.Process._
import scalaz.stream.Step
import scalaz.stream.wye.{AwaitBoth, AwaitR, AwaitL}
import scalaz.stream.{Process, wye}

object WyeActor {

  val Interrupted = new InterruptedException {
    override def fillInStackTrace(): Throwable = this
  }

  /**
   * Execute one step of the process `p` and after it's done call `cb` with the result.
   *
   * This driver of process runs the process with following semantics:
   *
   *   - if the evaluation of next step of process results in `Halt` then the callback is called indicating
   *     that process evaluation is done passing the reason contained in Halt
   *   - if the evaluation of next step of process results in `Emit` then the callback is called passing
   *     the emitted elements and next step to be called. Also the `cleanup` of the last `Await` evaluated is
   *     passed to give a chance user of this driver to cleanup any resources, should the process terminate after
   *     emit.
   *   - if the evaluation of next step of process results in `Await`, next step is evaluated, until `Halt` or `Emit`
   *     is encountered.
   *
   *  Evaluation of the process is forked to different thread by utilizing the supplied `S` strategy.
   *  This is motivated to assure stack safety during evaluation and interruption.
   *
   *  Note that at any time evaluation may be interrupted by invoking resulting function of this driver.
   *  If the evaluation is interrupted then immediately, cleanup from last await is executed. If, at
   *  time of interruption process was evaluating an `Await` request, then, after that request is completed
   *  the evaluation will continue with process returned by the await that will switch to cleanup phase with
   *  `Interrupted` as an exception.
   *
   *  Note that, when invoking the `cleanup` in case of `interrupt` then, there is chance that cleanup code will be
   *  called twice. If that is not desired, wrap the code in `Process.affine` combinator.
   *
   *
   * @param cb Called with computed step.
   *           If the execution of the step was terminated then the head of the step contains exception `Interrupted`.
   * @return   Function which terminates execution of the step if it is still in progress.
   */
  def runAsyncInterruptibly[O](p: Process[Task, O])(cb: Step[Task, O] => Unit)(implicit S: Strategy): () => Unit = {

    // True when execution of the step was completed or terminated and actor should ignore all subsequent messages.
    var completed = false

    // Cleanup from the last await whose `req` has been started.
    var cleanup: Process[Task, O] = halt
    var a: Actor[Option[Process[Task, O]]] = null

    a = new Actor[Option[Process[Task, O]]]({

      // Execute one step of the process `p`.
      case Some(p) if !completed => p match {
        case Emit(h, t) =>
          completed = true
          cb(Step(\/-(h), t, cleanup))

        case h@Halt(e) =>
          completed = true
          cb(Step(-\/(e), h, halt))

        case Await(req, rcv, fb, cln) =>
          cleanup = cln
          req.runAsync({
            case \/-(r0)  => a ! Some(rcv(r0))
            case -\/(End) => a ! Some(fb)
            case -\/(e)   => a ! Some(cln.causedBy(e))
          })
      }

      //result from the last `Await` after process was interrupted
      //this is run _after_ callback of this driver is completed.
      case Some(p) => p.killBy(Interrupted).run.runAsync(_ => ())


      // Terminate process: Run cleanup but don't interrupt currently running task.
      case None if !completed =>
        completed = true
        cleanup.run.runAsync {
          case \/-(_) => cb(Step(-\/(Interrupted), Halt(Interrupted), halt))
          case -\/(e) =>
            val rsn = CausedBy(e, Interrupted)
            cb(Step(-\/(rsn), Halt(rsn), halt))
        }

      case _ => ()
    })(S)

    a ! Some(p)
    () => { a ! None }
  }

 

  trait WyeSide[A, L, R, O] {
    /** returns next wye after processing the result of the step **/
    def receive(step: Step[Task,A])(y2: Wye[L,R,O]): Wye[L,R,O]
  }

  sealed trait WyeSideState[A]

  /**
   * Process of this side is not running and hasn't halted.
   * @param cont Continuation. To be run when wye awaits this side.
   * @param cleanup Cleanup from the last evaluated `Await` of this process. To be run when wye halts.
   */
  final case class Ready[A](cont: Process[Task,A], cleanup: Process[Task,A]) extends WyeSideState[A]

  /**
   * Step is running. Actor will receive `StepCompleted` when finished.
   *
   * The reason for this state is that wye requested data from this side (awaited this side) or that
   * wye terminated this side.
   * @param interrupt To be called when terminating running side.
   */
  final case class Running[A](interrupt: () => Unit) extends WyeSideState[A]

  /**
   * This side has halted and wye knows it.
   */
  final case class Done[A](cause: Throwable) extends WyeSideState[A]

  sealed trait Msg

  /**
   * Notification that side completed step.
   */
  final case class StepCompleted[A,L,R,O](from: WyeSide[A,L,R,O], step: Step[Task,A]) extends Msg

  /**
   * Request for data from wye.
   */
  final case class Get[O](cb: (Throwable \/ Seq[O]) => Unit) extends Msg

  /**
   * Request to terminate wye and consequently both sides.
   */
  final case class Terminate(cause: Throwable, cb: (Throwable \/ Unit) => Unit) extends Msg

  trait WyeSideOps[A, L, R, O] extends WyeSide[A, L, R, O] {

    var state: WyeSideState[A]

    def feedA(as: Seq[A])(y2: Wye[L, R, O]): Wye[L, R, O]
    def haltA(e: Throwable)(y2: Wye[L, R, O]): Wye[L, R, O]

    //feeds the wye by element or signals halt to wye, producing next state of process
    def receive(step: Step[Task, A])(y2: Wye[L, R, O]): Wye[L, R, O] = {
      val fedY = feedA(step.head.getOrElse(Nil))(y2)
      step match {
        // `runStepAsyncInterruptibly` guarantees that halted process is cleaned.
        case Step(_, Halt(e), _) =>
          state = Done(e)
          haltA(e)(fedY)
        case Step(_, tail, cleanup) =>
          state = Ready(tail, cleanup)
          fedY
      }
    }

    def isDone: Boolean = doneBy.isDefined

    def doneBy: Option[Throwable] = state match {
      case Done(e) => Some(e)
      case _ => None
    }

    def pull(actor: Actor[Msg]): Unit = state match {
      case Ready(p, cleanup) => runStep(p, actor)
      case Running(_) | Done(_) => ()
    }

    /**
     * Called when wye is `Halt`.
     */
    def terminate(e: Throwable, actor: Actor[Msg]): Unit = state match {
      case Ready(_, cleanup) =>
        runStep(cleanup.drain, actor)
        // Replace interrupt by noop, so cleanup won't be interrupted.
        state = Running(() => ())
      case Running(interrupt) => interrupt()
      case Done(_) => ()
    }

    private def runStep(p: Process[Task,A], actor: Actor[Msg]): Unit = {
      val interrupt = runAsyncInterruptibly[A](p)(step => actor ! StepCompleted(this, step))
      state = Running(interrupt)
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

    case class LeftWyeSide(var state: WyeSideState[L] = Done(End)) extends WyeSideOps[L,L,R,O] {
      def feedA(as: Seq[L])(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.feedL(as)(y2)
      def haltA(e: Throwable)(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.haltL(e)(y2)
      override def toString: String = "Left"
    }

    case class RightWyeSide(var state: WyeSideState[R] = Done(End)) extends WyeSideOps[R,L,R,O] {
      def feedA(as: Seq[R])(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.feedR(as)(y2)
      def haltA(e: Throwable)(y2: Process.Wye[L, R, O]): Process.Wye[L, R, O] = wye.haltR(e)(y2)
      override def toString: String = "Right"
    }

    val L: LeftWyeSide = LeftWyeSide()
    val R: RightWyeSide = RightWyeSide()
    yy = L.receive(Step.fromProcess(pl))(yy)
    yy = R.receive(Step.fromProcess(pr))(yy)

    def completeOut(cb: (Throwable \/ Seq[O]) => Unit, r: Throwable \/ Seq[O]): Unit = {
      out = None
      S(cb(r))
    }

    /**
     * When awaiting: wye is killed iff it's certain that no awaited side can complete a step.
     */
    @tailrec
    def tryCompleteOut(cb: (Throwable \/ Seq[O]) => Unit, y2: Wye[L, R, O]): Wye[L, R, O] = {
      y2.unemit match {
        // This is never case when handling `Terminate` request because wye is killed.
        case (h, ny) if h.nonEmpty =>
          completeOut(cb, \/-(h))
          ny

        // Terminate both sides when wye is `Halt`.
        case (_, ny@Halt(e))  =>
          L.terminate(e, a)
          R.terminate(e, a)
          // Ensures that we don't complete `Terminate` request before all sides are done.
          if (L.isDone && R.isDone) completeOut(cb, -\/(e))
          ny

        case (_, ny@AwaitL(_, _, _)) => L.doneBy match {
            case Some(e) => tryCompleteOut(cb, ny.killBy(e))
            case None    => L.pull(a); ny
          }

        case (_, ny@AwaitR(_, _, _)) => R.doneBy match {
            case Some(e) => tryCompleteOut(cb, ny.killBy(e))
            case None    => R.pull(a); ny
          }

        case (_, ny@AwaitBoth(_, _, _)) =>
          (L.doneBy, R.doneBy) match {
            case (Some(e), Some(_)) => tryCompleteOut(cb, ny.killBy(e))
            case _ =>
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
      case StepCompleted(side: WyeSide[Any,L,R,O]@unchecked, step) =>
        leftBias = side == R
        val ny = side.receive(step)(yy)
        yy = out match {
          case Some(cb) => tryCompleteOut(cb,ny)
          case None     => ny
        }

      case get: Get[O@unchecked] =>
        out = Some(get.cb)
        yy = tryCompleteOut(get.cb, yy)

      case Terminate(cause, cb) =>
        val cbOut = cb compose ((_: Throwable \/ Seq[O]) => \/-(()))
        out = Some(cbOut)
        yy = tryCompleteOut(cbOut, yy.killBy(cause))

    })(S)

    repeatEval(Task.async[Seq[O]](cb => a ! Get(cb))).flatMap(emitSeq(_)) onComplete
      suspend(eval(Task.async[Unit](cb => a ! Terminate(End, cb)))).drain
  }

}
