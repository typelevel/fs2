package scalaz.stream2


import scala.Ordering
import scala.annotation.tailrec
import scala.collection.SortedMap
import scalaz.\/._
import scalaz.concurrent.{Actor, Strategy, Task}
import scalaz.{\/, Catchable, Monoid, Monad, ~>}
import scala.concurrent.duration._
import java.util.concurrent.{TimeUnit, ScheduledExecutorService, ExecutorService}

sealed trait Process[+F[_], +O]
  extends Process1Ops[F,O]
          with TeeOps[F,O] {

  import Process._
  import Util._

  /**
   * Generate a `Process` dynamically for each output of this `Process`, and
   * sequence these processes using `append`.
   */
  final def flatMap[F2[x] >: F[x], O2](f: O => Process[F2, O2]): Process[F2, O2] = {
   // debug(s"FM this:$this")
    this match {
      case Halt(_) | Emit(Seq()) => this.asInstanceOf[Process[F2,O2]]
      case Emit(os)                => os.tail.foldLeft(Try(f(os.head)))((p, n) => p append Try(f(n)))
      case aw@Await(_, _)          => aw.extend(_ flatMap f)
      case ap@Append(p, n)         => ap.extend(_ flatMap f)
    }

  }

  /** Transforms the output values of this `Process` using `f`. */
  final def map[O2](f: O => O2): Process[F, O2] =
    flatMap { o => emit(f(o)) }

  /**
   * Feed the output of this `Process` as input of `p1`. The implementation
   * will fuse the two processes, so this process will only generate
   * values as they are demanded by `p1`. If `p1` signals termination, `this`
   * is killed with same reason giving it an opportunity to cleanup.
   */
  final def pipe[O2](p1: Process1[O, O2]): Process[F, O2] = p1.suspendStep flatMap {s =>
   // println((s"PIPE p1:$p1 p1step: $s, this: $this, thisStep: ${this.step}"))
    s match {
       case p1s@Cont(AwaitP1(rcv1), next1) => this.step match {
        case Cont(awt@Await(_, _), next) => awt.extend(p => (p onHalt next) pipe p1)
        case Cont(Emit(os), next)        => Try(next(End)) pipe process1.feed(os)(p1)
        case Done(rsn)                   => fail(rsn) onComplete p1s.toProcess.disconnect
      }
      case Cont(Emit(os), next1)          => Emit(os) ++ this.pipe(Try(next1(End)))
      case Done(rsn1)                     => this.killBy(Kill(rsn1)).swallowKill onComplete fail(rsn1)
    }
  }

  /** Operator alias for `pipe`. */
  final def |>[O2](p2: Process1[O, O2]): Process[F, O2] = pipe(p2)




    /**
     * Evaluate this process and produce next `Step` of this process.
     *
     * Result may be in state of `Cont` that indicates there are other process steps to be evaluated
     * or in `Done` that indicates process has finished and there is no more evaluation
     *
     * Note this evaluation is not resource safe, that means user must assure the evaluation whatever
     * is produced as next step in `Cont` until `Done`.
     */
  final def step: Step[F, O] = {
    @tailrec
    def go(cur: Process[F, O], stack: Vector[Throwable => Trampoline[Process[F, O]]]): Step[F, O] = {
    // debug(s"STEP $idx: $cur, stack: ${stack.size}")
      if (stack.isEmpty) {
        cur match {
          case Halt(rsn)      => Done(rsn)
          case Append(p, n)   => go(p, n)
          case AwaitOrEmit(p) => Cont(p, rsn => Halt(rsn))
        }
      } else {
        cur match {
          case Halt(End) =>  go(Try(stack.head(End).run), stack.tail)
          case Halt(rsn)      =>  go(Try(stack.head(rsn).run), stack.tail)
          case Append(p, n)   => go(p, n fast_++ stack)
          case AwaitOrEmit(p) => Cont(p, rsn => Append(Halt(rsn), stack))
        }
      }
    }

    go(this, Vector())

  }

  /**
   * Like `step` just suspended in Process for stack-safety and lazy evaluation.
   * @return
   */
  final def suspendStep: Process[Nothing,Step[F,O]] =
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
   * with reason that caused `tee` to terminate.
   */
  final def tee[F2[x]>:F[x],O2,O3](p2: Process[F2,O2])(t: Tee[O,O2,O3]): Process[F2,O3] = {
    import scalaz.stream2.tee.{AwaitL,AwaitR,feedL,feedR, haltR, haltL}
    t.suspendStep flatMap {
      case ts@Cont(AwaitL(_),_) => this.step match {
        case Cont(awt@Await(rq,rcv), next) => awt.extend { p => (p onHalt next).tee(p2)(t) }
        case Cont(Emit(os), next) => Try(next(End)).tee(p2)(feedL[O,O2,O3](os)(t))
        case d@Done(rsn) => d.asHalt.tee(p2)(haltL(ts.toProcess,Kill(rsn)))
      }
      case ts@Cont(AwaitR(_),_) => p2.step match {
        case Cont(awt:Await[F2,Any,O2]@unchecked, next:(Throwable => Process[F2,O2])@unchecked) =>
          awt.extend { p => this.tee(p onHalt next)(t) }
        case Cont(Emit(o2s:Seq[O2]@unchecked), next:(Throwable => Process[F2,O2])@unchecked) =>
          this.tee(Try(next(End)))(feedR[O,O2,O3](o2s)(t))
        case d@Done(rsn) =>
          this.tee(d.asHalt)(haltR(t,Kill(rsn)))
      }
      case Cont(emt@Emit(o3s), next) => emt ++ this.tee(p2)(Try(next(End)))
      case Done(rsn) =>
        this.killBy(Kill(rsn)).swallowKill onComplete
          p2.killBy(Kill(rsn)).swallowKill onComplete
          fail(rsn).swallowKill

    }

  }


  ////////////////////////////////////////////////////////////////////////////////////////
  // Alphabetical order from now on
  ////////////////////////////////////////////////////////////////////////////////////////

  /**
   * If this process halts without an error, attaches `p2` as the next step.
   * Also this won't attach `p2` whenever process was `Killed` by downstream
   */
  final def append[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = {
    onHalt {
      case End => p2
      case rsn => fail(rsn)
    }
  }

  /** alias for `append` **/
  final def ++[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = append(p2)

  /**
   * alias for `append`
   */
  final def fby[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = append(p2)

  /**
   * Catch exceptions produced by this `Process`, not including normal termination (`End` + `Kill`)
   * and uses `f` to decide whether to resume a second process.
   */
  final def attempt[F2[x]>:F[x],O2](
    f: Throwable => Process[F2,O2] = (t:Throwable) => emit(t)
    ): Process[F2, O2 \/ O] = {
    this.map(right) onFailure ( f andThen (p => p.map(left) ))
  }

  /**
   * Add `e` as a cause when this `Process` halts.
   * This is a no-op  if `e` is `Process.End`
   */
  final def causedBy(e: Throwable): Process[F, O] = {
    e match {
      case End => this
      case _ => onHalt(rsn => fail(CausedBy(rsn,e)))
    }
  }

  /** Ignore all outputs of this `Process`. */
  final def drain: Process[F, Nothing] = {
    val ts = this.step
    ts match {
      case Cont(Emit(_),n) => n(End).drain
      case Cont(awt@Await(_,_), next) => awt.extend(_.drain).onHalt(rsn=>next(rsn).drain)
      case Done(rsn) => fail(rsn)
    }
  }

  /**
   * Returns true, if this process is halted
   */
  final def isHalt: Boolean = this match {
    case Halt(_) => true
    case _ => false
  }

  /**
   * Used when a transducer is terminated by awaiting on a branch that
   * is in the halted state. Such a process is given the opportunity
   * to emit any final values before halting with `End`. The implementation
   * issues `Kill` to any future `onHalt`, then swallows these `Kill` causes
   * in the output process.
   *
   * A disconnected process will terminate either with `End` or an error,
   * never a `Kill` (exception: if it internally halts with `Kill` without
   * external signaling).
   */
  final def disconnect: Process[Nothing,O] = this.suspendStep flatMap {
    case Cont(e@Emit(_),n) =>
      e onHalt { rsn => Try(n(Kill(rsn)).disconnect).swallowKill }
    case Cont(Await(_,rcv),n) =>
      (Try(rcv(left(Kill)).run).disconnect onHalt {
        case End | Kill => Try(n(Kill).disconnect)
        case rsn => Try(n(rsn).disconnect)
      }).swallowKill
    case Done(rsn) => Halt(rsn)
  }

  /** Replaces `Halt(Kill)` with `Halt(End)`. */
  def swallowKill: Process[F,O] = this.onHalt {
    case Kill => halt
    case e: Throwable => fail(e)
  }

  /**
   * Causes this process to be terminated, giving chance for any cleanup actions to be run
   */
  final def kill: Process[F,Nothing] = killBy(Kill)

  /**
   * Causes this process to be terminated, giving chance for any cleanup actions to be run
   * @param rsn Reason for termination
   */
  final def killBy(rsn: Throwable): Process[F, Nothing] = {
    val ts = this.step
    //debug(s"KILLBY rsn:$rsn, this:$this, step: ${ts } ")
    ts match {
      case Cont(Emit(_),n) => Try(n(rsn)).drain
      case Cont(Await(_,_),n) => Try(Try(n(rsn))).drain
      case Done(End) => Halt(rsn)
      case Done(rsn0) => Halt(CausedBy(rsn0,rsn))
    }
  }

  //  /** Causes _all_ subsequent awaits to  to fail with the `End` exception. */
  //  final def disconnect: Process[F, O] = disconnect0(End)
  //
  //  /**
  //   * Causes _all_ subsequent awaits to  to fail with the `Kill` exception.
  //   * Awaits are supposed to run cleanup code on receiving `End` or `Kill` exception
  //   */
  //  final def hardDisconnect: Process[F, O] = disconnect0(Kill)
  //
  //  /** Causes _all_ subsequent awaits to  to fail with the `rsn` exception. */
  //  final def disconnect0(rsn:Throwable): Process[F, O] = {
  //    this match {
  //      case h@Halt(_)       => h
  //      case Await(_, rcv)   => suspend(Try(rcv(left(rsn)).run).disconnect(rsn))
  //      case ap@Append(p, n) => ap.extend(_.disconnect(rsn))
  //      case Emit(h)         => this
  //    }
  //  }

  //
  //  /** Send the `End` signal to the next `Await`, then ignore all outputs. */
  //  final def kill: Process[F, Nothing] = this.disconnect.drain

  /**
   * Run `p2` after this `Process` completes normally, or in the event of an error.
   * This behaves almost identically to `append`, except that `p1 append p2` will
   * not run `p2` if `p1` halts with an error.
   *
   * If you want to attach code that depends on reason of termination, use `onHalt`
   *
   */
  final def onComplete[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] =
    onHalt {
      case End => Try(p2)
      case rsn =>  Try(p2).causedBy(rsn)
    }

  /**
   * runs process generated by evaluating `f` and passing any failure reason for this process to terminate.
   * Note this is run only when process was terminated with failure that means any reason different
   * from `End` or `Kill`
   */
  final def onFailure[F2[x] >: F[x], O2 >: O](f: Throwable => Process[F2, O2]): Process[F2, O2] = {
    onHalt {
      case End => halt
      case Kill => fail(Kill)
      case rsn => Try(f(rsn))
    }
  }


  /**
   * When the process terminates either due to `End` or `Throwable`
   * `f` is evaluated to produce next state.
   */
  final def onHalt[F2[x] >: F[x], O2 >: O](f: Throwable => Process[F2, O2]): Process[F2, O2] = {
    val next = (t: Throwable) => Trampoline.delay(Try(f(t)))
    this match {
      case HaltEmitOrAwait(p) => Append(p, Vector(next))
      case Append(p, n)   => Append(p, n :+ next)
    }
  }


  /**
   * Atached supplied process only if process has been terminated with `Kill` signal.
   */
  final def onKill[F2[x] >: F[x], O2 >: O](p: => Process[F2, O2]): Process[F2, O2] = {
    onHalt {
      case End => halt
      case Kill => p
      case rsn => fail(rsn)
    }
  }



  /**
   * Run this process until it halts, then run it again and again, as
   * long as no errors occur.
   */
  final def repeat: Process[F, O] = {
 //  debug(s"REPEAT $this ")
    this.onHalt {
      case End =>  this.repeat
      case rsn =>  fail(rsn)
    }
  }

  /** Translate the request type from `F` to `G`, using the given polymorphic function. */
  def translate[G[_]](f: F ~> G): Process[G,O] =
    this.suspendStep.flatMap {
      case Cont(Emit(os),next) => emitAll(os) ++ (Try(next(End)) translate f)
      case Cont(Await(req,rcv), next) =>
        Await[G,Any,O](f(req), r => {
          Trampoline.suspend(rcv(r)).map(_ translate f)
        }) onHalt { rsn => Try(next(rsn)) translate f }
      case dn@Done(rsn) => dn.asHalt
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
        case Cont(Emit(os),n) => go(n(End),acc fast_++ os)
        case Cont(awt,next) => (acc,awt onHalt next)
        case Done(rsn) => (acc,Halt(rsn))
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
      cur.step match {
        case Cont(Emit(os),next) =>
          F.bind(F.point(os.foldLeft(acc)((b, o) => B.append(b, f(o))))) { nacc =>
            go(Try(next(End).asInstanceOf[Process[F2, O]]), nacc)
          }
        case Cont(awt:Await[F2,Any,O]@unchecked,next:(Throwable => Process[F2,O])@unchecked) =>
          F.bind(C.attempt(awt.req)) { _.fold(
            rsn => go(Try(awt.rcv(left(rsn)).run).causedBy(rsn) onHalt next , acc)
            , r => go(Try(awt.rcv(right(r)).run) onHalt next, acc)
          )}
        case Done(End) => F.point(acc)
        case Done(Kill) => F.point(acc)
        case Done(err) => C.fail(err)
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

  import Util._

  type Trampoline[+A] = scalaz.Free.Trampoline[A]
  val Trampoline = scalaz.Trampoline


  /**
   * Tags a state of process that has no appended tail, tha means can be Halt, Emit or Await
   */
  sealed trait HaltEmitOrAwait[+F[_], +O] extends Process[F, O]

  object HaltEmitOrAwait {

    def unapply[F[_], O](p: Process[F, O]): Option[HaltEmitOrAwait[F, O]] = p match {
      case emit: Emit[O@unchecked] => Some(emit)
      case halt: Halt                             => Some(halt)
      case aw: Await[F@unchecked, _, O@unchecked] => Some(aw)
      case _                                      => None
    }

  }

  sealed trait AwaitOrEmit[+F[_], +O] extends HaltEmitOrAwait[F,O]

  object AwaitOrEmit {
    def unapply[F[_], O](p: Process[F, O]): Option[AwaitOrEmit[F, O]] = p match {
      case emit: Emit[O@unchecked] => Some(emit)
      case aw: Await[F@unchecked, _, O@unchecked] => Some(aw)
      case _                                      => None
    }
  }

  /**
   * The `Halt` constructor instructs the driver to stop
   * due to the given reason as `Throwable`.
   * The special `Throwable` instance `Process.End`
   * indicates normal termination. It's more typical to construct a `Halt` via
   * `Process.halt` (for normal termination) or
   * `Process.fail(err)` (for termination with an error).
   */
  case class Halt(rsn: Throwable) extends HaltEmitOrAwait[Nothing, Nothing]

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
  case class Emit[+O](seq: Seq[O]) extends AwaitOrEmit[Nothing, O]

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
    ) extends AwaitOrEmit[F, O] {
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
    head: HaltEmitOrAwait[F, O]
    , tail: Vector[Throwable => Trampoline[Process[F, O]]]
    ) extends Process[F, O] {


    /**
     * Helper to modify the head and appended processes
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Process[F2, O2] = {
      val et = tail.map(n => (t: Throwable) => Trampoline.suspend(n(t)).map(f))
      Try(f(head)) match {
        case HaltEmitOrAwait(p) =>
          Append(p, et)
        case ap: Append[F2@unchecked, O2@unchecked] =>
          Append(ap.head, ap.tail fast_++ et)
      }
    }

  }

  sealed trait Step[+F[_], +O] {
    /**
     * Helper to convert `Step` to appended process again.
     */
    def toProcess: Process[F,O] = this match {
      case Done(rsn) => fail(rsn)
      case Cont(hd, tl) => hd onHalt tl
    }
  }

  /**
   * A `Step` that indtcates that process has terminated.
   * @param rsn
   */
  case class Done(rsn: Throwable) extends Step[Nothing, Nothing] {
    def asHalt : Halt = Halt(rsn)
  }

  /**
   * A `Step` that indicates there is Await or Emit at head, and some next step of `Process`
   * that will be eventually produced when the head will get evaluated.
   */
  case class Cont[+F[_], +O](h: AwaitOrEmit[F, O], next: Throwable => Process[F, O]) extends Step[F, O]


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
    Await[F, A, O](req, r => Trampoline.delay(r.fold(fail,rcv)))

  /**
   * Constructs an await, that allows to specify processes that will be run when
   * request wil either terminate with `End` or `Kill` (fallback) or with error (onError).
   *
   * if you don't need to specify `fallback` or `onError` use `await`
   */
  def awaitOr[F[_],A,O](req:F[A])(
    fb: Throwable => Process[F,O]
    )(rcv: A => Process[F, O]): Process[F, O] = {
    Await(req,(r:Throwable \/ A) => Trampoline.delay(r.fold(
      rsn => Try(fb(rsn))
      , a => rcv(a)
    )))
  }

  /** helper to construct await for Process1 **/
  def await1[I]: Process1[I, I] =
    await(Get[I])(emit)

  /** heleper to construct for await on Both sides. Can be used in `Wye`**/
  def awaitBoth[I,I2]: Wye[I,I2,ReceiveY[I,I2]] =
    await(Both[I,I2])(emit)

  /** helper construct for await on Left side. Can be used in `Tee` and `Wye` **/
  def awaitL[I]: Tee[I,Any,I] =
    await(L[I])(emit)

  /** helper construct for await on Right side. Can be used in `Tee` and `Wye` **/
  def awaitR[I2]: Tee[Any,I2,I2] =
    await(R[I2])(emit)

  /** constructor to emit single `O` **/
  def emit[O](o: O): Process[Nothing, O] = Emit(Vector(o))

  /** constructor to emit sequence of `O` **/
  def emitAll[O](os: Seq[O]): Process[Nothing, O] = os match {
    case Seq() => halt
    case _ => Emit(os)
  }

  /** constructor to emit sequence of `O` having `tail` as next state **/
  @deprecated("Use please emitAll(h) ++ tail instead", "0.5.0")
  def emitSeq[F[_], O](h: Seq[O], t: Process[F, O] = halt): Process[F, O] = t match {
    case `halt` | Emit(Seq()) => emitAll(h)
    case _      => emitAll(h) ++ t
  }

  /** Indicates termination with supplied reason **/
  def fail(rsn: Throwable): Process[Nothing, Nothing] = Halt(rsn)


  /**
   * The infinite `Process`, always emits `a`.
   * If for performance reasons is good to emit `a` in chunks,
   * specifiy size of chunk by `chunkSize` parameter
   */
  def constant[A](a: A, chunkSize: Int = 1): Process[Task,A] = {
    lazy val go: Process[Task,A] =
      if (chunkSize.max(1) == 1)
        await(Task.now(a))(emit) ++ go
      else
        await(Task.now(a))(r=>emitAll(List.fill(chunkSize)(r))) ++ go
    go
  }


  /**
   * awaits receive of `I` in process1, and attaches fallback in case await evaluation
   * terminated with `End` indicated source being exhausted or `Kill` indicating process was
   * forcefully terminated.
   *
   * If you don't need to handle the `fallback` case, use `await1.flatMap`
   */
  def receive1[I,O](rcv: I => Process1[I,O], fallback: => Process1[I,O] = halt): Process1[I,O] =
    awaitOr(Get[I])({
      case End | Kill => fallback
      case rsn => fail(rsn)
    })(rcv)

  /**
   * Curried syntax alias for receive1
   */
  def receive1Or[I,O](fb: => Process1[I,O])(rcv: I => Process1[I,O]): Process1[I,O] =
    receive1[I,O](rcv,fb)

  /**
   * Awaits to receive input from Both sides,
   * than if that request terminates with `End` or is killed by `Kill`
   * runs the supplied fallback. Otherwise `rcv` is run to produce next state.
   *
   * If you don't need `fallback` use rather `awaitBoth.flatMap`
   */
  def receiveBoth[I,I2,O](rcv: ReceiveY[I,I2] => Wye[I,I2,O], fallback: => Wye[I,I2,O] = halt): Wye[I,I2,O] =
    awaitOr[Env[I,I2]#Y,ReceiveY[I,I2],O](Both)({
      case End | Kill => fallback
      case rsn => fail(rsn)
    })(rcv)

  /**
   * Awaits to receive input from Left side,
   * than if that request terminates with `End` or is killed by `Kill`
   * runs the supplied fallback. Otherwise `rcv` is run to produce next state.
   *
   * If  you don't need `fallback` use rather `awaitL.flatMap`
   */
  def receiveL[I,I2,O](rcv: I => Tee[I,I2,O], fallback: => Tee[I,I2,O] = halt): Tee[I,I2,O] =
    awaitOr[Env[I,I2]#T,I,O](L)({
      case End | Kill => fallback
      case rsn => fail(rsn)
    })(rcv)

  /**
   * Awaits to receive input from Right side,
   * than if that request terminates with `End` or is killed by `Kill`
   * runs the supplied fallback. Otherwise `rcv` is run to produce next state.
   *
   * If  you don't need `fallback` use rather `awaitR.flatMap`
   */
  def receiveR[I,I2,O](rcv: I2 => Tee[I,I2,O], fallback: => Tee[I,I2,O] = halt
    ): Tee[I,I2,O] =
    awaitOr[Env[I,I2]#T,I2,O](R)({
      case End | Kill => fallback
      case rsn => fail(rsn)
    })(rcv)

  /** syntax sugar for receiveBoth  **/
  def receiveBothOr[I,I2,O](fallback: => Wye[I,I2,O])(rcv: ReceiveY[I,I2] => Wye[I,I2,O]):Wye[I,I2,O] =
    receiveBoth(rcv, fallback)

  /** syntax sugar for receiveL **/
  def receiveLOr[I,I2,O](fallback: => Tee[I,I2,O])(rcvL: I => Tee[I,I2,O]): Tee[I,I2,O] =
    receiveL(rcvL, fallback)

  /** syntax sugar for receiveR **/
  def receiveROr[I,I2,O](fallback: => Tee[I,I2,O])(rcvR: I2 => Tee[I,I2,O]): Tee[I,I2,O] =
    receiveR(rcvR, fallback)


  ///////////////////////////////////////////////////////////////////////////////////////
  //
  // DECONSTRUCTORS, Matchers
  //
  //////////////////////////////////////////////////////////////////////////////////////

  //  object Await1 {
  //
  //    @deprecated("use rather AwaitP1 deconstruct", "0.5.0")
  //    def unapply[I, O](self: Process1[I, O]):
  //    Option[(I => Process1[I, O], Process1[I, O], Process1[I, O])] = self match {
  //      case Await(_, rcv) => Some(
  //        ((i: I) => Try(rcv(right(i)).run)
  //          , suspend(Try(rcv(left(End)).run))
  //          , suspend(Try(rcv(left(Kill)).run)))
  //      )
  //      case _             => None
  //    }
  //
  //  }

  object AwaitP1 {
    /** deconstruct for `Await` directive of `Process1` **/
    def unapply[I, O](self: Process1[I, O]): Option[I => Process1[I, O]] = self match {
      case Await(_, rcv) => Some((i: I) => Try(rcv(right(i)).run))
      case _             => None
    }

    /** Like `AwaitP1.unapply` only allows for extracting the fallback case as well **/
    object withFb {
      def unapply[I,I2,O](self: Process1[I,O]):
      Option[(Throwable \/ I => Process1[I,O])] = self match {
        case Await(_,rcv) => Some((r : Throwable \/ I) => Try(rcv(r).run))
        case _ => None
      }
    }

    /** Like `AwaitP1.unapply` only allows for extracting the fallback case as well on Trampoline **/
    object withFbT {
      def unapply[I,I2,O](self: Process1[I,O]):
      Option[(Throwable \/ I => Trampoline[Process1[I,O]])] = self match {
        case Await(_,rcv) => Some(rcv)
        case _ => None
      }
    }
  }


  ///////////////////////////////////////////////////////////////////////////////////////
  //
  // CONSUTRUCTORS -> Helpers
  //
  //////////////////////////////////////////////////////////////////////////////////////

  /**
   * Discrete process, that ever `d` emits elapsed duration
   * since being run for the firs time.
   *
   * For example: `awakeEvery(5 seconds)` will
   * return (approximately) `5s, 10s, 20s`, and will lie dormant
   * between emitted values.
   *
   * By default, this uses a shared
   * `ScheduledExecutorService` for the timed events, and runs the
   * actual callbacks with `S` Strategy, to allow for the process to decide whether
   * result shall be run on different thread pool, or with `Strategy.Sequential` on the
   * same thread pool of the scheduler.
   *
   * @param d           Duration between emits of the resulting process
   * @param S           Strategy to run the process
   * @param scheduler   Scheduler used to schedule tasks
   */
  def awakeEvery(d:FiniteDuration)(
    implicit S: Strategy
      , scheduler: ScheduledExecutorService
    ):Process[Task,FiniteDuration] = {

    def schedule(start:FiniteDuration):Task[FiniteDuration] = Task.async { cb =>
      scheduler.schedule(new Runnable{
        override def run(): Unit = S(cb(right(start + d)))
      }, d.toNanos, TimeUnit.NANOSECONDS)
    }
    def go(start:FiniteDuration):Process[Task,FiniteDuration] =
      await(schedule(start))(d => emit(d) ++ go(d))

    go(0 millis)
  }

  /** `Process.emitRange(0,5) == Process(0,1,2,3,4).` */
  def emitRange(start: Int, stopExclusive: Int): Process[Nothing,Int] =
    emitAll(start until stopExclusive)

  /** Lazily produce the range `[start, stopExclusive)`. */
  def range(start: Int, stopExclusive: Int, by: Int = 1): Process[Task, Int] =
    unfold(start)(i => if (i < stopExclusive) Some((i,i+by)) else None)

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


  /** Produce a (potentially infinite) source from an unfold. */
  def unfold[S, A](s0: S)(f: S => Option[(A, S)]): Process[Task, A] =
    await(Task.delay(f(s0)))({
      case Some(ht) => emit(ht._1) ++ unfold(ht._2)(f)
      case None => halt
    })

  //////////////////////////////////////////////////////////////////////////////////////
  //
  // INSTANCES
  //
  /////////////////////////////////////////////////////////////////////////////////////

  /**
   * Special exception indicating normal termination. Throwing this
   * exception results in control switching to process, that is appended
   */
  case object End extends Exception {
    override def fillInStackTrace = this
  }

  /**
   * Special exception indicating downstream termination.
   * An `Await` should respond to a `Kill` by performing
   * necessary cleanup actions, then halting with `End`
   */
  case object Kill extends Exception {
    def apply(rsn:Throwable):Throwable = rsn match {
      case End => Kill
      case _ => rsn
    }
    override def fillInStackTrace = this
  }

  /** A special variant of `Kill` used in Process1 to signal termination of Process1 **/
  case object Kill1 extends Exception {
    override def fillInStackTrace = this
  }

  /**
   * Wrapper for Exception that was caused by other Exception during the
   * Execution of the Process
   */
  class CausedBy(e: Throwable, cause: Throwable) extends Exception(cause) {
    override def toString = s"$e caused by: $cause"
  }

  object CausedBy {
    def apply(e: Throwable, cause: Throwable): Throwable =
      e match {
        case End => cause
        case `cause` => e
        case _   => new CausedBy(e, cause)
      }
  }

  /** indicates normal termination **/
  val halt = Halt(End)



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
  implicit class EvalProcess[F[_], O](self: Process[F, F[O]]) {

    /**
     * Evaluate the stream of `F` actions produced by this `Process`.
     * This sequences `F` actions strictly--the first `F` action will
     * be evaluated before work begins on producing the next `F`
     * action. To allow for concurrent evaluation, use `sequence`
     * or `gather`.
     */
    def eval: Process[F, O] = {
      self.step match {
        case Cont(Emit(fos), next)       =>
          fos.foldLeft(halt: Process[F, O])((p, n) => p ++ Process.eval(n)) ++ next(End).eval
        case Cont(awt@Await(_, _), next) =>
          awt.extend(_.eval) onHalt (rsn => next(rsn).eval)
        case Done(rsn)                   => Halt(rsn)
      }
    }

  }


  /**
   * This class provides infix syntax specific to `Process0`.
   */
  implicit class Process0Syntax[O](self: Process0[O]) {
    def toIndexedSeq: IndexedSeq[O] = {
      @tailrec
      def go(cur: Process0[O], acc:Vector[O]) : IndexedSeq[O] = {
        cur.step match {
          case Cont(Emit(os),next) => go(next(End),acc fast_++ os)
          case Cont(_,next) => go(next(End),acc)
          case Done(End) => acc
          case Done(rsn) => throw rsn
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
    def toSource: Process[Task, O] =    emitAll(toIndexedSeq.map(o => Task.delay(o))).eval
  }

  /**
   * Syntax for processes that have its effects wrapped in Task
   * @param self
   * @tparam O
   */
  implicit class SourceSyntax[O](val self: Process[Task, O]) extends WyeOps[O] {

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
    protected[stream2] final def runAsync(
      cb: Throwable \/ (Seq[O], Throwable => Process[Task, O]) => Unit
      )(implicit S: Strategy): (Throwable) => Unit = {

      sealed trait M
      case class AwaitDone(res: Throwable \/ Any, awt: Await[Task, Any, O], next: Throwable => Process[Task, O]) extends M
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
      def runStep(p: Process[Task, O]): Throwable \/ (Throwable => Process[Task, O]) = {
        val step = p.step
        step match {
          case Cont(Emit(Seq()), next)         => runStep(Try(next(End)))
          case Cont(Emit(h), next)             => cb(right((h, next))); left(End)
          case Cont(awt@Await(req, rcv), next) =>
            req.runAsync(r => a ! AwaitDone(r, awt, next))
            right((t: Throwable) => Try(rcv(left(t)).run) onHalt next)
          case Done(rsn)                       => cb(left(rsn)); left(rsn)
        }
      }


      a = new Actor[M]({
        case AwaitDone(r, awt, next) if completed.isEmpty =>
          runStep(Try(awt.rcv(r).run) onHalt next).fold(
            rsn => completed = Some(rsn)
            , cln => cleanup = cln
          )

        // on interrupt we just run any cleanup code we have memo-ed
        // from last `Await`
        case Interrupt(rsn) if completed.isEmpty =>
          completed = Some(rsn)
          cleanup(Kill(rsn)).run.runAsync(_.fold(
            rsn0 => cb(left(CausedBy(rsn0, rsn)))
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
            .killBy(Kill(completed.get))
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
  def repeat[F[_],O](p: Process[F,O]): Process[F,O] = p.repeat

  /**
   * Evaluate an arbitrary effect in a `Process`. The resulting `Process` will emit values
   * until evaluation of `t` signals termination with `End` or an error occurs.
   */
  def repeatEval[F[_], O](f: F[O]): Process[F, O] =
    eval(f) ++ repeatEval(f)


  /**
   * Produce `p` lazily, guarded by a single `Append`. Useful if
   * producing the process involves allocation of some mutable
   * resource we want to ensure is accessed in a single-threaded way.
   */
  def suspend[F[_], O](p: => Process[F, O]): Process[F, O] = {
   // println("SUSPEND")
    Append(halt,Vector({
      case End => Trampoline.delay(p)
      case rsn => Trampoline.delay(p causedBy rsn)
    }))
  }



}
