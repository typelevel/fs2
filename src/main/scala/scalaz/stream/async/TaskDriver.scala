package scalaz.stream.async

import scalaz.stream.Process
import scalaz.stream.Process.{Halt, Step, Emit, Await, Cont}
import scalaz.{Trampoline, \/}
import scalaz.\/._
import scalaz.concurrent.{Strategy, Actor, Future, Task}
import scalaz.stream.Cause
import scalaz.stream.Cause.{Kill, EarlyCause}
import scalaz.stream.Util._

/**
 * Asynchronous driver for Process, where `F` is specialized to TASK 
 */
object TaskDriver {

  /**
   * Runs the supplied task with possible chance to interrupt it.
   * Task is interrupted when resulting callback is applied.
   *
   * Whenever task is interrupted, following is guaranteed:
   * - if, there was asynchronous code of task in process, the `cb` will get invoked immediatelly with `Task.TaskInterrupted` exception
   * - if the task is complete, then interruption is no-op
   * - if the task is running `pure` code, that code is completed and `cb` is invoked with result of that code
   *
   * Whenever task is completed, following is guaranteed
   * - if the interrupt was received while evaluating the task (pure code) then `cb` is called with result of the task evaluation
   * - if the interrupt was received while evaluating the task (async code) then evaluated result is discarded
   * - otherwise `cb` is called with result of task evaluatin
   *
   * @param task    Task to evaluate
   * @param cb      cb to invoke after evalutation is done, or when task is interrupted
   */
  def runTaskAsyncInterruptibly[A](task: Task[A], cb: (Throwable \/ A) => Unit): () => Unit = {
    sealed trait M
    case object Interrupt extends M
    case class Complete(r: Throwable \/ A) extends M
    case class CompleteBind(r: Any, g: Any => Future[Throwable \/ A]) extends M

    var open: Boolean = true

    def complete(r: (Throwable \/ A)): Unit = {
      open = false
      cb(r)
    }

    def tryCompleteTask(current: Future[Throwable \/ A], actor: => Actor[M]): Unit = {
      current.step match {
        case Future.Now(r) => complete(r)
        case Future.Async(onFinish) =>
          onFinish((r: Throwable \/ A) => Trampoline.done(actor ! Complete(r)))
        case Future.BindAsync(onFinish, g) =>
          onFinish((r: Any) => Trampoline.done(actor ! CompleteBind(r, g)))

      }
    }

    lazy val actor: Actor[M] = Actor[M]({
      case Complete(r) if open => complete(r)
      case CompleteBind(r, g) if open => tryCompleteTask(g(r), actor)
      case Interrupt if open => complete(left(Task.TaskInterrupted))
      case _ => //no-op

    })(Strategy.Sequential)

    tryCompleteTask(task.get, actor)
    () => {actor ! Interrupt}
  }


  /**
   * Asynchronous execution of this Process. Note that this method is not resource safe unless
   * callback is called with _left_ side completed. In that case it is guaranteed that all cleanups
   * has been successfully completed.
   *
   * User of this method is responsible for any cleanup actions to be performed by running the
   * next Process obtained on right side of callback.
   *
   * This method returns a function, that when applied, causes the running computation to be interrupted.
   * That is useful of process contains any asynchronous code, that may be left with incomplete callbacks.
   * If the evaluation of the process is interrupted, then the interruption is only active if the callback
   * was not completed before, otherwise interruption is no-op.
   *
   * This implementation also guarantees that when `cb` is invoked on right side, then sequence is always non-empty.
   * This is usefully to run two processes truly in parallel w/o consulting higher level combinator (i.e. wye/merge).
   *
   * This implementation as well guaranteed that any onComplete // onFail // onKill code is consulted exactly once
   * whether the computation was or was not interrupted.
   *
   * @param cb  result of the asynchronous evaluation of the process. Note that, the callback is never called
   *            on the right side, if the sequence is empty.
   * @param S  Strategy to use when evaluating the process. Note that `Strategy.Sequential` may cause SOE.
   * @return   Function to interrupt the evaluation
   */
  protected[stream] final def runAsync[O](
    process:Process[Task,O]
    , cb: Cause \/ (Seq[O], Cont[Task, O]) => Unit
    )(implicit S: Strategy): (EarlyCause) => Unit = {

    sealed trait M
    case class Eval(p:Process[Task,O]) extends M
    case class AwaitDone(r:Throwable \/ Any, awt:Await[Task,Any,O], cont:Cont[Task, O]) extends M
    case object Interrupt extends M

    var interrupted:Boolean = false
    var interrupt:Option[() => Unit] = None

    // runs one step of the evaluation
    // note that `step` must terminated and is defaulted to 10 steps now
    // on await or empty result will again consult actor to give chance for interrupt
    def evalStep(p:Process[Task,O], actor:Actor[M]):Unit = {
      p.step match {
        case Step(Emit(os),next) if os.isEmpty => actor ! Eval(next.continue)
        case Step(Emit(os),next) =>  S(cb(right(os -> next)))
        case Halt(cause) => S(cb(left(cause)))
        case Step(awt@Await(t,_), next) =>
          interrupt = Some(
            runTaskAsyncInterruptibly[Any](t,{ r =>
              S(actor ! AwaitDone(r,awt,next)) //need to wrap to `S` for stack safety
            })
          )

      }
    }

    def runCleanup(p:Process[Task,O]):Unit = {
      p.run.runAsync { r =>
        val cause =
          EarlyCause.fromTaskResult(r)
          .fold(identity, _ => Kill)
        S(cb(left(cause)))
      }
    }


    lazy val actor: Actor[M] = Actor[M]({
      case Eval(p) =>
        if (!interrupted) evalStep(p,actor)
        else runCleanup(p.kill)

      case AwaitDone(r,awt, next) =>
        interrupt = None
        val result = EarlyCause.fromTaskResult(r)
        if (!interrupted) actor ! Eval(Try(awt.rcv(result).run) +: next)
        else {
          val cause = result.fold(identity, _ => Kill) //drop the result or use the cause from the evaluation
          val p = Try(awt.rcv(left(cause)).run) +: next
          runCleanup(p)
        }


      case Interrupt  =>
        interrupted = true
        interrupt.foreach(_())
        interrupt = None

    })(S)

    actor ! Eval(process)

    {(c:EarlyCause) => actor ! Interrupt}
  }

}
