package scalaz.stream

import Cause._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.annotation.tailrec
import scala.collection.SortedMap
import scala.concurrent.duration._
import scala.Function.const

import scalaz.stream.async.immutable.Signal
import scalaz.{\/-, Catchable, Functor, Monad, Monoid, Nondeterminism, \/, -\/, ~>}
import scalaz.\/._
import scalaz.concurrent.{Actor, Future, Strategy, Task}
import scalaz.stream.process1.Await1
import scalaz.syntax.monad._

import scala.annotation.unchecked.uncheckedVariance

/**
 * An effectful stream of `O` values. In between emitting values
 * a `Process` may request evaluation of `F` effects.
 * A `Process[Nothing,A]` is a pure `Process` with no effects.
 * A `Process[Task,A]` may have `Task` effects. A `Process`
 * halts due to some `Cause`, generally `End` (indicating normal
 * termination) or `Error(t)` for some `t: Throwable` indicating
 * abnormal termination due to some uncaught error.
 */
sealed trait Process[+F[_], +O]
  extends Process1Ops[F,O]
          with TeeOps[F,O] {

  import scalaz.stream.Process._
  import scalaz.stream.Util._

  /**
   * Generate a `Process` dynamically for each output of this `Process`, and
   * sequence these processes using `append`.
   */
  final def flatMap[F2[x] >: F[x], O2](f: O => Process[F2, O2]): Process[F2, O2] = {
    // Util.debug(s"FMAP $this")
    this match {
      case Halt(_) => this.asInstanceOf[Process[F2, O2]]
      case Emit(os) if os.isEmpty => this.asInstanceOf[Process[F2, O2]]
      case Emit(os) => os.tail.foldLeft(Try(f(os.head)))((p, n) => p ++ Try(f(n)))
      case aw@Await(_, _, _) => aw.extend(_ flatMap f)
      case ap@Append(p, n) => ap.extend(_ flatMap f)
    }
  }
  /** Transforms the output values of this `Process` using `f`. */
  final def map[O2](f: O => O2): Process[F, O2] =
    flatMap { o => emit(f(o))}

  /**
   * If this process halts due to `Cause.End`, runs `p2` after `this`.
   * Otherwise halts with whatever caused `this` to `Halt`.
   */
  final def append[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = {
    onHalt {
      case End => p2
      case cause => Halt(cause)
    }
  }

  /** Alias for `append` */
  final def ++[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = append(p2)

  /** Alias for `append` */
  final def fby[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = append(p2)

  /**
   * Run one step of an incremental traversal of this `Process`.
   * This function is mostly intended for internal use. As it allows
   * a `Process` to be observed and captured during its execution,
   * users are responsible for ensuring resource safety.
   */
  final def step: HaltOrStep[F, O] = {
    val empty: Emit[Nothing] = Emit(Nil)
    @tailrec
    def go(cur: Process[F,O], stack: Vector[Cause => Trampoline[Process[F,O]]], cnt: Int) : HaltOrStep[F,O] = {
      if (stack.nonEmpty) cur match {
        case Halt(End) if cnt <= 0  => Step(empty,Cont(stack))
        case Halt(cause) => go(Try(stack.head(cause).run), stack.tail, cnt - 1)
        case Emit(os) if os.isEmpty => Step(empty,Cont(stack))
        case emt@(Emit(os)) => Step(emt,Cont(stack))
        case awt@Await(_,_,_) => Step(awt,Cont(stack))
        case Append(h,st) => go(h, st fast_++ stack, cnt - 1)
      } else cur match {
        case hlt@Halt(cause) => hlt
        case emt@Emit(os) if os.isEmpty => halt0
        case emt@Emit(os) => Step(emt,Cont(Vector.empty))
        case awt@Await(_,_,_) => Step(awt,Cont(Vector.empty))
        case Append(h,st) => go(h,st, cnt - 1)
      }
    }
    go(this,Vector.empty, 10)   // *any* value >= 1 works here. higher values improve throughput but reduce concurrency and fairness. 10 is a totally wild guess

  }

  /**
   * `p.suspendStep` propagates exceptions to `p`.
   */
  final def suspendStep: Process0[HaltOrStep[F, O]] =
    halt onHalt {
      case End => emit(step)
      case early: EarlyCause => emit(injectCause(early).step)
    }

  /**
   * When this `Process` halts, call `f` to produce the next state.
   * Note that this function may be used to swallow or handle errors.
   */
  final def onHalt[F2[x] >: F[x], O2 >: O](f: Cause => Process[F2, O2]): Process[F2, O2] = {
     val next = (t: Cause) => Trampoline.delay(Try(f(t)))
     this match {
       case (append: Append[F2, O2] @unchecked) => Append(append.head, append.stack :+ next)
       case emt@Emit(_)        => Append(emt, Vector(next))
       case awt@Await(_, _, _) => Append(awt, Vector(next))
       case hlt@Halt(rsn)      => Append(hlt, Vector(next))
     }
  }


  //////////////////////////////////////////////////////////////////////////////////////
  //
  // Pipe and Tee
  //
  /////////////////////////////////////////////////////////////////////////////////////


  /**
   * Feed the output of this `Process` as input of `p1`. The implementation
   * will fuse the two processes, so this process will only generate
   * values as they are demanded by `p1`. If `p1` signals termination, `this`
   * is killed with same reason giving it an opportunity to cleanup.
   */
  final def pipe[O2](p1: Process1[O, O2]): Process[F, O2] =
    p1.suspendStep.flatMap({ s1 =>
      s1 match {
        case s@Step(awt1@Await1(rcv1), cont1) =>
          val nextP1 = s.toProcess
          this.step match {
            case Step(awt@Await(_, _, _), cont) => awt.extend(p => (p +: cont) pipe nextP1)
            case Step(Emit(os), cont)           => cont.continue pipe process1.feed(os)(nextP1)
            case hlt@Halt(End)                  => hlt pipe nextP1.disconnect(Kill).swallowKill
            case hlt@Halt(rsn: EarlyCause)      => hlt pipe nextP1.disconnect(rsn)
          }

        case Step(emt@Emit(os), cont)      =>
          // When the pipe is killed from the outside it is killed at the beginning or after emit.
          // This ensures that Kill from the outside is not swallowed.
          emt onHalt {
            case End => this.pipe(cont.continue)
            case early => this.pipe(Halt(early) +: cont).causedBy(early)
          }

        case Halt(rsn)           => this.kill onHalt { _ => Halt(rsn) }
      }
    })

  /** Operator alias for `pipe`. */
  final def |>[O2](p2: Process1[O, O2]): Process[F, O2] = pipe(p2)

  /**
   * Use a `Tee` to interleave or combine the outputs of `this` and
   * `p2`. This can be used for zipping, interleaving, and so forth.
   * Nothing requires that the `Tee` read elements from each
   * `Process` in lockstep. It could read fifty elements from one
   * side, then two elements from the other, then combine or
   * interleave these values in some way, etc.
   *
   * If at any point the `Tee` awaits on a side that has halted,
   * we gracefully kill off the other side, then halt.
   *
   * If at any point `t` terminates with cause `c`, both sides are killed, and
   * the resulting `Process` terminates with `c`.
   */
  final def tee[F2[x] >: F[x], O2, O3](p2: Process[F2, O2])(t: Tee[O, O2, O3]): Process[F2, O3] = {
    import scalaz.stream.tee.{AwaitL, AwaitR, disconnectL, disconnectR, feedL, feedR}
    t.suspendStep flatMap { ts =>
      ts match {
        case s@Step(AwaitL(_), contT) => this.step match {
          case Step(awt@Await(_, _, _), contL) => awt.extend { p => (p  +: contL).tee(p2)(s.toProcess) }
          case Step(Emit(os), contL)           => contL.continue.tee(p2)(feedL[O, O2, O3](os)(s.toProcess))
          case hlt@Halt(End)              => hlt.tee(p2)(disconnectL(Kill)(s.toProcess).swallowKill)
          case hlt@Halt(rsn: EarlyCause)  => hlt.tee(p2)(disconnectL(rsn)(s.toProcess))
        }

        case s@Step(AwaitR(_), contT) => p2.step match {
          case s2: Step[F2, O2]@unchecked =>
            (s2.head, s2.next) match {
              case (awt: Await[F2, Any, O2]@unchecked, contR) =>
                awt.extend { (p: Process[F2, O2]) => this.tee(p +: contR)(s.toProcess) }
              case (Emit(o2s), contR) =>
                this.tee(contR.continue.asInstanceOf[Process[F2,O2]])(feedR[O, O2, O3](o2s)(s.toProcess))
            }
          case hlt@Halt(End)              => this.tee(hlt)(disconnectR(Kill)(s.toProcess).swallowKill)
          case hlt@Halt(rsn : EarlyCause) => this.tee(hlt)(disconnectR(rsn)(s.toProcess))
        }

        case Step(emt@Emit(o3s), contT) =>
          // When the process is killed from the outside it is killed at the beginning or after emit.
          // This ensures that Kill from the outside isn't swallowed.
          emt onHalt {
            case End => this.tee(p2)(contT.continue)
            case early => this.tee(p2)(Halt(early) +: contT).causedBy(early)
          }

        case Halt(rsn)             => this.kill onHalt { _ => p2.kill onHalt { _ => Halt(rsn) } }
      }
    }
  }


  //////////////////////////////////////////////////////////////////////////////////////
  //
  // Alphabetically, Other combinators
  //
  /////////////////////////////////////////////////////////////////////////////////////

  /**
   * Catch exceptions produced by this `Process`, not including termination by `Continue`, `End`, `Kill`
   * and uses `f` to decide whether to resume a second process.
   */
  final def attempt[F2[x] >: F[x], O2](
    f: Throwable => Process[F2, O2] = (t: Throwable) => emit(t)
    ): Process[F2, O2 \/ O] =
    this.map(right) onHalt {
      case Error(t) => Try(f(t)).map(left)
      case rsn      => Halt(rsn)
    }

  /**
   * Attached `cause` when this Process terminates.  See `Cause.causedBy` for semantics.
   */
  final def causedBy(cause: Cause): Process[F, O] =
    cause.fold(this)(ec => this.onHalt(c => Halt(c.causedBy(ec))))

  /**
   * Used when a `Process1`, `Tee`, or `Wye` is terminated by awaiting
   * on a branch that is in the halted state or was killed. Such a process
   * is given the opportunity to emit any final values. All Awaits are
   * converted to terminate with `cause`
   */
  final def disconnect(cause: EarlyCause): Process0[O] =
    this.step match {
      case Step(emt@Emit(_), cont)     => emt +: cont.extend(_.disconnect(cause))
      case Step(awt@Await(_, rcv,_), cont) => suspend((Try(rcv(left(cause)).run) +: cont).disconnect(cause))
      case hlt@Halt(rsn)           => Halt(rsn)
    }

  /** Ignore all outputs of this `Process`. */
  final def drain: Process[F, Nothing] = flatMap(_ => halt)

  /**
   * Map over this `Process` to produce a stream of `F`-actions,
   * then evaluate these actions.
   */
  def evalMap[F2[x]>:F[x],O2](f: O => F2[O2]): Process[F2,O2] =
    map(f).eval

  /** Prepend a sequence of elements to the output of this `Process`. */
  def prepend[O2>:O](os:Seq[O2]) : Process[F,O2] = {
    if (os.nonEmpty) {
      emitAll(os) onHalt {
        case End               => this
        case cause: EarlyCause => this.step match {
          case Step(Await(_, rcv, _), cont) => Try(rcv(left(cause)).run) +: cont
          case Step(Emit(_), cont)       => Halt(cause) +: cont
          case Halt(rsn)             => Halt(rsn.causedBy(cause))
        }
      }
    } else this
  }

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

  /** Returns true, if this process is halted */
  final def isHalt: Boolean = this match {
    case Halt(_) => true
    case _ => false
  }

  /**
   * Skip the first part of the process and pretend that it ended with `early`.
   * The first part is the first `Halt` or the first `Emit` or request from the first `Await`.
   */
  private[stream] final def injectCause(early: EarlyCause): Process[F, O] = (this match {
    // Note: We cannot use `step` in the implementation since we want to inject `early` as soon as possible.
    // Eg. Let `q` be `halt ++ halt ++ ... ++ p`. `step` reduces `q` to `p` so if `injectCause` was implemented
    // by `step` then `q.injectCause` would be same as `p.injectCause`. But in our current implementation
    // `q.injectCause` behaves as `Halt(early) ++ halt ++ ... ++ p` which behaves as `Halt(early)`
    // (by the definition of `++` and the fact `early != End`).
    case Halt(rsn) => Halt(rsn.causedBy(early))
    case Emit(_) => Halt(early)
    case Await(_, rcv, _) => Try(rcv(left(early)).run)
    case Append(Halt(rsn), stack) => Append(Halt(rsn.causedBy(early)), stack)
    case Append(Emit(_), stack) => Append(Halt(early), stack)
    case Append(Await(_, rcv, _), stack) => Try(rcv(left(early)).run) +: Cont(stack)
  })

  /**
   * Causes this process to be terminated immediately with `Kill` cause,
   * giving chance for any cleanup actions to be run
   */
  final def kill: Process[F, Nothing] = injectCause(Kill).drain.causedBy(Kill)

  /**
   * Run `p2` after this `Process` completes normally, or in the event of an error.
   * This behaves almost identically to `append`, except that `p1 append p2` will
   * not run `p2` if `p1` halts with an `Error` or is killed. Any errors raised by
   * `this` are reraised after `p2` completes.
   *
   * Note that `p2` is made into a finalizer using `asFinalizer`, so we
   * can be assured it is run even when this `Process` is being killed
   * by a downstream consumer.
   */
  final def onComplete[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] =
    this.onHalt { cause => p2.asFinalizer.causedBy(cause) }

  /**
   * Mostly internal use function. Ensures this `Process` is run even
   * when being `kill`-ed. Used to ensure resource safety in various
   * combinators.
   */
  final def asFinalizer: Process[F, O] = {
    def mkAwait[F[_], A, O](req: F[A], cln: A => Trampoline[Process[F,Nothing]])(rcv: EarlyCause \/ A => Trampoline[Process[F, O]]) = Await(req, rcv,cln)
    step match {
      case Step(e@Emit(_), cont) => e onHalt {
        case Kill => (halt +: cont).asFinalizer.causedBy(Kill)
        case cause => (Halt(cause) +: cont).asFinalizer
      }
      case Step(Await(req, rcv, cln), cont) => mkAwait(req, cln) {
        case -\/(Kill) => Trampoline.delay(Await(req, rcv, cln).asFinalizer.causedBy(Kill))
        case x => rcv(x).map(p => (p +: cont).asFinalizer)
      }
      case hlt@Halt(_) => hlt
    }
  }

  /**
   * If this `Process` completes with an error, call `f` to produce
   * the next state. `f` is responsible for reraising the error if that
   * is the desired behavior. Since this function is often used for attaching
   * resource deallocation logic, the result of `f` is made into a finalizer
   * using `asFinalizer`, so we can be assured it is run even when this `Process`
   * is being killed by a downstream consumer.
   */
  final def onFailure[F2[x] >: F[x], O2 >: O](f: Throwable => Process[F2, O2]): Process[F2, O2] =
    this.onHalt {
      case err@Error(rsn) => f(rsn).asFinalizer
      case other => Halt(other)
    }

  /**
   * Attach supplied process only if process has been killed.
   * Since this function is often used for attaching resource
   * deallocation logic, the result of `f` is made into a finalizer
   * using `asFinalizer`, so we can be assured it is run even when
   * this `Process` is being killed by a downstream consumer.
   */
  final def onKill[F2[x] >: F[x], O2 >: O](p: => Process[F2, O2]): Process[F2, O2] =
    this.onHalt {
      case Kill => p.asFinalizer
      case other => Halt(other)
    }

  /**
   * Like `attempt`, but accepts a partial function. Unhandled errors are rethrown.
   */
  def partialAttempt[F2[x]>:F[x],O2](f: PartialFunction[Throwable, Process[F2,O2]])
    (implicit F: Catchable[F2]): Process[F2, O2 \/ O] =
    attempt(err => f.lift(err).getOrElse(fail(err)))


  /**
   * Run this process until it halts, then run it again and again, as
   * long as no errors or `Kill` occur.
   */
  final def repeat: Process[F, O] = this.append(this.repeat)

  /**
   * For anly process terminating with `Kill`, this swallows the `Kill` and replaces it with `End` termination
   */
  final def swallowKill: Process[F,O] =
    this.onHalt {
      case Kill | End => halt
      case cause => Halt(cause)
    }

  /** Translate the request type from `F` to `G`, using the given polymorphic function. */
  def translate[G[_]](f: F ~> G): Process[G,O] =
    this.suspendStep.flatMap {
      case Step(Emit(os),cont) => emitAll(os) +: cont.extend(_.translate(f))
      case Step(Await(req,rcv,cln),cont) =>
        Await[G,Any,O](f(req), r => {
          Trampoline.suspend(rcv(r)).map(_ translate f)
        }, cln.andThen(_.map(_.translate(f)))) +: cont.extend(_.translate(f))
      case hlt@Halt(rsn) => hlt
    }

  /**
   * Remove any leading emitted values from this `Process`.
   */
  @tailrec
  final def trim: Process[F,O] =
    this.step match {
      case Step(Emit(_), cont) => cont.continue.trim
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
        case Step(Emit(os),cont) => go(cont.continue, acc fast_++ os)
        case Step(awt, cont) => (acc,awt +: cont)
        case Halt(rsn) => (acc,Halt(rsn))
      }
    }
    go(this, Vector())

  }

  final def uncons[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[(O2, Process[F2, O2])] =
    unconsOption(F, C).flatMap(_.map(F.point[(O2, Process[F2, O2])](_)).getOrElse(C.fail(new NoSuchElementException)))

  final def unconsOption[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[Option[(O2, Process[F2, O2])]] = step match {
    case Step(head, next) => head match {
      case Emit(as) => as.headOption.map(x => F.point[Option[(O2, Process[F2, O2])]](Some((x, Process.emitAll[O2](as drop 1) +: next)))) getOrElse
          next.continue.unconsOption
      case await: Await[F2, _, O2] => await.evaluate.flatMap(p => (p +: next).unconsOption(F,C))
    }
    case Halt(cause) => cause match {
      case End | Kill => F.point(None)
      case _ : EarlyCause => C.fail(cause.asThrowable)
    }
  }

  ///////////////////////////////////////////
  //
  // Interpreters, runXXX
  //
  ///////////////////////////////////////////

  /**
   * Collect the outputs of this `Process[F,O]` into a Monoid `B`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  final def runFoldMap[F2[x] >: F[x], B](f: O => B)(implicit F: Monad[F2], C: Catchable[F2], B: Monoid[B]): F2[B] = {
    def go(cur: Process[F2, O], acc: B): F2[B] = {
      cur.step match {
        case s: Step[F2,O]@unchecked =>
          (s.head, s.next) match {
            case (Emit(os), cont) =>
              F.bind(F.point(os.foldLeft(acc)((b, o) => B.append(b, f(o))))) { nacc =>
                go(cont.continue.asInstanceOf[Process[F2,O]], nacc)
              }
            case (awt:Await[F2,Any,O]@unchecked, cont) =>
              awt.evaluate.flatMap(p => go(p +: cont, acc))
          }
        case Halt(End) => F.point(acc)
        case Halt(Kill) => F.point(acc)
        case Halt(Error(rsn)) => C.fail(rsn)
      }
    }

    go(this, B.zero)
  }


  /**
   * Collect the outputs of this `Process[F,O]`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  final def runLog[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[Vector[O2]] = {
    runFoldMap[F2, Vector[O2]](Vector(_))(
      F, C,
      // workaround for performance bug in Vector ++
      Monoid.instance[Vector[O2]]((a, b) => a fast_++ b, Vector())
    )
  }

  /** Run this `Process` solely for its final emitted value, if one exists. */
  final def runLast[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[Option[O2]] = {
    implicit val lastOpt = new Monoid[Option[O2]] {
      def zero = None
      def append(left: Option[O2], right: => Option[O2]) = right orElse left      // bias toward the end
    }

    this.last.runFoldMap[F2, Option[O2]]({ Some(_) })
  }

  /** Run this `Process` solely for its final emitted value, if one exists, using `o2` otherwise. */
  final def runLastOr[F2[x] >: F[x], O2 >: O](o2: => O2)(implicit F: Monad[F2], C: Catchable[F2]): F2[O2] =
    runLast[F2, O2] map { _ getOrElse o2 }

  /** Run this `Process`, purely for its effects. */
  final def run[F2[x] >: F[x]](implicit F: Monad[F2], C: Catchable[F2]): F2[Unit] =
    F.void(drain.runLog(F, C))

}


object Process extends ProcessInstances {


  import scalaz.stream.Util._

  //////////////////////////////////////////////////////////////////////////////////////
  //
  // Algebra
  //
  /////////////////////////////////////////////////////////////////////////////////////

  type Trampoline[+A] = scalaz.Free.Trampoline[A] @uncheckedVariance
  val Trampoline = scalaz.Trampoline

  /**
   * Tags a state of process that has no appended tail, tha means can be Halt, Emit or Await
   */
  sealed trait HaltEmitOrAwait[+F[_], +O] extends Process[F, O]

  object HaltEmitOrAwait {

    def unapply[F[_], O](p: Process[F, O]): Option[HaltEmitOrAwait[F, O]] = p match {
      case emit: Emit[O@unchecked] => Some(emit)
      case halt: Halt => Some(halt)
      case aw: Await[F@unchecked, _, O@unchecked] => Some(aw)
      case _ => None
    }

  }

  /**
   * Marker trait representing process in Emit or Await state.
   * Is useful for more type safety.
   */
  sealed trait EmitOrAwait[+F[_], +O] extends Process[F, O]


  /**
   * The `Halt` constructor instructs the driver
   * that the last evaluation of Process completed with
   * supplied cause.
   */
  case class Halt(cause: Cause) extends HaltEmitOrAwait[Nothing, Nothing] with HaltOrStep[Nothing, Nothing]


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
  case class Emit[+O](seq: Seq[O]) extends HaltEmitOrAwait[Nothing, O] with EmitOrAwait[Nothing, O]

  /**
   * The `Await` constructor instructs the driver to evaluate
   * `req`. If it returns successfully, `recv` is called with result on right side
   * to transition to the next state.
   *
   * In case the req terminates with failure the `Error(failure)` is passed on left side
   * giving chance for any fallback action.
   *
   * In case the process was killed before the request is evaluated `Kill` is passed on left side.
   * `Kill` is passed on left side as well as when the request is already in progress, but process was killed.
   *
   * The `preempt` parameter is used when constructing resource and preemption safe cleanups.
   * See `Process.bracket` for more.
   *
   * Note that
   *
   * Instead of this constructor directly, please use:
   *
   * Process.await or Process.bracket
   *
   */
  case class Await[+F[_], A, +O](
    req: F[A]
    , rcv: (EarlyCause \/ A) => Trampoline[Process[F, O]] @uncheckedVariance
    , preempt : A => Trampoline[Process[F,Nothing]] @uncheckedVariance = (_:A) => Trampoline.delay(halt:Process[F,Nothing])
    ) extends HaltEmitOrAwait[F, O] with EmitOrAwait[F, O] {
    /**
     * Helper to modify the result of `rcv` parameter of await stack-safely on trampoline.
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Await[F2, A, O2] =
      Await[F2, A, O2](req, r => Trampoline.suspend(rcv(r)).map(f), preempt)

    def evaluate[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[Process[F2,O2]] =
      C.attempt(req).map { e =>
        rcv(EarlyCause.fromTaskResult(e)).run
      }
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
    , stack: Vector[Cause => Trampoline[Process[F, O]]] @uncheckedVariance
    ) extends Process[F, O] {

    /**
     * Helper to modify the head and appended processes
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Process[F2, O2] = {
      val ms = stack.map(n => (cause: Cause) => Trampoline.suspend(n(cause)).map(f))

      f(head) match {
        case HaltEmitOrAwait(p) => Append(p, ms)
        case app: Append[F2@unchecked, O2@unchecked] => Append(app.head, app.stack fast_++ ms)
      }

    }

  }

  /**
   * Marker trait representing next step of process or terminated process in `Halt`
   */
  sealed trait HaltOrStep[+F[_], +O]

  /**
   * Intermediate step of process.
   * Used to step within the process to define complex combinators.
   */
  case class Step[+F[_], +O](head: EmitOrAwait[F, O], next: Cont[F, O]) extends HaltOrStep[F, O] {
    def toProcess : Process[F,O] = Append(head.asInstanceOf[HaltEmitOrAwait[F,O]],next.stack)
  }

  /**
   * Continuation of the process. Represents process _stack_. Used in conjunction with `Step`.
   */
  case class Cont[+F[_], +O](stack: Vector[Cause => Trampoline[Process[F, O]]] @uncheckedVariance) {

    /**
     * Prepends supplied process to this stack
     */
    def +:[F2[x] >: F[x], O2 >: O](p: Process[F2, O2]): Process[F2, O2] = prepend(p)

    /** alias for +: */
    def prepend[F2[x] >: F[x], O2 >: O](p: Process[F2, O2]): Process[F2, O2] = {
      if (stack.isEmpty) p
      else p match {
        case app: Append[F2@unchecked, O2@unchecked] => Append[F2, O2](app.head, app.stack fast_++ stack)
        case emt: Emit[O2@unchecked] => Append(emt, stack)
        case awt: Await[F2@unchecked, _, O2@unchecked] => Append(awt, stack)
        case hlt@Halt(_) => Append(hlt, stack)
      }
    }

    /**
     * Converts this stack to process, that is used
     * when following process with normal termination.
     */
    def continue: Process[F, O] = prepend(halt)

    /**
     * Applies transformation function `f` to all frames of this stack.
     */
    def extend[F2[_], O2](f: Process[F, O] => Process[F2, O2]): Cont[F2, O2] =
      Cont(stack.map(tf => (cause: Cause) => Trampoline.suspend(tf(cause).map(f))))


    /**
     * Returns true, when this continuation is empty, i.e. no more appends to process
     */
    def isEmpty : Boolean = stack.isEmpty

  }

  object Cont {
    /** empty continuation, that means evaluation is at end **/
    val empty:Cont[Nothing,Nothing] = Cont(Vector.empty)
  }


  ///////////////////////////////////////////////////////////////////////////////////////
  //
  // CONSTRUCTORS
  //
  //////////////////////////////////////////////////////////////////////////////////////

  /** Alias for emitAll */
  def apply[O](o: O*): Process0[O] = emitAll(o)

  /**
   * Await the given `F` request and use its result.
   * If you need to specify fallback, use `awaitOr`
   */
  def await[F[_], A, O](req: F[A])(rcv: A => Process[F, O]): Process[F, O] =
    awaitOr(req)(Halt.apply)(rcv)


  /**
   * Await a request, and if it fails, use `fb` to determine the next state.
   * Otherwise, use `rcv` to determine the next state.
   */
  def awaitOr[F[_], A, O](req: F[A])(fb: EarlyCause => Process[F, O])(rcv: A => Process[F, O]): Process[F, O] =
    Await(req,(r: EarlyCause \/ A) => Trampoline.delay(Try(r.fold(fb,rcv))))


  /** The `Process1` which awaits a single input, emits it, then halts normally. */
  def await1[I]: Process1[I, I] =
    receive1(emit)

  /** `Writer` based version of `await1`. */
  def await1W[A]: Writer1[Nothing, A, A] =
    writer.liftO(Process.await1[A])

  /** Like `await1`, but consults `fb` when await fails to receive an `I` */
  def await1Or[I](fb: => Process1[I, I]): Process1[I, I] =
    receive1Or(fb)(emit)

  /** The `Wye` which request from both branches concurrently. */
  def awaitBoth[I, I2]: Wye[I, I2, ReceiveY[I, I2]] =
    await(Both[I, I2])(emit)

  /** `Writer` based version of `awaitBoth`. */
  def awaitBothW[I, I2]: WyeW[Nothing, I, I2, ReceiveY[I, I2]] =
    writer.liftO(Process.awaitBoth[I, I2])

  /** The `Tee` which requests from the left branch, emits this value, then halts. */
  def awaitL[I]: Tee[I, Any, I] =
    await(L[I])(emit)

  /** `Writer` based version of `awaitL`. */
  def awaitLW[I]: TeeW[Nothing, I, Any, I] =
    writer.liftO(Process.awaitL[I])

  /** The `Tee` which requests from the right branch, emits this value, then halts. */
  def awaitR[I2]: Tee[Any, I2, I2] =
    await(R[I2])(emit)

  /** `Writer` based version of `awaitR`. */
  def awaitRW[I2]: TeeW[Nothing, Any, I2, I2] =
    writer.liftO(Process.awaitR[I2])


  /**
   * Resource and preemption safe `await` constructor.
   *
   * Use this combinator, when acquiring resources. This build a process that when run
   * evaluates `req`, and then runs `rcv`. Once `rcv` is completed, fails, or is interrupted, it will run `release`
   *
   * When the acquisition (`req`) is interrupted, neither `release` or `rcv` is run, however when the req was interrupted after
   * resource in `req` was acquired then, the `release` is run.
   *
   * If,the acquisition fails, use `bracket(req)(onPreempt)(rcv).onFailure(err => ???)` code to recover from the
   * failure eventually.
   *
   */
  def bracket[F[_], A, O](req: F[A])(release: A => Process[F, Nothing])(rcv: A => Process[F, O]): Process[F, O] = {
    Await(req,
    { (r: EarlyCause \/ A) => Trampoline.delay(Try(r.fold(Halt.apply, a => rcv(a) onComplete release(a) ))) },
    { a: A => Trampoline.delay(release(a)) })
  }


  /**
   * The infinite `Process`, always emits `a`.
   * If for performance reasons it is good to emit `a` in chunks,
   * specify size of chunk by `chunkSize` parameter
   */
  def constant[A](a: A, chunkSize: Int = 1): Process0[A] = {
    lazy val go: Process0[A] =
      if (chunkSize.max(1) == 1) emit(a) ++ go
      else emitAll(List.fill(chunkSize)(a)) ++ go
    go
  }

  /** The `Process` which emits the single value given, then halts. */
  def emit[O](o: O): Process0[O] = Emit(Vector(o))

  /** The `Process` which emits the given sequence of values, then halts. */
  def emitAll[O](os: Seq[O]): Process0[O] = Emit(os)

  /** A `Writer` which emits one value to the output. */
  def emitO[O](o: O): Process0[Nothing \/ O] =
    emit(right(o))

  /** A `Writer` which writes the given value. */
  def emitW[W](s: W): Process0[W \/ Nothing] =
    emit(left(s))

  /** The `Process` which emits no values and halts immediately with the given exception. */
  def fail(rsn: Throwable): Process0[Nothing] = Halt(Error(rsn))

  /** A `Process` which emits `n` repetitions of `a`. */
  def fill[A](n: Int)(a: A, chunkSize: Int = 1): Process0[A] = {
    val chunkN = chunkSize max 1
    val chunk = emitAll(List.fill(chunkN)(a)) // we can reuse this for each step
    def go(m: Int): Process0[A] =
      if (m >= chunkN) chunk ++ go(m - chunkN)
      else if (m <= 0) halt
      else emitAll(List.fill(m)(a))
    go(n max 0)
  }

  /**
   * Produce a continuous stream from a discrete stream by using the
   * most recent value.
   */
  def forwardFill[A](p: Process[Task, A])(implicit S: Strategy): Process[Task, A] =
    async.toSignal(p).continuous

  /** `halt` but with precise type. */
  private[stream] val halt0: Halt = Halt(End)

  /** The `Process` which emits no values and signals normal termination. */
  val halt: Process0[Nothing] = halt0

  /** Alias for `halt`. */
  def empty[F[_],O]: Process[F, O] = halt

  /**
   * An infinite `Process` that repeatedly applies a given function
   * to a start value. `start` is the first value emitted, followed
   * by `f(start)`, then `f(f(start))`, and so on.
   */
  def iterate[A](start: A)(f: A => A): Process0[A] =
    emit(start) ++ iterate(f(start))(f)

  /**
   * Like [[iterate]], but takes an effectful function for producing
   * the next state. `start` is the first value emitted.
   */
  def iterateEval[F[_], A](start: A)(f: A => F[A]): Process[F, A] =
    emit(start) ++ await(f(start))(iterateEval(_)(f))

  /** Lazily produce the range `[start, stopExclusive)`. If you want to produce the sequence in one chunk, instead of lazily, use `emitAll(start until stopExclusive)`.  */
  def range(start: Int, stopExclusive: Int, by: Int = 1): Process0[Int] =
    unfold(start)(i => if (i < stopExclusive) Some((i, i + by)) else None)

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
  def ranges(start: Int, stopExclusive: Int, size: Int): Process0[(Int, Int)] = {
    require(size > 0, "size must be > 0, was: " + size)
    unfold(start){
      lower =>
        if (lower < stopExclusive)
          Some((lower -> ((lower+size) min stopExclusive), lower+size))
        else
          None
    }
  }

  /**
   * The `Process1` which awaits a single input and passes it to `rcv` to
   * determine the next state.
   */
  def receive1[I, O](rcv: I => Process1[I, O]): Process1[I, O] =
    await(Get[I])(rcv)

  /** Like `receive1`, but consults `fb` when it fails to receive an input. */
  def receive1Or[I, O](fb: => Process1[I, O])(rcv: I => Process1[I, O]): Process1[I, O] =
    awaitOr(Get[I])((rsn: EarlyCause) => fb.causedBy(rsn))(rcv)

  /**
   * Delay running `p` until `awaken` becomes true for the first time.
   * The `awaken` process may be discrete.
   */
  def sleepUntil[F[_], A](awaken: Process[F, Boolean])(p: Process[F, A]): Process[F, A] =
    awaken.dropWhile(!_).once.flatMap(_ => p)

  /**
   * A supply of `Long` values, starting with `initial`.
   * Each read is guaranteed to return a value which is unique
   * across all threads reading from this `supply`.
   */
  def supply(initial: Long): Process[Task, Long] = {
    import java.util.concurrent.atomic.AtomicLong
    val l = new AtomicLong(initial)
    repeatEval { Task.delay { l.getAndIncrement }}
  }

  /** A `Writer` which writes the given value; alias for `emitW`. */
  def tell[S](s: S): Process0[S \/ Nothing] =
    emitW(s)

  /** Produce a (potentially infinite) source from an unfold. */
  def unfold[S, A](s0: S)(f: S => Option[(A, S)]): Process0[A] = {
    def go(s: S): Process0[A] =
      f(s) match {
        case Some((a, sn)) => emit(a) ++ go(sn)
        case None => halt
      }
    suspend(go(s0))
  }

  /** Like [[unfold]], but takes an effectful function. */
  def unfoldEval[F[_], S, A](s0: S)(f: S => F[Option[(A, S)]]): Process[F, A] = {
    def go(s: S): Process[F, A] =
      await(f(s)) {
        case Some((a, sn)) => emit(a) ++ go(sn)
        case None => halt
      }
    suspend(go(s0))
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

  /** Adds syntax for `Channel`. */
  implicit def toChannelSyntax[F[_], I, O](self: Channel[F, I, O]): ChannelSyntax[F, I, O] =
    new ChannelSyntax(self)

  /** Adds syntax for `Process1`. */
  implicit def toProcess1Syntax[I, O](self: Process1[I, O]): Process1Syntax[I, O] =
    new Process1Syntax(self)

  /** Adds syntax for `Sink`. */
  implicit def toSinkSyntax[F[_], I](self: Sink[F, I]): SinkSyntax[F, I] =
    new SinkSyntax(self)

  /** Adds syntax for `Sink` that is specialized for Task. */
  implicit def toSinkTaskSyntax[F[_], I](self: Sink[Task, I]): SinkTaskSyntax[I] =
    new SinkTaskSyntax(self)

  /** Adds syntax for `Tee`. */
  implicit def toTeeSyntax[I, I2, O](self: Tee[I, I2, O]): TeeSyntax[I, I2, O] =
    new TeeSyntax(self)

  /** Adds syntax for `Writer`. */
  implicit def toWriterSyntax[F[_], W, O](self: Writer[F, W, O]): WriterSyntax[F, W, O] =
    new WriterSyntax(self)

  /** Adds syntax for `Writer` that is specialized for Task. */
  implicit def toWriterTaskSyntax[W, O](self: Writer[Task, W, O]): WriterTaskSyntax[W, O] =
    new WriterTaskSyntax(self)

  /** Adds syntax for `Wye`. */
  implicit def toWyeSyntax[I, I2, O](self: Wye[I, I2, O]): WyeSyntax[I, I2, O] =
    new WyeSyntax(self)


  implicit class ProcessSyntax[F[_],O](val self: Process[F,O]) extends AnyVal {
    /** Feed this `Process` through the given effectful `Channel`. */
    def through[F2[x]>:F[x],O2](f: Channel[F2,O,O2]): Process[F2,O2] =
      self.zipWith(f)((o,f) => f(o)).eval onHalt { _.asHalt }     // very gross; I don't like this, but not sure what to do

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
      self.zipWith(f)((o,f) => (o,f(o))) flatMap { case (orig,action) => eval(action).drain ++ emit(orig) } onHalt { _.asHalt }

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
     */
    def eval: Process[F, O] = self flatMap { await(_)(emit) }

    /**
     * Read chunks of `bufSize` from input, then use `Nondeterminism.gatherUnordered`
     * to run all these actions to completion.
     */
    def gather(bufSize: Int)(implicit F: Nondeterminism[F]): Process[F,O] =
      self.pipe(process1.chunk(bufSize)).map(F.gatherUnordered).eval.flatMap(emitAll)

    /**
     * Read chunks of `bufSize` from input, then use `Nondeterminism.gather`
     * to run all these actions to completion and return elements in order.
     */
    def sequence(bufSize: Int)(implicit F: Nondeterminism[F]): Process[F,O] =
      self.pipe(process1.chunk(bufSize)).map(F.gather).eval.flatMap(emitAll)
  }

  /**
   * This class provides infix syntax specific to `Process0`.
   */
  implicit class Process0Syntax[O](val self: Process0[O]) extends AnyVal {

    /** Converts this `Process0` to a `Vector`. */
    def toVector: Vector[O] =
      self.unemit match {
        case (_, Halt(Error(rsn))) => throw rsn
        case (os, _) => os.toVector
      }

    /** Converts this `Process0` to an `IndexedSeq`. */
    def toIndexedSeq: IndexedSeq[O] = toVector

    /** Converts this `Process0` to a `List`. */
    def toList: List[O] = toVector.toList

    /** Converts this `Process0` to a `Seq`. */
    def toSeq: Seq[O] = toVector

    /** Converts this `Process0` to a `Stream`. */
    def toStream: Stream[O] = {
      def go(p: Process0[O]): Stream[O] =
        p.step match {
          case s: Step[Nothing, O] =>
            s.head match {
              case Emit(os) => os.toStream #::: go(s.next.continue)
              case _ => sys.error("impossible")
            }
          case Halt(Error(rsn)) => throw rsn
          case Halt(_) => Stream.empty
        }
      go(self)
    }

    /** Converts this `Process0` to a `Map`. */
    def toMap[K, V](implicit isKV: O <:< (K, V)): Map[K, V] = toVector.toMap(isKV)

    /** Converts this `Process0` to a `SortedMap`. */
    def toSortedMap[K, V](implicit isKV: O <:< (K, V), ord: Ordering[K]): SortedMap[K, V] =
      SortedMap(toVector.asInstanceOf[Seq[(K, V)]]: _*)

    def toSource: Process[Task, O] = self

    @deprecated("liftIO is deprecated in favor of toSource. It will be removed in a future release.", "0.7")
    def liftIO: Process[Task, O] = self
  }

  /**
   * Syntax for processes that have its effects wrapped in Task
   */
  implicit class SourceSyntax[O](val self: Process[Task, O])   extends WyeOps[O] {

    /** converts process to signal **/
    def toSignal(implicit S:Strategy):Signal[O] =
      async.toSignal(self)

    /**
     * Produce a continuous stream from a discrete stream by using the
     * most recent value.
     */
    def forwardFill(implicit S: Strategy): Process[Task, O] =
      self.toSignal.continuous

    /**
     * Returns result of channel evaluation tupled with
     * original value passed to channel.
     **/
    def observeThrough[O2](ch: Channel[Task, O, O2]): Process[Task, (O, O2)] = {
      val observerCh = ch map { f =>
        o: O => f(o) map { o2 => o -> o2 }
      }
      self through observerCh
    }

    /**
     * Asynchronous stepping of this Process. Note that this method is not resource safe unless
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
     * always at least once. The second cleanup invocation in that case may run on different thread, asynchronously.
     *
     * Please note that this method is *not* intended for external use!  It is the `Await` analogue of `step`, which
     * is also an internal-use function.
     *
     * @param cb  result of the asynchronous evaluation of the process. Note that, the callback is never called
     *            on the right side, if the sequence is empty.
     * @param S  Strategy to use when evaluating the process. Note that `Strategy.Sequential` may cause SOE.
     * @return   Function to interrupt the evaluation
     */
    protected[stream] final def stepAsync(cb: Cause \/ (Seq[O], Cont[Task,O]) => Unit)(implicit S: Strategy): EarlyCause => Unit = {
      val allSteps = Task delay {
        /*
         * Represents the running state of the computation.  If we're running, then the interrupt
         * function *for our current step* will be on the left.  If we have been interrupted, then
         * the cause for that interrupt will be on the right.  These state transitions are made
         * atomically, such that it is *impossible* for a task to be running, never interrupted and
         * to have this value be a right.  If the value is a right, then either no task is running
         * or the running task has received an interrupt.
         */
        val interrupted = new AtomicReference[(() => Unit) \/ EarlyCause](-\/({ () => () }))

        /*
         * Produces the evaluation for a single step.  Generally, this function will be
         * invoked only once and return immediately.  In the case of an `Await`, we must
         * descend recursively into the resultant child.  Generally speaking, the recursion
         * should be extremely shallow, since it is uncommon to have a chain of nested
         * awaits of any significant length (usually they are punctuated by an `Emit`).
         */
        def go(p: Process[Task, O]): Task[EarlyCause => Unit] = Task delay {
          p.step match {
            case Halt(cause) =>
              (Task delay { S { cb(-\/(cause)) } }) >> (Task now { _: EarlyCause => () })

            case Step(Emit(os), cont) =>
              (Task delay { S { cb(\/-((os, cont))) } }) >> (Task now { _: EarlyCause => () })

            case Step(awt: Await[Task, a, O], cont) => {
              val Await(req, rcv, cln) = awt

              case class PreStepAbort(c: EarlyCause) extends RuntimeException

              def unpack(msg: Option[Throwable \/ a]): Option[Process[Task, O]] = msg map { r => Try(rcv(EarlyCause fromTaskResult r).run) }

              // throws an exception if we're already interrupted (caught in preStep check)
              def checkInterrupt(int: => (() => Unit)): Task[Unit] = Task delay {
                interrupted.get() match {
                  case ptr @ -\/(int2) => {
                    if (interrupted.compareAndSet(ptr, -\/(int)))
                      Task now (())
                    else
                      checkInterrupt(int)
                  }

                  case \/-(c) => Task fail PreStepAbort(c)
                }
              } join

              Task delay {
                // will be true when we have "committed" to either a mid-step OR exceptional/completed
                val barrier = new AtomicBoolean(false)

                // detects what completion/interrupt case we're in and factors out race conditions
                def handle(
                    // interrupted before the task started running; task never ran!
                    preStep: EarlyCause => Unit,
                    // interrupted *during* the task run; task is probably still running
                    midStep: EarlyCause => Unit,
                    // task finished running, but we were *previously* interrupted
                    postStep: (Process[Task, Nothing], EarlyCause) => Unit,
                    // task finished with an error, but was not interrupted
                    exceptional: Throwable => Unit,
                    // task finished with a value, no errors, no interrupts
                    completed: Process[Task, O] => Unit)(result: Option[Throwable \/ a]): Unit = result match {

                  // interrupted via the `Task.fail` defined in `checkInterrupt`
                  case Some(-\/(PreStepAbort(cause: EarlyCause))) => preStep(cause)

                  case result => {
                    val inter = interrupted.get().toOption

                    assert(!inter.isEmpty || result.isDefined)

                    // interrupted via the callback mechanism, checked in `completeInterruptibly`
                    // always matches to a `None` (we don't have a value yet)
                    inter filter { _ => !result.isDefined } match {
                      case Some(cause) => {
                        if (barrier.compareAndSet(false, true)) {
                          midStep(cause)
                        } else {
                          // task already completed *successfully*, pretend we weren't interrupted at all
                          // our *next* step (which is already running) will get a pre-step interrupt
                          ()
                        }
                      }

                      case None => {
                        // completed the task, `interrupted.get()` is defined, and so we were interrupted post-completion
                        // always matches to a `Some` (we always have value)
                        val pc = for {
                          cause <- inter
                          either <- result
                        } yield {
                          either match {
                            case -\/(t) => ()       // I guess we just swallow the exception here? no idea what to do, since we don't have a handler for this case
                            case \/-(r) => postStep(Try(cln(r).run), cause)     // produce the preemption handler, given the resulting resource
                          }
                        }

                        pc match {
                          case Some(back) => back

                          case None => {
                            if (barrier.compareAndSet(false, true)) {
                              result match {
                                // nominally completed the task, but with an exception
                                case Some(-\/(t)) => exceptional(t)

                                case result => {
                                  // completed the task, no interrupts, no exceptions, good to go!
                                  unpack(result) match {
                                    case Some(head) => completed(head +: cont)

                                    case None => ???      // didn't match any condition; fail! (probably a double-None bug in completeInterruptibly)
                                  }
                                }
                              }
                            } else {
                              result match {
                                case Some(_) =>
                                  // we detected mid-step interrupt; this needs to transmute to post-step; loop back to the top!
                                  handle(preStep = preStep, midStep = midStep, postStep = postStep, exceptional = exceptional, completed = completed)(result)

                                case None => ???        // wtf?! (apparently we were called twice with None; bug in completeInterruptibly)
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }

                /*
                 * Start the task. per the `completeInterruptibly` invariants, the callback will be invoked exactly once
                 * unless interrupted in the final computation step, in which case it will be invoked twice: once with
                 * the interrupt signal and once with the final computed result (this case is detected by the `PostStep`)
                 * extractor.  Under all other circumstances, including interrupts, exceptions and natural completion, the
                 * callback will be invoked exactly once.
                 */
                lazy val interrupt: () => Unit = completeInterruptibly((checkInterrupt(interrupt) >> req).get)(handle(
                  preStep = { cause =>
                    // interrupted; now drain
                    (Try(rcv(-\/(cause)).run) +: cont).drain.run runAsync {
                      case -\/(t) => S { cb(-\/(Error(t) causedBy cause)) }
                      case \/-(_) => S { cb(-\/(cause)) }
                    }
                  },

                  midStep = { cause =>
                    // interrupted; now drain
                    (Try(rcv(-\/(cause)).run) +: cont).drain.run runAsync {
                      case -\/(t) => S { cb(-\/(Error(t) causedBy cause)) }
                      case \/-(_) => S { cb(-\/(cause)) }
                    }
                  },

                  postStep = { (inner, _) =>
                    inner.run runAsync { _ => () }      // invoke the cleanup
                  },

                  exceptional = { t =>
                    // we got an exception (not an interrupt!) and we need to drain everything
                    go(Try(rcv(-\/(Error(t))).run) +: cont) runAsync { _ => () }
                  },

                  completed = { continuation =>
                    go(continuation) runAsync { _ => () }
                  }
                ))

                interrupt     // please don't delete this!  highly mutable code within

                // interrupts the current step (may be a recursive child!) and sets `interrupted`
                def referencedInterrupt(cause: EarlyCause): Unit = {
                  interrupted.get() match {
                    case ptr @ -\/(int) => {
                      if (interrupted.compareAndSet(ptr, \/-(cause))) {
                        int()
                      } else {
                        referencedInterrupt(cause)
                      }
                    }

                    case \/-(_) => ()    // interrupted a second (or more) time; discard later causes and keep the first one
                  }
                }

                referencedInterrupt _
              }
            }
          }
        } join

        go _
      }

      allSteps flatMap { _(self) } run   // hey, we could totally return something sane here! what up?
    }

    /**
     * Analogous to Future#listenInterruptibly, but guarantees listener notification provided that the
     * body of any given computation step does not block indefinitely.  When the interrupt function is
     * invoked, the callback will be immediately invoked, either with an available completion value or
     * with None.  If the current step of the task ultimately completes with its *final* value (i.e.
     * the final step of the task is an Async and it starts before the interrupt and completes *afterwards*),
     * that value will be passed to the callback as a second return.  Thus, the callback will always be
     * invoked at least once, and may be invoked twice.  If it is invoked twice, the first callback
     * will always be None while the second will be Some.
     *
     *
     */
    private def completeInterruptibly[A](f: Future[A])(cb: Option[A] => Unit)(implicit S: Strategy): () => Unit = {
      import Future._

      val cancel = new AtomicBoolean(false)

      // `cb` is run exactly once or twice
      // Case A) `cb` is run with `None` followed by `Some` if we were cancelled but still obtained a value.
      // Case B) `cb` is run with just `Some` if it's never cancelled.
      // Case C) `cb` is run with just `None` if it's cancelled before a value is even attempted.
      // Case D) the same as case A, but in the opposite order, only in very rare cases
      lazy val actor: Actor[Option[Future[A]]] = new Actor[Option[Future[A]]]({
        // pure cases
        case Some(Suspend(thunk)) if !cancel.get() =>
          actor ! Some(thunk())

        case Some(BindSuspend(thunk, g)) if !cancel.get() =>
          actor ! Some(thunk() flatMap g)

        case Some(Now(a)) => S { cb(Some(a)) }

        case Some(Async(onFinish)) if !cancel.get() => {
          onFinish { a =>
            Trampoline delay { S { cb(Some(a)) } }
          }
        }

        case Some(BindAsync(onFinish, g)) if !cancel.get() => {
          onFinish { a =>
            if (!cancel.get()) {
              Trampoline delay { g(a) } map { r => actor ! Some(r) }
            } else {
              // here we drop `a` on the floor
              Trampoline done { () }  // `cb` already run with `None`
            }
          }
        }

        // fallthrough case where cancel.get() == true
        case Some(_) => ()  // `cb` already run with `None`

        case None => {
          cancel.set(true)  // the only place where `cancel` is set to `true`
          S { cb(None) }
        }
      })

      S { actor ! Some(f) }

      { () => actor ! None }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //
  // SYNTAX Functions
  //
  /////////////////////////////////////////////////////////////////////////////////////

  /**
   * Alias for await(fo)(emit)
   */
  def eval[F[_], O](fo: F[O]): Process[F, O] = await(fo)(emit)

  /**
   * Evaluate an arbitrary effect once, purely for its effects,
   * ignoring its return value. This `Process` emits no values.
   */
  def eval_[F[_], O](f: F[O]): Process[F, Nothing] =
    eval(f).drain

  /** Prefix syntax for `p.repeat`. */
  def repeat[F[_], O](p: Process[F, O]): Process[F, O] = p.repeat


  /**
   * Evaluate an arbitrary effect in a `Process`. The resulting `Process` will emit values
   * until an error occurs.
   *
   */
  def repeatEval[F[_], O](fo: F[O]): Process[F, O] = eval(fo).repeat

  /**
   * Produce `p` lazily. Useful if producing the process involves allocation of
   * some local mutable resource we want to ensure is freshly allocated
   * for each consumer of `p`.
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
   *    Halt(cause) ++ suspend(p) === Halt(cause) ++ p
   * }}}
   *
   */
  def suspend[F[_], O](p: => Process[F, O]): Process[F, O] =
    Append(halt0,Vector({
      case End => Trampoline.done(p)
      case early: EarlyCause => Trampoline.done(p.injectCause(early))
    }))
}

