package scalaz.stream2


import scala.Ordering
import scala.Some
import scala.annotation.tailrec
import scala.collection.SortedMap
import scalaz._
import scalaz.concurrent.Task

/**
 * Created by pach on 06/03/14.
 */
sealed trait Process[+F[_], +O] {

  import Process._
  import Util._

  final def flatMap[F2[x] >: F[x], O2](f: O => Process[F2, O2]): Process[F2, O2] = {
    debug(s"FM this:$this")
    this match {
      case Halt(_) | Emit(Seq()) => this.asInstanceOf[Process[F2,O2]]
      case Emit(os)                => os.tail.foldLeft(Try(f(os.head)))((p, n) => p append Try(f(n)))
      case aw@Await(_, _)          => aw.extend(_ flatMap f)
      case ap@Append(p, n)         => ap.extend(_ flatMap f)
    }

  }


  final def map[O2](f: O => O2): Process[F, O2] =
    flatMap { o => emit(f(o)) }


  ////////////////////////////////////////////////////////////////////////////////////////
  // Alphabetical order from now on
  ////////////////////////////////////////////////////////////////////////////////////////

  /**
   * If this process halts without an error, attaches `p2` as the next step.
   */
  final def append[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = {
    onHalt {
      case End => p2
      case rsn => halt
    }
  }

  /** alias for `append` **/
  final def ++[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = append(p2)


  /**
   * Add `e` as a cause when this `Process` halts.
   * This is a noop and returns immediately if `e` is `Process.End`.
   */
  final def causedBy[F2[x] >: F[x], O2 >: O](e: Throwable): Process[F2, O2] = {
    onHalt {
      case End => fail(e)
      case rsn => fail(CausedBy(rsn, e))
    }
  }

  /** Ignore all outputs of this `Process`. */
  final def drain: Process[F, Nothing] = {
    this match {
      case h@Halt(_)       => h
      case Emit(_)         => halt
      case aw@Await(_, _)  => aw.extend(_.drain)
      case ap@Append(p, n) => ap.extend(_.drain)
    }
  }

  /**
   * Run `p2` after this `Process` completes normally, or in the event of an error.
   * This behaves almost identically to `append`, except that `p1 append p2` will
   * not run `p2` if `p1` halts with an error.
   *
   * If you want to attach code that depends on reason of termination, use `onHalt`
   *
   */
  final def onComplete[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] =
    onHalt { _ => Try(p2) }

  /**
   * When the process terminates either due to `End` or `Throwable`
   * `f` is evaluated to produce next state.
   */
  final def onHalt[F2[x] >: F[x], O2 >: O](f: Throwable => Process[F2, O2]): Process[F2, O2] = {
    val next = (t: Throwable) => Trampoline.delay(Try(f(t)))
    this match {
      case Append(p, n)   => Append(p, n :+ next)
      case DoneOrAwait(p) => Append(p, Vector(next))
    }
  }

  ///////////////////////////////////////////
  // runXXX
  ///////////////////////////////////////////

  /**
   * Collect the outputs of this `Process[F,O]` into a monoid `B`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  final def runFoldMap[F2[x] >: F[x], B](f: O => B)(implicit F: Monad[F2], C: Catchable[F2], B: Monoid[B]): F2[B] = {
    def go(cur: Process[F2, O], acc: B, stack: Vector[Throwable => Trampoline[Process[F2, O]]], cause: Throwable): F2[B] = {
      cur match {
        case `halt` | Emit(Seq()) =>
          if (stack.isEmpty) cause match {
            case End => F.point(acc)
            case rsn => C.fail(rsn)
          }
          else go(Try(stack.head(cause).run), acc, stack.tail, cause)

        case Halt(rsn) =>
          val cause0 = CausedBy(rsn, cause)
          if (stack.isEmpty) C.fail(cause0)
          else go(Try(stack.head(cause0).run), acc, stack.tail, cause0)

        case Emit(os) =>
          def r = os.foldLeft(acc)((b, o) => B.append(b, f(o)))
          if (stack.isEmpty) F.point(r)
          else go(Try(stack.head(cause).run), r, stack.tail, cause)

        case Append(p, n) =>
          go(p.asInstanceOf[Process[F2, O]], acc, n.asInstanceOf[Vector[Throwable => Trampoline[Process[F2, O]]]] fast_++ stack, cause)

        case Await(req, rcv) =>
          F.bind(C.attempt(req.asInstanceOf[F2[AnyRef]])) { r =>
            go(Try(rcv(r).run.asInstanceOf[Process[F2, O]]), acc, stack, cause)
          }
      }
    }


    go(this, B.zero, Vector(), End)
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
    ??? // F.map(this.last.runLog[F2,O2])(_.lastOption)

  /** Run this `Process` solely for its final emitted value, if one exists, using `o2` otherwise. */
  final def runLastOr[F2[x] >: F[x], O2 >: O](o2: => O2)(implicit F: Monad[F2], C: Catchable[F2]): F2[O2] =
    ??? //  F.map(this.last.runLog[F2,O2])(_.lastOption.getOrElse(o2))

  /** Run this `Process`, purely for its effects. */
  final def run[F2[x] >: F[x]](implicit F: Monad[F2], C: Catchable[F2]): F2[Unit] =
    F.void(drain.runLog(F, C))


}


object Process {

  import Util._

  type Trampoline[+A] = scalaz.Free.Trampoline[A]
  val Trampoline = scalaz.Trampoline


  /**
   * Tags a state of process, that is either done or Await, but is not Append
   */
  sealed trait DoneOrAwait[+F[_], +O] extends Process[F, O]

  object DoneOrAwait {

    def unapply[F[_], O](p: Process[F, O]): Option[DoneOrAwait[F, O]] = p match {
      case emit: Emit[O@unchecked]                => Some(emit)
      case halt: Halt                             => Some(halt)
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
  case class Halt(rsn: Throwable) extends DoneOrAwait[Nothing, Nothing]

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
  case class Emit[+O](seq: Seq[O]) extends DoneOrAwait[Nothing, O]

  /**
   * The `Await` constructor instructs the driver to evaluate
   * `req`. If it returns successfully, `recv` is called
   * to transition to the next state.
   * In case the req terminates with either a failure (`Throwable`) or
   * an `End` indicating normal termination, these are passed to rcv on the left side,
   * to produce next state.
   *
   * Instead of this constructor directly, please use:
   *
   * Process.await
   *
   */
  case class Await[+F[_], A, +O](
    req: F[A]
    , rcv: (Throwable \/ A) => Trampoline[Process[F, O]]
    ) extends DoneOrAwait[F, O] {
    /**
     * Helper to modify the result of `rcv` parameter of await stack-safely on trampoline.
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Process[F2, O2] =
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
    head: DoneOrAwait[F, O]
    , tail: Vector[Throwable => Trampoline[Process[F, O]]]
    ) extends Process[F, O] {

    /**
     * Helper to modify the head and appended processes
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Process[F2, O2] = {
      val et = tail.map(n => (t: Throwable) => Trampoline.suspend(n(t)).map(f))
      Try(f(head)) match {
        case DoneOrAwait(p)                         =>
          Append(p, et)
        case ap: Append[F2@unchecked, O2@unchecked] =>
          Append[F2, O2](ap.head, ap.tail fast_++ et)
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

  /** Indicates termination with supplied reason **/
  def fail(rsn: Throwable): Process[Nothing, Nothing] = Halt(rsn)

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


  /** stack-safe constructor for await allowing to pass `req` as request and function to produce next state **/
  def await[F[_], A, O](req: F[A])(
    rcv: A => Process[F, O]
    , fallback: => Process[F, O] = halt
    , cleanup: => Process[F, O] = halt
    ): Process[F, O] = {
    Await[F, A, O](req, _.fold({
      case End => Trampoline.delay {
        val fb = fallback
        val cln = cleanup
        if (fb != halt || cln != halt) fb onComplete cln
        else halt
      }
      case rsn => Trampoline.delay(cleanup)
    }
    , r => Trampoline.delay(rcv(r))
    ))
  }

  //(r: Throwable \/ A) => Trampoline.delay(rcv(r))

  ///////////////////////////////////////////////////////////////////////////////////////
  //
  // CONSUTRUCTORS -> Helpers
  //
  //////////////////////////////////////////////////////////////////////////////////////

  /** `Process.emitRange(0,5) == Process(0,1,2,3,4).` */
  def emitRange(start: Int, stopExclusive: Int): Process[Nothing,Int] =
    emitAll(start until stopExclusive)

  /** Lazily produce the range `[start, stopExclusive)`. */
  def range(start: Int, stopExclusive: Int, by: Int = 1): Process[Task, Int] =
    unfold(start)(i => if (i < stopExclusive) Some((i,i+by)) else None)


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
   * Wrapper for Exception that was caused by other Exception during the
   * Execution of the Process
   */
  class CausedBy(e: Throwable, cause: Throwable) extends Exception(cause) {
    override def toString = s"$e\n\ncaused by:\n\n$cause"
  }

  object CausedBy {
    def apply(e: Throwable, cause: Throwable): Throwable =
      cause match {
        case End => e
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


  //////////////////////////////////////////////////////////////////////////////////////
  //
  // SYNTAX
  //
  /////////////////////////////////////////////////////////////////////////////////////

  /**
   * This class provides infix syntax specific to `Process0`.
   */
  implicit class Process0Syntax[O](self: Process0[O]) {
    def toIndexedSeq: IndexedSeq[O] = {
      @tailrec
      def go(cur: Process0[O], acc: Vector[O], stack: Vector[Throwable => Trampoline[Process0[O]]]): IndexedSeq[O] = {
        //debug(s"P0 cur: $cur, acc: $acc, stack: ${stack.size}")
        cur match {
          case Emit(os) =>
            val osacc = acc fast_++ os
            if (stack.isEmpty) osacc
            else go(Try(stack.head(End).run), osacc, stack.tail)
          case Append(p, t) =>
            //    debug(s"P0 APPEND p:$p, t:${t.size}, s:${stack.size}")
            go(p, acc, t fast_++ stack)
          case _            =>

            if (stack.isEmpty) acc
            else go(Try(stack.head(End).run), acc, stack.tail)
        }

      }
      go(self, Vector(), Vector())
    }
    def toList: List[O] = toIndexedSeq.toList
    def toSeq: Seq[O] = toIndexedSeq
    def toMap[K, V](implicit isKV: O <:< (K, V)): Map[K, V] = toIndexedSeq.toMap(isKV)
    def toSortedMap[K, V](implicit isKV: O <:< (K, V), ord: Ordering[K]): SortedMap[K, V] =
      SortedMap(toIndexedSeq.asInstanceOf[Seq[(K, V)]]: _*)
    def toStream: Stream[O] = toIndexedSeq.toStream
    def toSource: Process[Task, O] = ??? // emitSeq(toIndexedSeq.map(o => Task.delay(o))).eval
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
  def eval[F[_], O](t: F[O]): Process[F, O] =
    await(t)(emit)

  /**
   * Evaluate an arbitrary effect once, purely for its effects,
   * ignoring its return value. This `Process` emits no values.
   */
  def eval_[F[_], O](t: F[O]): Process[F, Nothing] =
    await(t)(_ => halt)


  /**
   * Produce `p` lazily, guarded by a single `Append`. Useful if
   * producing the process involves allocation of some mutable
   * resource we want to ensure is accessed in a single-threaded way.
   */
  def suspend[F[_], O](p: => Process[F, O]): Process[F, O] =
    halt append p


}
