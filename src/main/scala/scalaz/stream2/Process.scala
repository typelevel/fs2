package scalaz.stream2


import scala.Ordering
import scala.annotation.tailrec
import scala.collection.SortedMap
import scalaz.\/._
import scalaz._
import scalaz.concurrent.Task

sealed trait Process[+F[_], +O] {

  import Process._
  import Util._

  /**
   * Generate a `Process` dynamically for each output of this `Process`, and
   * sequence these processes using `append`.
   */
  final def flatMap[F2[x] >: F[x], O2](f: O => Process[F2, O2]): Process[F2, O2] = {
    debug(s"FM this:$this")
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
  final def pipe[O2](p1: Process1[O, O2]): Process[F, O2] = {
    p1 step match {
      case (awt1@AwaitP1(rcv1), t1) => this.step match {
        case (awt@Await(req, rcv), t) => awt.extend(_.pipe(p1))
        case (emt@Emit(so), t)        => this.pipe(process1.feed(so)(p1))
        case (hlt@Halt(rsn), _)       => ??? //this.pipe(t1.killBy(rsn)) as we now that tail is always halt in this case

      }
      case (emt1@Emit(so2), t1)     => Emit(so2) ++ this.pipe(t1)
      case (hlt1@Halt(rsn), _)     => ??? //this.killBy(rsn).pipe(p1) as we know tail is always halt in this case
    }




    //    def go(cur: Process[F, O]
    //      , cur1: Process1[O, O2]
    //      , stack: Vector[Throwable => Trampoline[Process[F, O]]]
    //      , stack1: Vector[Throwable => Trampoline[Process1[O, O2]]]): Process[F, O2] = {
    //      cur1 match {
    //        case hlt@Halt(rsn)
    //          if stack1.isEmpty => stack.headOption match {
    //          case Some(head) => go(Try(head(rsn).run), cur1, stack.tail, stack1)
    //          case None => hlt
    //        }
    //        case Halt(rsn)                       => go(cur, Try(stack1.head(rsn).run), stack, stack1.tail)
    //        case emit@Emit(_) =>  emit onHalt { rsn => go(cur, Halt(rsn), stack, stack1) }
    //        case Append(p, n) => go(cur, p, stack, n fast_++ stack1)
    //        case aw@AwaitP1(rcv1) =>
    //          cur match {
    //            case hlt@Halt(rsn)
    //              if stack.isEmpty   => stack1.headOption match {
    //              case Some(head1) => go(hlt, Try(head1(rsn).run), stack, stack1.tail)
    //              case None     => hlt
    //            }
    //            case Halt(rsn)       => go(Try(stack.head(rsn).run), cur1, stack.tail, stack1)
    //            case Emit(Seq())     => go(halt, cur1, stack, stack1.tail)
    //            case Emit(os)        => go(Emit(os.tail), Try(rcv1(os.head)), stack, stack1)
    //            case aw@Await(_, _)  => aw.extend(go(_, cur1, stack, stack1))
    //            case ap@Append(p, n) => go(p, cur1, n fast_++ stack, stack1)
    //          }
    //
    //      }
    //
    //    }
    //
    //
    //    go(this, p2, Vector(), Vector())

  }

  /** Operator alias for `pipe`. */
  final def |>[O2](p2: Process1[O, O2]): Process[F, O2] = pipe(p2)


  /**
   * Evaluate this process and produce tuple of `Step` and continuations
   * that shall be run after the Step.
   *
   * Note this evaluation is not resource safe, and user must assure resource safety
   */
  final def step: (Step[F, O], Process[F, O]) = {
    @tailrec
    def go(cur: Process[F, O], stack: Vector[Throwable => Trampoline[Process[F, O]]])
    : (Step[F, O], Process[F, O]) = cur match {
      case Append(p, n)        => go(p, n fast_++ stack)
      case s:Step[F@unchecked,O@unchecked] =>
        if (stack.isEmpty) (s,halt)
        else s match {
          case  hlt@Halt(rsn) => go(Try(stack.head(rsn).run), stack.tail)
          case emt@Emit(_) => (emt, Append(halt, stack))
          case awt@Await(_, _) => (awt.extend(_ ++ Append(halt, stack)), Append(halt, stack))
        }
    }

    go(this, Vector())
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
      case rsn => halt
    }
  }

