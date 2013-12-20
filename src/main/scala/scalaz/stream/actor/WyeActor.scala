package scalaz.stream.actor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala._
import scala.annotation.tailrec
import scalaz._
import scalaz.concurrent.{Strategy, Actor, Task}
import scalaz.stream.Process._
import scalaz.stream.Step
import scalaz.stream.wye.{AwaitBoth, AwaitR, AwaitL}
import scalaz.stream.{Process, wye}

object WyeActor {

  /**
   * Evaluates one step of the process `p` and calls the callback `cb` with the evaluated step `s`.
   * Returns a function for interrupting the evaluation.
   *
   * `s.tail` is `Halt` iff the process `p` halted (by itself or by interruption).
   * In such case `p` was also cleaned and `s.cleanup` is `Halt` too.
   *
   * Otherwise `s.cleanup` contains the cleanup from the last evaluated `Await`.
   *
   *
   *
   * RESOURCE CLEANUP AFTER INTERRUPTION
   *
   * When interrupted, this function runs cleanup from the last evaluated `Await` to free resources.
   * So if you are allocating resources with
   *
   *   await(alloc)(r => use(r).onComplete(freeP(r)), halt, halt)
   *
   * then you risk a leakage. Since when interrupt happens in `allocate` after allocation
   * then `recv` is not called and `halt` is used for cleanup. The correct way of allocation is
   *
   *   def allocAndUse(r: Res) = await(alloc(r))(_ => use, halt, halt).onComplete(freeP(r))
   *
   *   await(getHandleToUnallocatedResource)(r => allocAndUse(r), halt, halt)
   *
   * where
   *
   *   def freeP(r: Res) = await(free(r))(_ => halt, halt, await(free(r))(_ => halt, halt, halt))
   *
   * and `free` is idempotent. `freeP` must take into account situation when `free(r)` in the outer
   * `await` is interrupted before the resource is freed. `free` must be idempotent since when
   * `free(r)` in outer `await` is interrupted after freeing the resource it will be called again.
   *
   */
  final def runStepAsyncInterruptibly[O](p: Process[Task,O], cb: Step[Task,O] => Unit): () => Unit = {
    val interruptedExn = new InterruptedException

    trait RunningTask {
      def interrupt: Unit
    }
    case class RunningTaskImpl[A](val complete: Throwable \/ A => Unit) extends RunningTask {
      def interrupt: Unit = complete(-\/(interruptedExn))
    }

    val interrupted = new AtomicBoolean(false)
    val runningTask = new AtomicReference[Option[RunningTask]](None)

    def interrupt() = {
      interrupted.set(true)
      runningTask.getAndSet(None).foreach(_.interrupt)
    }

    def clean(step: Step[Task,O]) = step match {
      case Step(head, h@Halt(_), c) if !c.isHalt => c.run.map(_ => Step(head, h, halt))
      case _ => Task.now(step)
    }

    def go(cur: Process[Task,O], cleanup: Process[Task,O]): Task[Step[Task,O]] = {
      def onAwait[A](req: Task[A], recv: A => Process[Task,O], fb: Process[Task,O], c: Process[Task,O]): Task[Step[Task,O]] = {
        // We must ensure that the callback `cb` is called exactly once.
        //
        // There are currently two cases who calls the callback:
        // - Interruptible task successfully completes and calls the callback itself.
        // - Interruptible task is interrupted and it doesn't call the callback - we must call it.
        //
        // After https://github.com/scalaz/scalaz/issues/599 is resolved
        // interruptible task will always call the callback itself.
        Task.async[A] { (cb: Throwable \/ A => Unit) =>
          val running = RunningTaskImpl(cb)
          runningTask.set(Some(running))
          if (interrupted.get) interrupt()
          else req.runAsyncInterruptibly(r => runningTask.getAndSet(None).foreach(_ => running.complete(r)), interrupted)
        }.attempt.flatMap[Step[Task,O]] {
          case -\/(End) => go(fb,c)
          case -\/(e) => Task.now(Step(-\/(e), Halt(e), c))
          case \/-(a) =>
            try go(recv(a), c)
            catch { case e: Throwable => Task.now(Step(-\/(e), Halt(e), c)) }
        }
      }

      cur match {
        case _ if interrupted.get => Task.now(Step(-\/(interruptedExn), Halt(interruptedExn), cleanup))
        // Don't run cleanup from the last `Await` when process halts normally.
        case h@Halt(e) => Task.now(Step(-\/(e), h, halt))
        case Emit(h, t) =>
          val (nh,nt) = t.unemit
          val hh = h ++ nh
          if (hh.isEmpty) go(nt, cleanup)
          else Task.now(Step(\/-(hh), nt, cleanup))
        case Await(req, recv, fb, c) => onAwait(req, recv, fb, c)
      }
    }

    go(p, halt).flatMap(clean).runAsync {
      case \/-(step) => cb(step)
      case -\/(_) => () // Impossible - step is always computed.
    }

    interrupt
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
      val interrupt = runStepAsyncInterruptibly[A](p, step => actor ! StepCompleted(this, step))
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
