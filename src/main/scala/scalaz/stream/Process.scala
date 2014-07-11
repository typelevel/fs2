package scalaz.stream

import java.util.concurrent.ScheduledExecutorService

import scala.annotation.tailrec
import scala.collection.SortedMap
import scala.concurrent.duration._
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.{Catchable, Functor, Monad, MonadPlus, Monoid, Nondeterminism, \/, ~>}

sealed trait Process[+F[_], +O]
  extends Process1Ops[F, O]
          with TeeOps[F,O] {

 import scalaz.stream.Process._
 import scalaz.stream.Util._

  /**
   * Generate a `Process` dynamically for each output of this `Process`, and
   * sequence these processes using `append`.
   */
  final def flatMap[F2[x] >: F[x], O2](f: O => Process[F2, O2]): Process[F2, O2] =
    this match {
      case Halt(_) | Emit(Seq()) => this.asInstanceOf[Process[F2, O2]]
      case Emit(os)              => os.tail.foldLeft(Try(f(os.head)))((p, n) => p ++ Try(f(n)))
      case aw@Await(_, _)        => aw.extend(_ flatMap f)
      case ap@Append(p, n)       => ap.extend(_ flatMap f)
    }

  /** Transforms the output values of this `Process` using `f`. */
  final def map[O2](f: O => O2): Process[F, O2] =
    flatMap { o => emit(f(o)) }


  /**
   * Feed the output of this `Process` as input of `p1`. The implementation
   * will fuse the two processes, so this process will only generate
   * values as they are demanded by `p1`. If `p1` signals termination, `this`
   * is killed with same reason giving it an opportunity to cleanup.
   * is killed with same reason giving it an opportu nity to cleanup.
   */
  final def pipe[O2](p1: Process1[O, O2]): Process[F, O2] = p1.suspendStep.flatMap({ s1 =>
    Util.debug(s"PIPE p1: $p1 | s1: $s1 | this: $this")
    s1 match {
      case Step(AwaitP1(rcv1)) => this.step match {
        case s@Step(awt@Await(_, _)) => awt.extend(p => (p onHalt s.next) pipe p1)
        case s@Step(Emit(os))    => s.continue pipe process1.feed(os)(p1)
        case Halt(End)           => p1.disconnect.swallowKill
        case Halt(rsn)           => p1.disconnect.causedBy(rsn)

      }
      case Step(Emit(os))      => Emit(os) onHalt { rsn => this.pipe(s1.next(rsn)) }
      case Halt(rsn1)          => this.kill onHalt { _ => fail(rsn1) }
    }
  })

  /** Operator alias for `pipe`. */
  final def |>[O2](p2: Process1[O, O2]): Process[F, O2] = pipe(p2)


  /**
   * Evaluate this process and produce next `Step` of this process.
   *
   * Result may be in state of `Append` that indicates there are other process steps to be evaluated
   * or in `Halt` that indicates process has finished and there is no more evaluation to be processed
   *
   * Note this evaluation is not resource safe, that means user must assure the evaluation whatever
   * is produced as next step.
   */
  final def step: Step[F, O] = Step(this, Vector.empty)


  final def suspendStep: Process[Nothing, Step[F, O]] =
    suspend { emit(step) }


  /**
   * Use a `Tee` to interleave or combine the outputs of `this` and
   * `p2`. This can be used for zipping, interleaving, and so forth.
   * Nothing requires that the `Tee` read elements from each
   * `Process` in lockstep. It could read fifty elements from one
   * side, then two elements from the other, then combine or
   * interleave these values in some way, etc.
   *
   * If at any point the `Tee` awaits on a side that has halted,
   * we gracefully kill off the other side by sending the `Kill`, then halt.
   *
   * If at any point tee terminates, both processes are terminated (Left side first)
   * with `Kill`, and resulting process terminates with reason that caused `tee` to terminate.
   */
  final def tee[F2[x]>:F[x],O2,O3](p2: Process[F2,O2])(t: Tee[O,O2,O3]): Process[F2,O3] = {
    import scalaz.stream.tee.{AwaitL, AwaitR, disconnectL, disconnectR, feedL, feedR}
    t.suspendStep flatMap {
      case Step(AwaitL(_)) => this.step match {
        case s@Step(awt@Await(rq,rcv)) => awt.extend { p => (p onHalt s.next).tee(p2)(t) }
        case s@Step(Emit(os)) => s.continue.tee(p2)(feedL[O,O2,O3](os)(t))
        case hlt@Halt(rsn) => hlt.tee(p2)(disconnectL(t)).causedBy(rsn)
      }

      case Step(AwaitR(_)) => p2.step match {
        case s@Step(awt:Await[F2,Any,O2]@unchecked) =>  awt.extend { p => this.tee(p onHalt s.next)(t) }
        case s@Step(Emit(o2s:Seq[O2]@unchecked)) => this.tee(s.continue)(feedR[O,O2,O3](o2s)(t))
        case hlt@Halt(rsn) => this.tee(hlt)(disconnectR(t)).causedBy(rsn)
      }

      case s@Step(emt@Emit(o3s)) => emt onHalt { rsn => this.tee(p2)(s.next(rsn)) }
      case Halt(rsn) => this.kill onHalt { _ => p2.kill onHalt { _ => fail(rsn) } }
    }
  }


  /**
   * If this process halts without an error, attaches `p2` as the next step.
   * Also this won't attach `p2` whenever process was `Killed` by downstream
   */
  final def append[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = {
    onHalt(_.fold(p2)({
      case End => p2
      case rsn => fail(rsn)
    }))
  }

  /** alias for `append` **/
  final def ++[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = append(p2)

  /**
   * Run this `Process`, then, if it self-terminates, run `p2`.
   * This differs from `append` in that `p2` is not consulted if this
   * `Process` terminates due to the last  Await
   * resulting in `End` signalling source being exhausted.
   */
  final def fby[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = {
    onHalt(_.fold(p2)(fail))
  }

  final def asFinalizer: Process[F, O] = this

  /**
   * Catch exceptions produced by this `Process`, not including termination by `Continue`, `End`, `Kill`
   * and uses `f` to decide whether to resume a second process.
   */
  final def attempt[F2[x]>:F[x],O2](
    f: Throwable => Process[F2,O2] = (t:Throwable) => emit(t)
    ): Process[F2, O2 \/ O] =
    map(right) onHalt ( _.fold(emitAll(Seq.empty[O2\/O]):Process[F2,O2\/O])({
      case End => halt
      case rsn => Try(f(rsn)).map(left)
    })
  )

  /**
   * Attached `cause` when this Process terminates normally (either by `Continue` or `End`)
   * If this process terminates abnormally, attaches cause via `CausedBy` to this process.
   */
  final def causedBy(cause: Throwable): Process[F, O] = {
    onHalt {
      _.fold(fail(cause))({
        case End     => fail(cause)
        case `cause` => fail(cause)
        case rsn     => cause match {
          case End => fail(rsn)
          case _   => fail(CausedBy(rsn, cause))
        }
      })
    }
  }

  /**
   * Used when a transducer is terminated by awaiting on a branch that
   * is in the halted state. Such a process is given the opportunity
   * to emit any final values. All Awaits are converted to terminate with `Kill`
   *
   */
  final def disconnect: Process[Nothing, O] = this.step match {
    case s@Step(emt@Emit(_)) =>  emt onHalt { rsn => s.next(rsn).disconnect }
    case s@Step(awt@Await(_,rcv)) =>
      (Try(rcv(left(Kill)).run) onHalt s.next).disconnect
    case hlt@Halt(rsn) => hlt
  }


  /** Ignore all outputs of this `Process`. */
  final def drain: Process[F, Nothing] = {
     this.suspendStep.flatMap {
      case s@Step(Emit(_)) => s.continue.drain
      case s@Step(awt@Await(_,_)) => awt.extend(_.drain).onHalt(s.next(_).drain)
      case hlt@Halt(rsn) => hlt
    }
  }

  /**
   * Map over this `Process` to produce a stream of `F`-actions,
   * then evaluate these actions.
   */
  def evalMap[F2[x]>:F[x],O2](f: O => F2[O2]): Process[F2,O2] =
    map(f).eval

  /**
   * Map over this `Process` to produce a stream of `F`-actions,
   * then evaluate these actions in batches of `bufSize`, allowing
   * for nondeterminism in the evaluation order of each action in the
   * batch.
   */
  def gatherMap[F2[x]>:F[x],O2](bufSize: Int)(f: O => F2[O2])(
    implicit F: Nondeterminism[F2]): Process[F2,O2] =
    map(f).gather(bufSize)


  /**
   * Catch some of the exceptions generated by this `Process`, rethrowing any
   * not handled by the given `PartialFunction` and stripping out any values
   * emitted before the error.
   */
  def handle[F2[x]>:F[x],O2](f: PartialFunction[Throwable, Process[F2,O2]])(implicit F: Catchable[F2]): Process[F2, O2] =
    attempt(rsn => f.lift(rsn).getOrElse(fail(rsn)))
    .dropWhile(_.isRight)
    .map(_.fold(identity, _ => sys.error("unpossible")))

  /**
   * Returns true, if this process is halted
   */
  final def isHalt: Boolean = this match {
    case Halt(_) => true
    case _ => false
  }

  /**
   * Causes this process to be terminated, giving chance for any cleanup actions to be run
   */
  final def kill: Process[F, Nothing] = { //todo: revisit with new `Step` implementeation
    (this.step match {
      case s@Step(Emit(os)) => s.cleanup(Kill)
      case s@Step(Await(req,rcv)) => Try(rcv(left(Kill)).run) onHalt(s.next)
      case hlt@Halt(rsn) => hlt.causedBy(Kill)
    }).drain
  }



  /**
   * Run `p2` after this `Process` completes normally, or in the event of an error.
   * This behaves almost identically to `append`, except that `p1 append p2` will
   * not run `p2` if `p1` halts with an error.
   *
   * If you want to attach code that depends on reason of termination, use `onHalt`
   *
   * Note that, the reason is attached to resulting process after evaluating an `f`
   *
   * Note as well, that `p2` won't get killed if process is killed in merging combinator (pipe, tee, wye...).
   */
  final def onComplete[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] =
    onHalt (_.fold(p2.asFinalizer)(rsn => p2.asFinalizer.causedBy(rsn)))


  /**
   * Runs process generated by evaluating `f` and passing any failure reason for this process to terminate.
   * Note this is run only when process was terminated with failure that means any reason different
   * from `End`.
   *
   * This gives chance for recovering from any unexpected failure of the process.
   */
  final def onFailure[F2[x] >: F[x], O2 >: O](f: Throwable => Process[F2, O2]): Process[F2, O2] =
    onHalt (_.fold(empty:Process[F2,O2])(rsn=>Try(f(rsn))))


  /**
   * When the process terminates either due to `End` or `Throwable`
   * `f` is evaluated to produce next state.
   *
   * Note that `f` is responsible to eventually propagate reason for termination.
   */
  final def onHalt[F2[x] >: F[x], O2 >: O](f: Option[Throwable] => Process[F2, O2]): Process[F2, O2] = {
    val next = (t: Option[Throwable]) => Trampoline.delay(Try(f(t)))
    this match {
      case Append(p, n)   => Append(p, n :+ next)
      case awt@Await(_,_) => Append(awt, Vector(next))
      case emt@Emit(_) => Append(emt, Vector(next))
      case Halt(rsn) =>  Try(f(Some(rsn)))
    }
  }

  /**
   * Attach supplied process only if process has been terminated with `Kill` signal.
   */
  final def onKill[F2[x] >: F[x], O2 >: O](p: => Process[F2, O2]): Process[F2, O2] = {
    onHalt(_.fold(emitAll(Seq.empty[O2]):Process[F2,O2])({
      case Kill => Try(p)
      case rsn => fail(rsn)
    }))
  }

  /**
   * Like `attempt`, but accepts a partial function. Unhandled errors are rethrown.
   */
  def partialAttempt[F2[x]>:F[x],O2](f: PartialFunction[Throwable, Process[F2,O2]])
    (implicit F: Catchable[F2]): Process[F2, O2 \/ O] =
    attempt(err => f.lift(err).getOrElse(fail(err)))



  /**
   * Run this process until it halts, then run it again and again, as
   * long as no errors or `End` or `Kill` occur.
   */
  final def repeat: Process[F, O] = onHalt(_.fold(this.repeat)(fail))


  /**
   * For anly process terminating with kill, this swallows the `Kill` and replaces it with normal termination
   * @return
   */
  final def swallowKill: Process[F,O] = onHalt(_.fold(empty:Process[F,O])({
    case Kill => halt
    case other => fail(other)
  }))

  /** Translate the request type from `F` to `G`, using the given polymorphic function. */
  def translate[G[_]](f: F ~> G): Process[G,O] =
    this.suspendStep.flatMap {
      case s@Step(Emit(os)) => emitAll(os) onHalt(rsn => s.next(rsn).translate(f))
      case s@Step(Await(req,rcv)) =>
        Await[G,Any,O](f(req), r => {
          Trampoline.suspend(rcv(r)).map(_ translate f)
        }) onHalt { rsn => s.next(rsn) translate f }
      case hlt@Halt(rsn) => hlt
    }


  /**
   * Remove any leading emitted values from this `Process`.
   */
  @tailrec
  final def trim: Process[F,O] =
    this.step match {
      case s@Step(Emit(_)) => s.continue.trim
      case _ => this
    }

  /**
   * Removes all emitted elements from the front of this `Process`.
   * The second argument returned by this method is guaranteed to be
   * an `Await`, `Halt` or an `Append`-- if there are multiple `Emit'`s at the
   * front of this process, the sequences are concatenated together.
   *
   * If this `Process` does not begin with an `Emit`, returns the empty
   * sequence along with `this`.
   */
  final def unemit:(Seq[O],Process[F,O]) = {
    @tailrec
    def go(cur: Process[F, O], acc: Vector[O]): (Seq[O], Process[F, O]) = {
      cur.step match {
        case s@Step(Emit(os)) => go(s.continue, acc fast_++ os)
        case s@Step(awt) => (acc,awt onHalt s.next)
        case Halt(rsn) => (acc,Halt(rsn))
      }
    }
    go(this, Vector())
  }


  ///////////////////////////////////////////
  // runXXX
  ///////////////////////////////////////////

  /**
   * Collect the outputs of this `Process[F,O]` into a Monoid `B`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  final def runFoldMap[F2[x] >: F[x], B](f: O => B)(implicit F: Monad[F2], C: Catchable[F2], B: Monoid[B]): F2[B] = {
    def go(cur: Process[F2, O], acc: B): F2[B] = {
      debug(s"RFM cur: $cur, acc: $acc")
      cur.step match {
        case s@Step(Emit(os)) =>
          F.bind(F.point(os.foldLeft(acc)((b, o) => B.append(b, f(o))))) { nacc =>
            go(s.continue, nacc)
          }
        case s@Step(awt:Await[F2,Any,O]@unchecked) =>
          F.bind(C.attempt(awt.req)) { _.fold(
            rsn => go(Try(awt.rcv(left(rsn)).run) onHalt s.next , acc)
            , r => go(Try(awt.rcv(right(r)).run) onHalt s.next, acc)
          )}
        case Halt(End) => F.point(acc)
        case Halt(rsn) => C.fail(rsn)
      }
    }

    go(this, B.zero)
  }


  /**
   * Collect the outputs of this `Process[F,O]`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  final def runLog[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[IndexedSeq[O2]] = {
    F.map(runFoldMap[F2, Vector[O2]](Vector(_))(
      F, C,
      // workaround for performance bug in Vector ++
      Monoid.instance[Vector[O2]]((a, b) => a fast_++ b, Vector())
    ))(_.toIndexedSeq)
  }

  /** Run this `Process` solely for its final emitted value, if one exists. */
  final def runLast[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[Option[O2]] =
    F.map(this.last.runLog[F2,O2])(_.lastOption)

  /** Run this `Process` solely for its final emitted value, if one exists, using `o2` otherwise. */
  final def runLastOr[F2[x] >: F[x], O2 >: O](o2: => O2)(implicit F: Monad[F2], C: Catchable[F2]): F2[O2] =
    F.map(this.last.runLog[F2,O2])(_.lastOption.getOrElse(o2))

  /** Run this `Process`, purely for its effects. */
  final def run[F2[x] >: F[x]](implicit F: Monad[F2], C: Catchable[F2]): F2[Unit] =
    F.void(drain.runLog(F, C))


}


object Process {

 import scalaz.stream.Util._

  type Trampoline[+A] = scalaz.Free.Trampoline[A]
  val Trampoline = scalaz.Trampoline

  /**
   * Tags a state of process that has no appended tail, tha means can be Halt, Emit or Await
   */
  sealed trait HaltEmitOrAwait[+F[_], +O] extends Process[F, O]

  object HaltEmitOrAwait {

    def unapply[F[_], O](p: Process[F, O]): Option[HaltEmitOrAwait[F, O]] = p match {
      case emit: Emit[O@unchecked]                => Some(emit)
      case halt: Halt                             => Some(halt)
      case aw: Await[F@unchecked, _, O@unchecked] => Some(aw)
      case _                                      => None
    }

  }

  sealed trait EmitOrAwait[+F[_],+O] extends Process[F,O]


  /**
   * The `Halt` constructor instructs the driver to stop
   * due to the given reason as `Throwable`.
   * The special `Throwable` instance `Process.End`
   * indicates normal termination. It's more typical to construct a `Halt` via
   * `Process.halt` (for normal termination) or
   * `Process.fail(err)` (for termination with an error).
   */
  case class Halt(rsn: Throwable) extends HaltEmitOrAwait[Nothing, Nothing] with Step[Nothing, Nothing]

  /**
   * The `Emit` constructor instructs the driver to emit
   * the given sequence of values to the output
   * and then halt execution with supplied reason.
   *
   * Instead calling this constructor directly, please use one
   * of the following helpers:
   *
   * Process.emit
   * Process.emitAll
   */
  case class Emit[+O](seq: Seq[O]) extends HaltEmitOrAwait[Nothing, O] with EmitOrAwait[Nothing,O]

  /**
   * The `Await` constructor instructs the driver to evaluate
   * `req`. If it returns successfully, `recv` is called with result on right side
   * to transition to the next state.
   * In case the req terminates with either a failure (`Throwable`) or
   * an `End` indicating normal termination, these are passed to rcv on the left side,
   * to produce next state.
   *
   *
   * Instead of this constructor directly, please use:
   *
   * Process.await
   *
   */
  case class Await[+F[_], A, +O](
    req: F[A]
    , rcv: Throwable \/ A => Trampoline[Process[F, O]]
    ) extends HaltEmitOrAwait[F, O] with EmitOrAwait[F,O] {
    /**
     * Helper to modify the result of `rcv` parameter of await stack-safely on trampoline.
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Await[F2, A, O2] =
      Await[F2, A, O2](req, r => Trampoline.suspend(rcv(r)).map(f))
  }


  /**
   * The `Append` constructor instructs the driver to continue with
   * evaluation of first step found in tail Vector.
   *
   * Instead of this constructor please use:
   *
   * Process.append
   */
  case class Append[+F[_], +O](
    head: EmitOrAwait[F, O]
    , tail: Vector[Option[Throwable] => Trampoline[Process[F, O]]]
    ) extends Process[F, O] with Step[F, O] {

    /**
     * Helper to modify the head and appended processes
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Process[F2, O2] = {
      val et = tail.map(n => (t: Option[Throwable]) => Trampoline.suspend(n(t)).map(f))
      Step(Try(f(head)), et)
    }

  }

  /**
   * Represents intermediate step of process.
   * @tparam F
   * @tparam O
   */
  sealed trait Step[+F[_], +O] extends Process[F, O] {

    /**
     * Produces the next process state
     * @return
     */
    def continue: Process[F, O] = next(None)

    /**
     * Switches process to fallback phase given the reason specified.
     * Note that:
     * {{{
     *   this.fallback === this.cleanup(End)
     * }}}
     * @return
     */
    def fallback: Process[F,O] = next(Some(End))

    /**
     * Switches process to cleanup phase given the reson specified.
     *
     * @param rsn
     * @return
     */
    def cleanup(rsn: Throwable): Process[F, O] = next(Some(rsn))

    /**
     * Switches to next state of this process, ignoring the `head` of this Step.
     *
     * @param r Indicates reason passed for next step. None indicates normal continuation and eventual request for next `O`
     *          whereas Some(rsn) indicates that process shall terminate due to input Exhausted (rsn == `End`) or
     *          being killed (`Kill`) or due to unexpected failure (`rsn`)
     * @return  next process state.
     */
    def next(r: Option[Throwable]): Process[F, O] = {
      this match {
        case Append(_, stack) =>
          stack.headOption.fold(Halt(r.getOrElse(End)):Process[F,O])({ f =>
            Step(Try(f(r).run), stack.tail)
          })
        case hlt@Halt(rsn0) =>
          r.fold(hlt: Process[F, O])(rsn => Halt(rsn)) //todo: not sure on that

      }

    }

  }

  object Step {
    def unapply[F[_], O](s: Step[F, O]): Option[EmitOrAwait[F, O]] = s match {
      case Append(h: EmitOrAwait[F@unchecked, O@unchecked], t) => Some(h)
      case hlt@Halt(_)                                         => None
    }

    @tailrec
    private[stream] def apply[F[_], O](
      p: Process[F, O]
      , stack: Vector[Option[Throwable] => Trampoline[Process[F, O]]]
      ): Step[F, O] = {
      Util.debug(s"STEP  CUR: $p, | stack: $stack")

      p match {
        case Emit(os) if os.isEmpty   =>
          stack.headOption match {
            case Some(f) => apply(Try(f(None).run.asInstanceOf[Process[F,O]]), stack.tail)
            case None => halt
          }
        case emt: Emit[O@unchecked]                              => Append(emt, stack)
        case app: Append[F@unchecked, O@unchecked]               => Append(app.head, app.tail fast_++ stack)
        case awt: Await[F@unchecked, Any@unchecked, O@unchecked] => Append(awt, stack)
        case hlt@Halt(rsn)                                       =>
          stack.headOption match {
            case Some(f) => apply(Try(f(Some(rsn)).run.asInstanceOf[Process[F,O]]), stack.tail)
            case None => Halt(rsn)
          }


      }
    }

  }


  ///////////////////////////////////////////////////////////////////////////////////////
  //
  // CONSTRUCTORS
  //
  //////////////////////////////////////////////////////////////////////////////////////

  /** alias for emitAll **/
  def apply[O](o: O*): Process[Nothing, O] = emitAll(o)

  /**
   * stack-safe constructor for Await
   * allowing to pass `req` as request
   * and function to produce next state
   *
   *
   */
  def await[F[_], A, O](req: F[A])(rcv: A => Process[F, O]): Process[F, O] =
    awaitOr[F, A, O](req)(fail)(rcv)

  /**
   * Constructs an await, that allows to specify processes that will be run when
   * request wil either terminate with `End` or `Kill` (continue) or with error (onError).
   *
   * if you don't need to specify `continue` or `onError` use `await`
   */
  def awaitOr[F[_], A, O](req: F[A])(
    fb: Throwable => Process[F, O]
    )(rcv: A => Process[F, O]): Process[F, O] = {
    Await(req, (r: Throwable \/ A) => Trampoline.delay(r.fold(
      rsn => {val that = Try(fb(rsn))
      that
      }
      , a => rcv(a)
    )))
  }

  /** helper to construct await for Process1 **/
  def await1[I]: Process1[I, I] =
    await(Get[I])(emit)

  /** like await1, but allows to define continue in case the process terminated with exception **/
  def await1Or[I](fb: => Process1[I, I]): Process1[I, I] =
    awaitOr(Get[I])((_: Throwable) => fb)(emit)


  /** heleper to construct for await on Both sides. Can be used in `Wye` **/
  def awaitBoth[I, I2]: Wye[I, I2, ReceiveY[I, I2]] =
    await(Both[I, I2])(emit)

  /** helper construct for await on Left side. Can be used in `Tee` and `Wye` **/
  def awaitL[I]: Tee[I, Any, I] =
    await(L[I])(emit)

  /** helper construct for await on Right side. Can be used in `Tee` and `Wye` **/
  def awaitR[I2]: Tee[Any, I2, I2] =
    await(R[I2])(emit)

  /** constructor to emit single `O` **/
  def emit[O](o: O): Process[Nothing, O] = Emit(Vector(o))

  /** constructor to emit sequence of `O` **/
  def emitAll[O](os: Seq[O]): Process[Nothing, O] = os match {
    case Seq() => empty
    case _     => Emit(os)
  }


  /** constructor to emit sequence of `O` having `tail` as next state **/
  @deprecated("Use please emitAll(h) ++ tail instead", "0.5.0")
  def emitSeq[F[_], O](h: Seq[O], t: Process[F, O] = halt): Process[F, O] = t match {
    case `halt` | Emit(Seq()) => emitAll(h)
    case _                    => emitAll(h) ++ t
  }

  /** The `Process` which emits no values and halts immediately with the given exception. **/
  def fail(rsn: Throwable): Process[Nothing, Nothing] = Halt(rsn)

  /** The `Process` which emits no values and signals normal continuation **/
  val halt: Step[Nothing, Nothing] = Halt(End)

  val empty: Emit[Nothing] = Emit(Nil)

  /**
   * awaits receive of `I` in process1, and attaches continue in case await evaluation
   * terminated with `End` indicated source being exhausted (`continue`)
   * or arbitrary exception indicating process was terminated abnormally (`cleanup`)
   *
   * If you don't need to handle the `continue` or `cleanup` case, use `await1.flatMap`
   */
  def receive1[I, O](
    rcv: I => Process1[I, O]
    , fallback: => Process1[I, O] = halt
    , cleanup: => Process1[I, O] = halt
    ): Process1[I, O] =
    awaitOr(Get[I])({
      case End => fallback.causedBy(End)
      case rsn =>  var cln = cleanup.causedBy(rsn)
        cln
    })(rcv)

  /**
   * Curried syntax alias for receive1
   * Note that `fb` is attached to both, fallback and cleanup
   */
  def receive1Or[I, O](fb: => Process1[I, O])(rcv: I => Process1[I, O]): Process1[I, O] =
    receive1[I, O](rcv, fb, fb)

  /**
   * Awaits to receive input from Both sides,
   * than if that request terminates with `End` or is terminated abonromally
   * runs the supplied continue or cleanup.
   * Otherwise `rcv` is run to produce next state.
   *
   * If you don't need `continue` use rather `awaitBoth.flatMap`
   */
  def receiveBoth[I, I2, O](
    rcv: ReceiveY[I, I2] => Wye[I, I2, O]
    , fallback: => Wye[I, I2, O] = halt
    , cleanup: => Wye[I, I2, O] = halt
    ): Wye[I, I2, O] =
    awaitOr[Env[I, I2]#Y, ReceiveY[I, I2], O](Both)({
      case End => fallback.causedBy(End)
      case rsn => cleanup.causedBy(rsn)
    })(rcv)

  /**
   * Awaits to receive input from Left side,
   * than if that request terminates with `End` or is terminated abnormally
   * runs the supplied `continue` or `cleanup`.
   * Otherwise `rcv` is run to produce next state.
   *
   * If  you don't need `continue` or `cleanup` use rather `awaitL.flatMap`
   */
  def receiveL[I, I2, O](
    rcv: I => Tee[I, I2, O]
    , fallback: => Tee[I, I2, O] = halt
    , cleanup: => Tee[I, I2, O] = halt
    ): Tee[I, I2, O] =
    awaitOr[Env[I, I2]#T, I, O](L)({
      case End => fallback.causedBy(End)
      case rsn => cleanup.causedBy(rsn)
    })(rcv)

  /**
   * Awaits to receive input from Right side,
   * than if that request terminates with `End` or is terminated abnormally
   * runs the supplied continue.
   * Otherwise `rcv` is run to produce next state.
   *
   * If  you don't need `continue` or `cleanup` use rather `awaitR.flatMap`
   */
  def receiveR[I, I2, O](
    rcv: I2 => Tee[I, I2, O]
    , fallback: => Tee[I, I2, O] = halt
    , cleanup: => Tee[I, I2, O] = halt
    ): Tee[I, I2, O] =
    awaitOr[Env[I, I2]#T, I2, O](R)({
      case End => fallback.causedBy(End)
      case rsn => cleanup.causedBy(End)
    })(rcv)

  /** syntax sugar for receiveBoth  **/
  def receiveBothOr[I, I2, O](fb: => Wye[I, I2, O])(rcv: ReceiveY[I, I2] => Wye[I, I2, O]): Wye[I, I2, O] =
    receiveBoth(rcv, fb, fb)

  /** syntax sugar for receiveL **/
  def receiveLOr[I, I2, O](fb: => Tee[I, I2, O])(rcvL: I => Tee[I, I2, O]): Tee[I, I2, O] =
    receiveL(rcvL, fb, fb)

  /** syntax sugar for receiveR **/
  def receiveROr[I, I2, O](fb: => Tee[I, I2, O])(rcvR: I2 => Tee[I, I2, O]): Tee[I, I2, O] =
    receiveR(rcvR, fb, fb)

  ///////////////////////////////////////////////////////////////////////////////////////
  //
  // DECONSTRUCTORS
  //
  //////////////////////////////////////////////////////////////////////////////////////


  object AwaitP1 {
    /** deconstruct for `Await` directive of `Process1` **/
    def unapply[I, O](self: Process1[I, O]): Option[I => Process1[I, O]] = self match {
      case Await(_, rcv) => Some((i: I) => Try(rcv(right(i)).run))
      case _             => None
    }

    /** Like `AwaitP1.unapply` only allows for extracting the continue case as well **/
    object withFb {
      def unapply[I, I2, O](self: Process1[I, O]):
      Option[(Throwable \/ I => Process1[I, O])] = self match {
        case Await(_, rcv) => Some((r: Throwable \/ I) => Try(rcv(r).run))
        case _             => None
      }
    }

    /** Like `AwaitP1.unapply` only allows for extracting the continue case as well on Trampoline **/
    object withFbT {
      def unapply[I, I2, O](self: Process1[I, O]):
      Option[(Throwable \/ I => Trampoline[Process1[I, O]])] = self match {
        case Await(_, rcv) => Some(rcv)
        case _             => None
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  //
  // CONSUTRUCTORS -> Helpers
  //
  //////////////////////////////////////////////////////////////////////////////////////

  /** `Writer` based version of `await1`. */
  def await1W[A]: Writer1[Nothing,A,A] =
    liftW(Process.await1[A])

  /** `Writer` based version of `awaitL`. */
  def awaitLW[I]: TeeW[Nothing,I,Any,I] =
    liftW(Process.awaitL[I])

  /** `Writer` based version of `awaitR`. */
  def awaitRW[I2]: TeeW[Nothing,Any,I2,I2] =
    liftW(Process.awaitR[I2])

  /** `Writer` based version of `awaitBoth`. */
  def awaitBothW[I,I2]: WyeW[Nothing,I,I2,ReceiveY[I,I2]] =
    liftW(Process.awaitBoth[I,I2])

  /**
   * Discrete process that every `d` emits elapsed duration
   * since the start time of stream consumption.
   *
   * For example: `awakeEvery(5 seconds)` will
   * return (approximately) `5s, 10s, 20s`, and will lie dormant
   * between emitted values.
   *
   * By default, this uses a shared `ScheduledExecutorService`
   * for the timed events, and runs the consumer using the `pool` `Strategy`,
   * to allow for the process to decide whether result shall be run on
   * different thread pool, or with `Strategy.Sequential` on the
   * same thread pool as the scheduler.
   *
   * @param d           Duration between emits of the resulting process
   * @param S           Strategy to run the process
   * @param scheduler   Scheduler used to schedule tasks
   */
  def awakeEvery(d: Duration)(
    implicit S: Strategy,
    scheduler: ScheduledExecutorService): Process[Task, Duration] = {
      def metronomeAndSignal:(()=>Unit,async.mutable.Signal[Duration]) = {
        val signal = async.signal[Duration](S)
        val t0 = Duration(System.nanoTime, NANOSECONDS)

        val metronome = scheduler.scheduleAtFixedRate(
          new Runnable { def run = {
            val d = Duration(System.nanoTime, NANOSECONDS) - t0
            signal.set(d).run
          }},
          d.toNanos,
          d.toNanos,
          NANOSECONDS
        )
        (()=>metronome.cancel(false), signal)
      }

      await(Task.delay(metronomeAndSignal))({
        case (cm, signal) =>  signal.discrete onComplete eval_(signal.close.map(_=>cm()))
      })
  }


  /**
   * The infinite `Process`, always emits `a`.
   * If for performance reasons it is good to emit `a` in chunks,
   * specifiy size of chunk by `chunkSize` parameter
   */
  def constant[A](a: A, chunkSize: Int = 1): Process[Nothing,A] = {
    lazy val go: Process[Nothing,A] =
      if (chunkSize.max(1) == 1) emit(a) fby go
      else emitAll(List.fill(chunkSize)(a)) fby go
    go
  }

  /**
   * A continuous stream of the elapsed time, computed using `System.nanoTime`.
   * Note that the actual granularity of these elapsed times depends on the OS, for instance
   * the OS may only update the current time every ten milliseconds or so.
   */
  def duration: Process[Task, Duration] = suspend {
    val t0 = System.nanoTime
    repeatEval { Task.delay { Duration(System.nanoTime - t0, NANOSECONDS) }}
  }

  /** A `Writer` which emits one value to the output. */
  def emitO[O](o: O): Process[Nothing, Nothing \/ O] =
    liftW(Process.emit(o))

  /** `Process.emitRange(0,5) == Process(0,1,2,3,4).` */
  def emitRange(start: Int, stopExclusive: Int): Process[Nothing,Int] =
    emitAll(start until stopExclusive)

  /** A `Writer` which writes the given value. */
  def emitW[W](s: W): Process[Nothing, W \/ Nothing] =
    Process.emit(left(s))


  /**
   * A 'continuous' stream which is true after `d, 2d, 3d...` elapsed duration,
   * and false otherwise.
   * If you'd like a 'discrete' stream that will actually block until `d` has elapsed,
   * use `awakeEvery` instead.
   */
  def every(d: Duration): Process[Task, Boolean] = {
    def go(lastSpikeNanos: Long): Process[Task, Boolean] =
      suspend {
        val now = System.nanoTime
        if ((now - lastSpikeNanos) > d.toNanos) emit(true) ++ go(now)
        else emit(false) ++ go(lastSpikeNanos)
      }
    go(0)
  }

  /** A `Process` which emits `n` repetitions of `a`. */
  def fill[A](n: Int)(a: A, chunkSize: Int = 1): Process[Nothing,A] = {
    val chunkN = chunkSize max 1
    val chunk = emitAll(List.fill(chunkN)(a)) // we can reuse this for each step
    def go(m: Int): Process[Nothing,A] =
      if (m >= chunkN) chunk ++ go(m - chunkN)
      else if (m <= 0) halt
      else emitAll(List.fill(m)(a))
    go(n max 0)
  }

  /**
   * Produce a continuous stream from a discrete stream by using the
   * Produce a continuous stream from a discrete stream by using the
   * most recent value.
   */
  def forwardFill[A](p: Process[Task,A])(implicit S: Strategy): Process[Task,A] =
    async.toSignal(p).continuous

  /**
   * An infinite `Process` that repeatedly applies a given function
   * to a start value.
   */
  def iterate[A](start: A)(f: A => A): Process[Nothing,A] = {
    def go(a: A): Process[Nothing,A] = emit(a) ++ go(f(a))
    go(start)
  }

  /** Promote a `Process` to a `Writer` that writes nothing. */
  def liftW[F[_],A](p: Process[F,A]): Writer[F,Nothing,A] =
    p.map(right)

  /**
   * Promote a `Process` to a `Writer` that writes and outputs
   * all values of `p`.
   */
  def logged[F[_],A](p: Process[F,A]): Writer[F,A,A] =
    p.flatMap(a => emitAll(Vector(left(a), right(a))))

  /** Lazily produce the range `[start, stopExclusive)`. */
  def range(start: Int, stopExclusive: Int, by: Int = 1): Process[Nothing,Int] =
    unfold(start)(i => if (i < stopExclusive) Some((i,i+by)) else None)

  /**
   * Lazily produce a sequence of nonoverlapping ranges, where each range
   * contains `size` integers, assuming the upper bound is exclusive.
   * Example: `ranges(0, 1000, 10)` results in the pairs
   * `(0, 10), (10, 20), (20, 30) ... (990, 1000)`
   *
   * Note: The last emitted range may be truncated at `stopExclusive`. For
   * instance, `ranges(0,5,4)` results in `(0,4), (4,5)`.
   *
   * @throws IllegalArgumentException if `size` <= 0
   */
  def ranges(start: Int, stopExclusive: Int, size: Int): Process[Nothing, (Int,Int)] = {
    require(size > 0, "size must be > 0, was: " + size)
    unfold(start)(lower =>
      if (lower < stopExclusive)
        Some((lower -> ((lower+size) min stopExclusive), lower+size))
      else
        None)
  }

  /**
   * A single-element `Process` that waits for the duration `d`
   * before emitting its value. This uses a shared
   * `ScheduledThreadPoolExecutor` to signal duration and
   * avoid blocking on thread. After the signal,
   * the execution continues with `S` strategy
   */
  def sleep(d: FiniteDuration)(
    implicit S: Strategy
    , schedulerPool: ScheduledExecutorService
    ): Process[Task,Nothing] =
    awakeEvery(d).once.drain

  /**
   * Delay running `p` until `awaken` becomes true for the first time.
   * The `awaken` process may be discrete.
   */
  def sleepUntil[F[_],A](awaken: Process[F,Boolean])(p: Process[F,A]): Process[F,A] =
    awaken.dropWhile(!_).once.flatMap(b => if (b) p else halt)

  /**
   * A supply of `Long` values, starting with `initial`.
   * Each read is guaranteed to retun a value which is unique
   * across all threads reading from this `supply`.
   */
  def supply(initial: Long): Process[Task, Long] = {
    import java.util.concurrent.atomic.AtomicLong
    val l = new AtomicLong(initial)
    repeatEval { Task.delay { l.getAndIncrement }}
  }

  /** A `Writer` which writes the given value; alias for `emitW`. */
  def tell[S](s: S): Process[Nothing, S \/ Nothing] =
    emitW(s)

  /**
   * Convert a `Process` to a `Task` which can be run repeatedly to generate
   * the elements of the `Process`.
   *
   * Note that evaluation of this task will end with Exception `End` or `Continute`
   * even when the evaluation of the process was succesfull.
   */
  def toTask[A](p: Process[Task,A]): Task[A] = {
    var cur = p
    def go: Task[A] =
      cur.step match {
        case s@Step(Emit(os)) =>
          if (os.isEmpty) {
            cur = s.continue
            go
          } else {
            cur = emitAll(os.tail) onHalt s.next
            Task.now(os.head)
          }
        case s@Step(Await(rq,rcv)) =>
          rq.attempt.flatMap { r =>
              cur = Try(rcv(r).run) onHalt s.next
              go
          }
        case Halt(rsn) => Task.fail(rsn)
      }
    Task.delay(go).flatMap(a => a)
  }

  /** Produce a (potentially infinite) source from an unfold. */
  def unfold[S, A](s0: S)(f: S => Option[(A, S)]): Process[Nothing,A] = suspend {
    f(s0) match {
      case Some(ht) => emit(ht._1) ++ unfold(ht._2)(f)
      case None => halt
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //
  // INSTANCES
  //
  /////////////////////////////////////////////////////////////////////////////////////

  /**
   * Special exception that indicating normal termination.
   * Note that this will never appear on left side of Await's receive
   */
  object Continue extends Exception {
    override def fillInStackTrace = this
  }

  /**
   * Special exception indicating End of Input. Throwing this
   * exception results in control switching to process, that is appended via ++, or cleanup
   */
  object End extends Exception {
    override def fillInStackTrace = this
  }

  /**
   * Special exception indicating downstream termination.
   * An `Await` should respond to a `Kill` by performing
   * necessary cleanup actions, then halting with `End`
   */
  object Kill extends Exception {
    override def fillInStackTrace = this
  }


  /**
   * Wrapper for Exception that was caused by other Exception during the
   * Execution of the Process
   */
  case class CausedBy(e: Throwable, cause: Throwable) extends Exception(cause) {
    override def toString = s"$e caused by: $cause"
    override def getMessage: String = toString
  }


  implicit def processInstance[F[_]]: MonadPlus[({type f[x] = Process[F, x]})#f] =
    new MonadPlus[({type f[x] = Process[F, x]})#f] {
      def empty[A] = halt
      def plus[A](a: Process[F, A], b: => Process[F, A]): Process[F, A] =
        a ++ b
      def point[A](a: => A): Process[F, A] = emit(a)
      def bind[A, B](a: Process[F, A])(f: A => Process[F, B]): Process[F, B] =
        a flatMap f
    }




  //////////////////////////////////////////////////////////////////////////////////////
  //
  // ENV, Tee, Wye et All
  //
  /////////////////////////////////////////////////////////////////////////////////////


  case class Env[-I, -I2]() {
    sealed trait Y[-X] {
      def tag: Int
      def fold[R](l: => R, r: => R, both: => R): R
    }
    sealed trait T[-X] extends Y[X]
    sealed trait Is[-X] extends T[X]
    case object Left extends Is[I] {
      def tag = 0
      def fold[R](l: => R, r: => R, both: => R): R = l
    }
    case object Right extends T[I2] {
      def tag = 1
      def fold[R](l: => R, r: => R, both: => R): R = r
    }
    case object Both extends Y[ReceiveY[I, I2]] {
      def tag = 2
      def fold[R](l: => R, r: => R, both: => R): R = both
    }
  }


  private val Left_  = Env[Any, Any]().Left
  private val Right_ = Env[Any, Any]().Right
  private val Both_  = Env[Any, Any]().Both

  def Get[I]: Env[I, Any]#Is[I] = Left_
  def L[I]: Env[I, Any]#Is[I] = Left_
  def R[I2]: Env[Any, I2]#T[I2] = Right_
  def Both[I, I2]: Env[I, I2]#Y[ReceiveY[I, I2]] = Both_

  //////////////////////////////////////////////////////////////////////////////////////
  //
  // SYNTAX
  //
  /////////////////////////////////////////////////////////////////////////////////////

  /**
   * Adds syntax for Channel
   */
  implicit class ChannelSyntax[F[_],I,O](self: Channel[F,I,O]) {
    /** Transform the input of this `Channel`. */
    def contramap[I0](f: I0 => I): Channel[F,I0,O] =
      self.map(f andThen _)

    /** Transform the output of this `Channel` */
    def mapOut[O2](f: O => O2)(implicit F: Functor[F]): Channel[F,I,O2] =
      self.map(_ andThen F.lift(f))
  }


  implicit class ProcessSyntax[F[_],O](val self:Process[F,O]) extends AnyVal {
    /** Feed this `Process` through the given effectful `Channel`. */
    def through[F2[x]>:F[x],O2](f: Channel[F2,O,O2]): Process[F2,O2] =
     self.zipWith(f)((o,f) => f(o)).eval

    /**
     * Feed this `Process` through the given effectful `Channel`, signaling
     * termination to `f` via `None`. Useful to allow `f` to flush any
     * buffered values to the output when it detects termination, see
     * [[scalaz.stream.io.bufferedChannel]] combinator.
     */
    def throughOption[F2[x]>:F[x],O2](f: Channel[F2,Option[O],O2]): Process[F2,O2] =
      self.terminated.through(f)

    /** Attaches `Sink` to this  `Process`  */
    def to[F2[x]>:F[x]](f: Sink[F2,O]): Process[F2,Unit] =
      through(f)

    /** Attach a `Sink` to the output of this `Process` but echo the original. */
    def observe[F2[x]>:F[x]](f: Sink[F2,O]): Process[F2,O] =
      self.zipWith(f)((o,f) => (o,f(o))).flatMap { case (orig,action) => emit(action).eval.drain ++ emit(orig) }



  }



  /**
   * Provides infix syntax for `eval: Process[F,F[O]] => Process[F,O]`
   */
  implicit class EvalProcess[F[_], O](val self: Process[F, F[O]]) extends AnyVal {

    /**
     * Evaluate the stream of `F` actions produced by this `Process`.
     * This sequences `F` actions strictly--the first `F` action will
     * be evaluated before work begins on producing the next `F`
     * action. To allow for concurrent evaluation, use `sequence`
     * or `gather`.
     *
     * If evaluation of `F` results to `End` the evaluation continues by next `F`.
     */
    def eval: Process[F, O] = {
      self.step match {
        case s@Step(Emit(fos))       =>
          fos.foldLeft(emitAll(Seq.empty[O]): Process[F, O])(
            (p, n) => p fby Process.eval(n)
          ) onHalt(rsn => s.next(rsn).eval)

        case s@Step(awt@Await(_, _)) =>
          awt.extend(_.eval) onHalt (rsn => s.next(rsn).eval)

        case hlt@Halt(rsn)                   => hlt
      }
    }

    /**
     * Read chunks of `bufSize` from input, then use `Nondeterminism.gatherUnordered`
     * to run all these actions to completion.
     */
    def gather(bufSize: Int)(implicit F: Nondeterminism[F]): Process[F,O] =
      self.pipe(process1.chunk(bufSize)).map(F.gatherUnordered).eval.flatMap(emitAll)
  }


  /**
   * This class provides infix syntax specific to `Process0`.
   */
  implicit class Process0Syntax[O](self: Process[Env[Any,Any]#Is,O]) {
    def toIndexedSeq: IndexedSeq[O] = {
      @tailrec
      def go(cur: Process[Any,O], acc: Vector[O]): IndexedSeq[O] = {
        Util.debug(s"ISQ $cur | acc: $acc")
        cur.step match {
          case s@Step(Emit(os)) => go(s.continue, acc fast_++ os)
          case s@Step(awt) => go(s.continue,acc)
          case Halt(End) => acc
          case Halt(rsn) => throw rsn
        }
      }
      go(self, Vector())
    }
    def toList: List[O] = toIndexedSeq.toList
    def toSeq: Seq[O] = toIndexedSeq
    def toMap[K, V](implicit isKV: O <:< (K, V)): Map[K, V] = toIndexedSeq.toMap(isKV)
    def toSortedMap[K, V](implicit isKV: O <:< (K, V), ord: Ordering[K]): SortedMap[K, V] =
      SortedMap(toIndexedSeq.asInstanceOf[Seq[(K, V)]]: _*)
    def toStream: Stream[O] = toIndexedSeq.toStream
    def toSource: Process[Task, O] = self.step match {
      case s@Step(emt@Emit(os)) => emt onHalt(rsn => s.next(rsn).toSource)
      case s@Step(awt)            => s.continue.toSource
      case hlt@Halt(rsn)             => hlt
    }

  }


  implicit class LiftIOSyntax[O](p: Process[Nothing,O]) {
    def liftIO: Process[Task,O] = p
  }

  /** Syntax for Sink, that is specialized for Task */
  implicit class SinkTaskSyntax[I](val self: Sink[Task,I]) extends AnyVal {
    /** converts sink to channel, that will perform the side effect and echo its input **/
    def toChannel:Channel[Task,I,I] = self.map(f => (i:I) => f(i).map(_ =>i))

    /** converts sint to sink that first pipes received `I0` to supplied p1 **/
    def pipeIn[I0](p1: Process1[I0, I]): Sink[Task, I0] = {
      import scalaz.Scalaz._
      // Note: Function `f` from sink `self` may be used for more than 1 element emitted by `p1`.
      @volatile var cur: Process1[I0, I] = p1
      self.map { (f: I => Task[Unit]) =>
        (i0: I0) =>
          val (piped, next) = process1.feed1(i0)(cur).unemit
          cur = next
          piped.toList.traverse_(f)
      }
    }

  }


  /**
   * This class provides infix syntax specific to `Process1`.
   */
  implicit class Process1Syntax[I,O](self: Process1[I,O]) {

    /** Apply this `Process` to an `Iterable`. */
    def apply(input: Iterable[I]): IndexedSeq[O] =
      Process(input.toSeq: _*).pipe(self.bufferAll).disconnect.unemit._1.toIndexedSeq

    /**
     * Transform `self` to operate on the left hand side of an `\/`, passing
     * through any values it receives on the right. Note that this halts
     * whenever `self` halts.
     */
    def liftL[I2]: Process1[I \/ I2, O \/ I2] =
      process1.liftL(self)

    /**
     * Transform `self` to operate on the right hand side of an `\/`, passing
     * through any values it receives on the left. Note that this halts
     * whenever `self` halts.
     */
    def liftR[I0]: Process1[I0 \/ I, I0 \/ O] =
      process1.liftR(self)

    /**
     * Feed a single input to this `Process1`.
     */
    def feed1(i: I): Process1[I,O] =
      process1.feed1(i)(self)
  }


  /**
   * Syntax for processes that have its effects wrapped in Task
   * @param self
   * @tparam O
   */
  implicit class SourceSyntax[O](val self: Process[Task, O]) extends WyeOps[O] {

    /**
     * Produce a continuous stream from a discrete stream by using the
     * most recent value.
     */
    def forwardFill(implicit S: Strategy): Process[Task,O] =
      async.toSignal(self).continuous

    /** Infix syntax for `Process.toTask`. */
    def toTask: Task[O] = Process.toTask(self)

    /**
     * Asynchronous execution of this Process. Note that this method is not resource safe unless
     * callback is called with _left_ side completed. In that case it is guaranteed that all cleanups
     * has been successfully completed.
     * User of this method is responsible for any cleanup actions to be performed by running the
     * next Process obtained on right side of callback.
     *
     * This method returns a function, that when applied, causes the running computation to be interrupted.
     * That is useful of process contains any asynchronous code, that may be left with incomplete callbacks.
     * If the evaluation of the process is interrupted, then the interruption is only active if the callback
     * was not completed before, otherwise interruption is no-op.
     *
     * There is chance, that cleanup code of intermediate `Await` will get called twice on interrupt, but
     * always at least once. User code shall therefore wrap resource cleanup in safe pattern like
     * {{{ if (res.isOpen) res.close }}}.
     *
     * Note that if some resources are open asynchronously, the cleanup of these resources may be run
     * asynchronously as well, essentially AFTER callback was called on different thread.
     *
     *
     * @param cb  result of the asynchronous evaluation of the process. Note that, the callback is never called
     *            on the right side, if the sequence is empty.
     * @param S  Strategy to use when evaluating the process. Note that `Strategy.Sequential` may cause SOE.
     * @return   Function to interrupt the evalation
     */
    protected[stream] final def runAsync(
      cb: Throwable \/ (Seq[O], Option[Throwable] => Process[Task, O]) => Unit
      )(implicit S: Strategy): (Throwable) => Unit = {

      sealed trait M
      case class AwaitDone(res: Throwable \/ Any, awt: Await[Task, Any, O], next: Option[Throwable] => Process[Task, O]) extends M
      case class Interrupt(rsn: Throwable) extends M

      //forward referenced actor here
      var a: Actor[M] = null

      // Set when the executin has been terminated with reason for termination
      var completed: Option[Throwable] = None

      // contains reference that eventually builds
      // a cleanup when the last await was interrupted
      // this is consulted only, if await was interrupted
      // volatile marked because of the first usage outside of actor
      @volatile var cleanup: (Throwable => Process[Task, O]) = fail _

      // runs single step of process.
      // completes with callback if process is `Emit` or `Halt`.
      // or asynchronously executes the Await and send result to actor `a`
      // It returns on left side reason with which this process terminated,
      // or on right side the cleanup code to be run when interrupted.
      @tailrec
      def runStep(p: Process[Task, O]): Option[Throwable] \/ ( Throwable => Process[Task, O]) = {
        val step = p.step
        step match {
          case s@Step(Emit(Seq()))         => runStep(s.continue)
          case s@Step(Emit(h))             => S(cb(right((h, s.next)))); left(None)
          case s@Step(awt@Await(req, rcv)) =>
            req.runAsync(r => a ! AwaitDone(r, awt, s.next))
            right((t:  Throwable) =>    Try(rcv(left(t)).run) onHalt s.next)
          case Halt(rsn)                 => S(cb(left(rsn))); left(Some(rsn))
        }
      }


      a = new Actor[M]({
        case AwaitDone(r, awt, next) if completed.isEmpty =>
          runStep(Try(awt.rcv(r).run) onHalt next).fold(
            rsn => completed = rsn
            , cln => cleanup = cln
          )

        // on interrupt we just run any cleanup code we have memo-ed
        // from last `Await`
        case Interrupt(rsn) if completed.isEmpty =>
          completed = Some(rsn)
          cleanup(Kill).run.runAsync(_.fold(
            rsn0 => cb(left(CausedBy(rsn0,Kill)))
            , _ => cb(left(rsn))
          ))

        // this indicates last await was interrupted.
        // In case the request was successful and only then
        // we have to get next state of the process and assure
        // any cleanup will be run.
        // note this won't consult any cleanup contained
        // in `next` or `rcv` on left side
        // as this was already run on `Interrupt`
        case AwaitDone(r, awt, _) =>
          r.foreach { a =>
            Try(awt.rcv(right(a)).run)
            .kill
            .run.runAsync(_ => ())
          }

        // Interrupt after we have been completed this is no-op
        case Interrupt(_) => ()

      })(S)

      runStep(self).fold(
        rsn => (t: Throwable) => ()
        , cln => {
          cleanup = cln
          (t: Throwable) => a ! Interrupt(t)
        }
      )
    }
  }


  /**
   * This class provides infix syntax specific to `Tee`. We put these here
   * rather than trying to cram them into `Process` itself using implicit
   * equality witnesses. This doesn't work out so well due to variance
   * issues.
   */
  implicit class TeeSyntax[I,I2,O](self: Tee[I,I2,O]) {

    /** Transform the left input to a `Tee`. */
    def contramapL[I0](f: I0 => I): Tee[I,I2,O] =
      self.contramapL_(f).asInstanceOf[Tee[I,I2,O]]

    /** Transform the right input to a `Tee`. */
    def contramapR[I3](f: I3 => I2): Tee[I,I3,O] =
      self.contramapR_(f).asInstanceOf[Tee[I,I3,O]]
  }

  /**
   * Infix syntax for working with `Writer[F,W,O]`. We call
   * the `W` parameter the 'write' side of the `Writer` and
   * `O` the 'output' side. Many method in this class end
   * with either `W` or `O`, depending on what side they
   * operate on.
   */
  implicit class WriterSyntax[F[_],W,O](self: Writer[F,W,O]) {

    /** Transform the write side of this `Writer`. */
    def flatMapW[F2[x]>:F[x],W2,O2>:O](f: W => Writer[F2,W2,O2]): Writer[F2,W2,O2] =
      self.flatMap(_.fold(f, a => emit(right(a))))

    /** Remove the write side of this `Writer`. */
    def stripW: Process[F,O] =
      self.flatMap(_.fold(_ => halt, emit))

    /** Map over the write side of this `Writer`. */
    def mapW[W2](f: W => W2): Writer[F,W2,O] =
      self.map(_.leftMap(f))

    /**
     * Observe the write side of this `Writer` using the
     * given `Sink`, keeping it available for subsequent
     * processing. Also see `drainW`.
     */
    def observeW(snk: Sink[F,W]): Writer[F,W,O] =
      self.zipWith(snk)((a,f) =>
        a.fold(
          (s: W) => eval_ { f(s) } ++ Process.emit(left(s)),
          (a: O) => Process.emit(right(a))
        )
      ).flatMap(identity)

    /**
     * Observe the write side of this `Writer` using the
     * given `Sink`, then discard it. Also see `observeW`.
     */
    def drainW(snk: Sink[F,W]): Process[F,O] =
      observeW(snk).stripW

    /** Map over the output side of this `Writer`. */
    def mapO[B](f: O => B): Writer[F,W,B] =
      self.map(_.map(f))

    def flatMapO[F2[x]>:F[x],W2>:W,B](f: O => Writer[F2,W2,B]): Writer[F2,W2,B] =
      self.flatMap(_.fold(s => emit(left(s)), f))

    def stripO: Process[F,W] =
      self.flatMap(_.fold(emit, _ => halt))

    def pipeO[B](f: Process1[O,B]): Writer[F,W,B] =
      self.pipe(process1.liftR(f))
  }

  /**
   * This class provides infix syntax specific to `Wye`. We put these here
   * rather than trying to cram them into `Process` itself using implicit
   * equality witnesses. This doesn't work out so well due to variance
   * issues.
   */
  implicit class WyeSyntax[I,I2,O](self: Wye[I,I2,O]) {

    /**
     * Apply a `Wye` to two `Iterable` inputs.
     */
    def apply(input: Iterable[I], input2: Iterable[I2]): IndexedSeq[O] = {
      // this is probably rather slow
      val src1 = Process.emitAll(input.toSeq).toSource
      val src2 = Process.emitAll(input2.toSeq).toSource
      src1.wye(src2)(self).runLog.run
    }

    /**
     * Transform the left input of the given `Wye` using a `Process1`.
     */
    def attachL[I0](f: Process1[I0,I]): Wye[I0, I2, O] =
      scalaz.stream.wye.attachL(f)(self)

    /**
     * Transform the right input of the given `Wye` using a `Process1`.
     */
    def attachR[I1](f: Process1[I1,I2]): Wye[I, I1, O] =
      scalaz.stream.wye.attachR(f)(self)

    /** Transform the left input to a `Wye`. */
    def contramapL[I0](f: I0 => I): Wye[I0, I2, O] =
      contramapL_(f)

    /** Transform the right input to a `Wye`. */
    def contramapR[I3](f: I3 => I2): Wye[I, I3, O] =
      contramapR_(f)

    private[stream] def contramapL_[I0](f: I0 => I): Wye[I0, I2, O] =
      self.attachL(process1.lift(f))

    private[stream] def contramapR_[I3](f: I3 => I2): Wye[I, I3, O] =
      self.attachR(process1.lift(f))

    /**
     * Converting requests for the left input into normal termination.
     * Note that `Both` requests are rewritten to fetch from the only input.
     */
    def detach1L: Wye[I,I2,O] = scalaz.stream.wye.detach1L(self)

    /**
     * Converting requests for the left input into normal termination.
     * Note that `Both` requests are rewritten to fetch from the only input.
     */
    def detach1R: Wye[I,I2,O] = scalaz.stream.wye.detach1R(self)
  }


  //////////////////////////////////////////////////////////////////////////////////////
  //
  // SYNTAX Functions
  //
  /////////////////////////////////////////////////////////////////////////////////////

  /**
   * Evaluate an arbitrary effect in a `Process`. The resulting
   * `Process` emits a single value. To evaluate repeatedly, use
   * `repeateEval(t)` or equivalently `eval(t).repeat`.
   */
  def eval[F[_], O](f: F[O]): Process[F, O] =
    await(f)(emit)

  /**
   * Evaluate an arbitrary effect once, purely for its effects,
   * ignoring its return value. This `Process` emits no values.
   */
  def eval_[F[_], O](f: F[O]): Process[F, Nothing] =
    await(f)(_ => halt)

  /** Prefix syntax for `p.repeat`. */
  def repeat[F[_], O](p: Process[F, O]): Process[F, O] = p.repeat


  /**
   * Evaluate an arbitrary effect in a `Process`. The resulting `Process` will emit values
   * until evaluation of `f` signals termination with `End` or an error occurs.
   */
  def repeatEval[F[_], O](f: F[O]): Process[F, O] =
    await(f)(o => emit(o) fby repeatEval(f))

  /**
   * Produce `p` lazily, guarded by a single `Append`. Useful if
   * producing the process involves allocation of some mutable
   * resource we want to ensure is accessed in a single-threaded way.
   *
   * Note that this implementation assures that:
   * {{{
   *    suspend(p).kill === suspend(p.kill)
   *    suspend(p).kill === p.kill
   *
   *    suspend(p).repeat === suspend(p.repeat)
   *    suspend(p).repeat ===  p.repeat
   *
   *    suspend(p).eval === suspend(p.eval)
   *    suspend(p).eval === p.eval
   *
   *    terminate ++ suspend(p) === halt ++ suspend(p)
   *    halt ++ suspend(p) === halt ++ p
   *    terminate ++ suspend(p) === terminate ++ p
   * }}}
   *
   */
  def suspend[F[_], O](p: => Process[F, O]): Process[F, O] =
    Append(empty,Vector((r:Option[Throwable]) =>
      r.fold(Trampoline.done(p))({
        case Kill => Trampoline.done(p.kill) // to make sure suspend(p).kill == suspend(p.kill), only lazily
        case rsn => Trampoline.done(p) // End or any other reason can't get there, only None or Some(Kill) when process is killed
      })
    ))

}