  /** alias for `append` **/
  final def ++[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = append(p2)

  /**
   * Run this `Process`, then, if it self-terminates, run `p2`.
   * This differs from `append` in that `p2` is not consulted if this
   * `Process` terminates due to the input being exhausted.
   * That is if the Await terminated with an End exception, `p2` is not appended.
   */
  final def fby[F2[x] >: F[x], O2 >: O](p2: => Process[F2, O2]): Process[F2, O2] = ???

  /**
   * Add `e` as a cause when this `Process` halts.
   * This is a no-op  if `e` is `Process.End`.
   */
  final def causedBy[F2[x] >: F[x], O2 >: O](e: Throwable): Process[F2, O2] = {
    onHalt {
      case rsn => e match {
        case End => fail(rsn)
        case _   => fail(CausedBy(rsn, e))
      }
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
    onHalt { _ => Try(p2) }

  /**
   * When the process terminates either due to `End` or `Throwable`
   * `f` is evaluated to produce next state.
   */
  final def onHalt[F2[x] >: F[x], O2 >: O](f: Throwable => Process[F2, O2]): Process[F2, O2] = {
    val next = (t: Throwable) => Trampoline.delay(Try(f(t)))
    this match {
      case Append(p, n)   => Append(p, n :+ next)
      case Step(p) => Append(p, Vector(next))
    }
  }

  /**
   * Append process specified in `fallback` argument in case the _next_ Await throws an End,
   * and appned process specified in `cleanup` argument in case _next_ Await throws any other exception
   */
  final def orElse[F2[x] >: F[x], O2 >: O](fallback: => Process[F2, O2], cleanup: => Process[F2, O2] = halt): Process[F2, O2] =
    onHalt { case End => fallback; case _ => cleanup }


  /**
   * Run this process until it halts, then run it again and again, as
   * long as no errors occur.
   */
  final def repeat[F2[x] >: F[x], O2 >: O]: Process[F2, O2] = {
    //    this onHalt {
    //      case End => this.repeat
    //      case rsn => halt
    //    }
    //
    //    def go(cur: Process[F,O]): Process[F,O] = cur match {
    //      case h@Halt(e) => e match {
    //        case End => go(this)
    //        case _ => h
    //      }
    //      case Await(req,recv,fb,c) => Await(req, recv andThen go, fb, c)
    //      case Emit(h, t) => emitSeq(h, go(t))
    //    }
    //    go(this)
    ???
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
          F.bind(C.attempt(req.asInstanceOf[F2[AnyRef]])) {
            case \/-(r)   => go(Try(rcv(r).run.asInstanceOf[Process[F2, O]]), acc, stack, cause)
            case -\/(rsn) => go(fail(rsn), acc, stack, cause)
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


  ////////////////////////////////////////////////////////////////////////////////////////////////////
  // Process1 syntax helpers.
  /////////////////////////////////////////////////////////////////////////////////////////////////////

  /** Alias for `this |> [[process1.buffer]](n)`. */
  def buffer(n: Int): Process[F, O] =
    this |> process1.buffer(n)

  /** Alias for `this |> [[process1.bufferAll]]`. */
  def bufferAll: Process[F, O] =
    this |> process1.bufferAll

  /** Alias for `this |> [[process1.bufferBy]](f)`. */
  def bufferBy(f: O => Boolean): Process[F, O] =
    this |> process1.bufferBy(f)

  /** Alias for `this |> [[process1.chunk]](n)`. */
  def chunk(n: Int): Process[F, Vector[O]] =
    this |> process1.chunk(n)

  /** Alias for `this |> [[process1.chunkAll]]`. */
  def chunkAll: Process[F, Vector[O]] =
    this |> process1.chunkAll

  /** Alias for `this |> [[process1.chunkBy]](f)`. */
  def chunkBy(f: O => Boolean): Process[F, Vector[O]] =
    this |> process1.chunkBy(f)

  /** Alias for `this |> [[process1.chunkBy2]](f)`. */
  def chunkBy2(f: (O, O) => Boolean): Process[F, Vector[O]] =
    this |> process1.chunkBy2(f)

  /** Alias for `this |> [[process1.collect]](pf)`. */
  def collect[O2](pf: PartialFunction[O, O2]): Process[F, O2] =
    this |> process1.collect(pf)

  /** Alias for `this |> [[process1.collectFirst]](pf)`. */
  def collectFirst[O2](pf: PartialFunction[O, O2]): Process[F, O2] =
    this |> process1.collectFirst(pf)

  /** Alias for `this |> [[process1.drop]](n)`. */
  def drop(n: Int): Process[F, O] =
    this |> process1.drop[O](n)

  /** Alias for `this |> [[process1.dropLast]]`. */
  def dropLast: Process[F, O] =
    this |> process1.dropLast

  /** Alias for `this |> [[process1.dropLastIf]](p)`. */
  def dropLastIf(p: O => Boolean): Process[F, O] =
    this |> process1.dropLastIf(p)

  /** Alias for `this |> [[process1.dropWhile]](f)`. */
  def dropWhile(f: O => Boolean): Process[F, O] =
    this |> process1.dropWhile(f)

  /** Alias for `this |> [[process1.exists]](f)` */
  def exists(f: O => Boolean): Process[F, Boolean] =
    this |> process1.exists(f)

  /** Alias for `this |> [[process1.filter]](f)`. */
  def filter(f: O => Boolean): Process[F, O] =
    this |> process1.filter(f)

  /** Alias for `this |> [[process1.find]](f)` */
  def find(f: O => Boolean): Process[F, O] =
    this |> process1.find(f)

  /** Alias for `this |> [[process1.forall]](f)` */
  def forall(f: O => Boolean): Process[F, Boolean] =
    this |> process1.forall(f)

  /** Alias for `this |> [[process1.fold]](b)(f)`. */
  def fold[O2 >: O](b: O2)(f: (O2, O2) => O2): Process[F, O2] =
    this |> process1.fold(b)(f)

  /** Alias for `this |> [[process1.foldMap]](f)(M)`. */
  def foldMap[M](f: O => M)(implicit M: Monoid[M]): Process[F, M] =
    this |> process1.foldMap(f)(M)

  /** Alias for `this |> [[process1.foldMonoid]](M)` */
  def foldMonoid[O2 >: O](implicit M: Monoid[O2]): Process[F, O2] =
    this |> process1.foldMonoid(M)

  /** Alias for `this |> [[process1.foldSemigroup]](M)`. */
  def foldSemigroup[O2 >: O](implicit M: Semigroup[O2]): Process[F, O2] =
    this |> process1.foldSemigroup(M)

  /** Alias for `this |> [[process1.fold1]](f)`. */
  def fold1[O2 >: O](f: (O2, O2) => O2): Process[F, O2] =
    this |> process1.fold1(f)

  /** Alias for `this |> [[process1.fold1Map]](f)(M)`. */
  def fold1Map[M](f: O => M)(implicit M: Monoid[M]): Process[F, M] =
    this |> process1.fold1Map(f)(M)

  /** Alias for `this |> [[process1.fold1Monoid]](M)` */
  def fold1Monoid[O2 >: O](implicit M: Monoid[O2]): Process[F, O2] =
    this |> process1.fold1Monoid(M)

  /** Alias for `this |> [[process1.intersperse]](sep)`. */
  def intersperse[O2 >: O](sep: O2): Process[F, O2] =
    this |> process1.intersperse(sep)

  /** Alias for `this |> [[process1.last]]`. */
  def last: Process[F, O] =
    this |> process1.last

  /** Alias for `this |> [[process1.reduce]](f)`. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Process[F, O2] =
    this |> process1.reduce(f)

  /** Alias for `this |> [[process1.reduceMap]](f)(M)`. */
  def reduceMap[M](f: O => M)(implicit M: Semigroup[M]): Process[F, M] =
    this |> process1.reduceMap(f)(M)

  /** Alias for `this |> [[process1.reduceMonoid]](M)`. */
  def reduceMonoid[O2 >: O](implicit M: Monoid[O2]): Process[F, O2] =
    this |> process1.reduceMonoid(M)

  /** Alias for `this |> [[process1.reduceSemigroup]](M)`. */
  def reduceSemigroup[O2 >: O](implicit M: Semigroup[O2]): Process[F, O2] =
    this |> process1.reduceSemigroup(M)

  /** Alias for `this |> [[process1.repartition]](p)(S)` */
  def repartition[O2 >: O](p: O2 => IndexedSeq[O2])(implicit S: Semigroup[O2]): Process[F, O2] =
    this |> process1.repartition(p)(S)

  /** Alias for `this |> [[process1.scan]](b)(f)`. */
  def scan[B](b: B)(f: (B, O) => B): Process[F, B] =
    this |> process1.scan(b)(f)

  /** Alias for `this |> [[process1.scanMap]](f)(M)`. */
  def scanMap[M](f: O => M)(implicit M: Monoid[M]): Process[F, M] =
    this |> process1.scanMap(f)(M)

  /** Alias for `this |> [[process1.scanMonoid]](M)`. */
  def scanMonoid[O2 >: O](implicit M: Monoid[O2]): Process[F, O2] =
    this |> process1.scanMonoid(M)

  /** Alias for `this |> [[process1.scanSemigroup]](M)`. */
  def scanSemigroup[O2 >: O](implicit M: Semigroup[O2]): Process[F, O2] =
    this |> process1.scanSemigroup(M)

  /** Alias for `this |> [[process1.scan1]](f)`. */
  def scan1[O2 >: O](f: (O2, O2) => O2): Process[F, O2] =
    this |> process1.scan1(f)

  /** Alias for `this |> [[process1.scan1Map]](f)(M)`. */
  def scan1Map[M](f: O => M)(implicit M: Semigroup[M]): Process[F, M] =
    this |> process1.scan1Map(f)(M)

  /** Alias for `this |> [[process1.scan1Monoid]](M)`. */
  def scan1Monoid[O2 >: O](implicit M: Monoid[O2]): Process[F, O2] =
    this |> process1.scan1Monoid(M)

  /** Alias for `this |> [[process1.split]](f)` */
  def split(f: O => Boolean): Process[F, Vector[O]] =
    this |> process1.split(f)

  /** Alias for `this |> [[process1.splitOn]](p)` */
  def splitOn[P >: O](p: P)(implicit P: Equal[P]): Process[F, Vector[P]] =
    this |> process1.splitOn(p)

  /** Alias for `this |> [[process1.splitWith]](f)` */
  def splitWith(f: O => Boolean): Process[F, Vector[O]] =
    this |> process1.splitWith(f)

  /** Alias for `this |> [[process1.take]](n)`. */
  def take(n: Int): Process[F, O] =
    this |> process1.take[O](n)

  /** Alias for `this |> [[process1.takeWhile]](f)`. */
  def takeWhile(f: O => Boolean): Process[F, O] =
    this |> process1.takeWhile(f)

  /** Alias for `this |> [[process1.terminated]]`. */
  def terminated: Process[F, Option[O]] =
    this |> process1.terminated

  /** Alias for `this |> [[process1.window]](n)`. */
  def window(n: Int): Process[F, Vector[O]] =
    this |> process1.window(n)

  /** Halts this `Process` after emitting 1 element. */
  def once: Process[F, O] = take(1)

}


object Process {

  import Util._

  type Trampoline[+A] = scalaz.Free.Trampoline[A]
  val Trampoline = scalaz.Trampoline


  /**
   * Tags a state of process that has no appended tail, tha means can be Halt, Emit or Await
   */
  sealed trait Step[+F[_], +O] extends Process[F, O]

  object Step {

    def unapply[F[_], O](p: Process[F, O]): Option[Step[F, O]] = p match {
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
  case class Halt(rsn: Throwable) extends Step[Nothing, Nothing]

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
  case class Emit[+O](seq: Seq[O]) extends Step[Nothing, O]

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
    , rcv: A => Trampoline[Process[F, O]]
    ) extends Step[F, O] {
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
    head: Step[F, O]
    , tail: Vector[Throwable => Trampoline[Process[F, O]]]
    ) extends Process[F, O] {


    /**
     * Helper to modify the head and appended processes
     */
    def extend[F2[x] >: F[x], O2](f: Process[F, O] => Process[F2, O2]): Process[F2, O2] = {
      val et = tail.map(n => (t: Throwable) => Trampoline.suspend(n(t)).map(f))
      Try(f(head)) match {
        case Step(p)                         =>
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

  /** hepler to construct await for Process1 **/
  def await1[I]: Process1[I, I] =
    await(Get[I])(emit)

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
    Await[F, A, O](req, a => Trampoline.delay(rcv(a))) onHalt {
      case End => fallback
      case _   => cleanup
    }

  }

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
  }


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
   * Special exception indicating downstream termination.
   * An `Await` should respond to a `Kill` by performing
   * necessary cleanup actions, then halting with `End`
   */
  case object Kill extends Exception {
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
