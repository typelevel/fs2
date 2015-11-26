package scalaz.stream

import Cause._
import scala.annotation.tailrec
import scalaz.{\/-, \/}
import scalaz.\/._
import scalaz.stream.Process._
import scalaz.stream.Util._

object tee {

  /**
   * Alternate pulling from the left, then right,
   * repeatedly, starting on the left, and emitting only values
   * from the right. When the left is exhausted, behaves like `passR`.
   */
  def drainL[I]: Tee[Any, I, I] =
    awaitL[Any].awaitOption.flatMap {
      case None => passR
      case Some(_) => awaitR[I] ++ drainL
    }

  /**
   * Alternate pulling from the left, then right,
   * repeatedly, starting on the left, and emitting only values
   * from the left. When the right is exhausted, behaves like `passL`.
   */
  def drainR[I]: Tee[I, Any, I] =
    awaitL[I] ++ awaitR[Any].awaitOption.flatMap {
      case None => passL
      case Some(_) => drainR
    }

  /** A `Tee` which alternates between emitting values from the left input and the right input. */
  def interleave[I]: Tee[I, I, I] =
  repeat { for {
      i1 <- awaitL[I]
      i2 <- awaitR[I]
      r <- emit(i1) ++ emit(i2)
    } yield r }

  /** A `Tee` which ignores all input from left. */
  def passR[I2]: Tee[Any, I2, I2] = awaitR[I2].repeat

  /** A `Tee` which ignores all input from the right. */
  def passL[I]: Tee[I, Any, I] = awaitL[I].repeat

  /** Echoes the right branch until the left branch becomes `true`, then halts. */
  def until[I]: Tee[Boolean, I, I] =
    awaitL[Boolean].flatMap(kill => if (kill) halt else awaitR[I] ++ until)

  /** Echoes the right branch when the left branch is `true`. */
  def when[I]: Tee[Boolean, I, I] =
    awaitL[Boolean].flatMap(ok => if (ok) awaitR[I] ++ when else when)

  /** Defined as `zipWith((_,_))` */
  def zip[I, I2]: Tee[I, I2, (I, I2)] = zipWith((_, _))

  /** Defined as `zipWith((arg,f) => f(arg)` */
  def zipApply[I,I2]: Tee[I, I => I2, I2] = zipWith((arg,f) => f(arg))

  /** A version of `zip` that pads the shorter stream with values. */
  def zipAll[I, I2](padI: I, padI2: I2): Tee[I, I2, (I, I2)] =
    zipWithAll(padI, padI2)((_, _))

  /**
   * Zip together two inputs, then apply the given function,
   * halting as soon as either input is exhausted.
   * This implementation reads from the left, then the right.
   */
  def zipWith[I, I2, O](f: (I, I2) => O): Tee[I, I2, O] = {
    for {
      i <- awaitL[I]
      i2 <- awaitR[I2]
      r <- emit(f(i, i2))
    } yield r
  } repeat


  /** A version of `zipWith` that pads the shorter stream with values. */
  def zipWithAll[I, I2, O](padI: I, padI2: I2)(
    f: (I, I2) => O): Tee[I, I2, O] = {
    def fbR: Tee[I, I2, O] = receiveR { i2 => emit(f(padI, i2)) ++ fbR }
    def fbL: Tee[I, I2, O] = receiveL { i => emit(f(i, padI2)) ++ fbL }

    def go: Tee[I, I2, O] = {
      receiveLOr[I, I2, O](fbR) { i =>
        receiveROr[I, I2, O](emit(f(i, padI2)) ++ fbL) { i2 =>
          emit(f(i, i2)) ++ go
        }
      }
    }
    go
  }


  /** Feed a sequence of inputs to the left side of a `Tee`. */
  def feedL[I, I2, O](i: Seq[I])(p: Tee[I, I2, O]): Tee[I, I2, O] = {
    @tailrec
    def go(in: Seq[I], out: Vector[Seq[O]], cur: Tee[I, I2, O]): Tee[I, I2, O] = {
      if (in.nonEmpty)  cur.step match {
        case Step(Emit(os), cont) =>
          go(in, out :+ os, cont.continue)

        case Step(awt@AwaitL(rcv), cont) =>
          go(in.tail, out, rcv(right(in.head)) +: cont)

        case Step(awt@AwaitR(rcv), cont) =>
          emitAll(out.flatten) onHalt {
            case End =>   awt.extend(p => feedL(in)(p +: cont))
            case early : EarlyCause => feedL(in)(rcv(left(early)) +: cont)
          }

        case Halt(rsn) => emitAll(out.flatten).causedBy(rsn)

      } else cur.prepend(out.flatten)
    }

    go(i, Vector(), p)
  }

  /** Feed a sequence of inputs to the right side of a `Tee`. */
  def feedR[I, I2, O](i: Seq[I2])(p: Tee[I, I2, O]): Tee[I, I2, O] = {
    @tailrec
    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Tee[I, I2, O]): Tee[I, I2, O] = {
      if (in.nonEmpty) cur.step match {
        case Step(Emit(os),cont) =>
          go(in, out :+ os, cont.continue)

        case Step(awt@AwaitL(rcv), cont) =>
          emitAll(out.flatten) onHalt {
            case End =>  awt.extend(p => feedR(in)(p +: cont))
            case early : EarlyCause => feedR(in)(rcv(left(early)) +: cont)
          }

        case Step(awt@AwaitR(rcv), cont) =>
          go(in.tail, out, rcv(right(in.head)) +: cont)

        case Halt(rsn) => emitAll(out.flatten).causedBy(rsn)

      } else cur.prepend(out.flatten)
    }

    go(i, Vector(), p)
  }

  /** Feed one input to the left branch of this `Tee`. */
  def feed1L[I, I2, O](i: I)(t: Tee[I, I2, O]): Tee[I, I2, O] =
    feedL(Vector(i))(t)

  /** Feed one input to the right branch of this `Tee`. */
  def feed1R[I, I2, O](i2: I2)(t: Tee[I, I2, O]): Tee[I, I2, O] =
    feedR(Vector(i2))(t)

  /**
   * Signals, that _left_ side of tee terminated.
   * That causes all succeeding AwaitL to terminate with `cause` giving chance
   * to emit any values or read on right.
   */
  def disconnectL[I, I2, O](cause: EarlyCause)(tee: Tee[I, I2, O]): Tee[Nothing, I2, O] = {
    tee.step match {
      case Step(emt@Emit(_), cont) => emt +: cont.extend(disconnectL(cause)(_))
      case Step(AwaitL(rcv), cont) => suspend(disconnectL(cause)(rcv(left(cause)) +: cont))
      case Step(awt@AwaitR(rcv), cont) => awt.extend(p => disconnectL[I,I2,O](cause)(p +: cont))
      case hlt@Halt(rsn) => Halt(rsn)
    }
  }


  /**
   * Signals, that _right_ side of tee terminated.
   * That causes all succeeding AwaitR to terminate with `cause` giving chance
   * to emit any values or read on left.
   */
  def disconnectR[I, I2, O](cause: EarlyCause)(tee: Tee[I, I2, O]): Tee[I, Nothing, O] = {
    tee.step match {
      case Step(emt@Emit(os), cont) =>  emt +: cont.extend(disconnectR(cause)(_))
      case Step(AwaitR(rcv), cont) => suspend(disconnectR(cause)(rcv(left(cause)) +: cont))
      case Step(awt@AwaitL(rcv), cont) => awt.extend(p => disconnectR[I,I2,O](cause)(p +: cont))
      case Halt(rsn) => Halt(rsn)
    }
  }


  /**
   * Awaits to receive input from Left side,
   * than if that request terminates with `End` or is terminated abnormally
   * runs the supplied `continue` or `cleanup`.
   * Otherwise `rcv` is run to produce next state.
   *
   * If  you don't need `continue` or `cleanup` use rather `awaitL.flatMap`
   */
  def receiveL[I, I2, O](rcv: I => Tee[I, I2, O]): Tee[I, I2, O] =
    await[Env[I, I2]#T, I, O](L)(rcv)

  /**
   * Awaits to receive input from Right side,
   * than if that request terminates with `End` or is terminated abnormally
   * runs the supplied continue.
   * Otherwise `rcv` is run to produce next state.
   *
   * If  you don't need `continue` or `cleanup` use rather `awaitR.flatMap`
   */
  def receiveR[I, I2, O](rcv: I2 => Tee[I, I2, O]): Tee[I, I2, O] =
    await[Env[I, I2]#T, I2, O](R)(rcv)

  /** syntax sugar for receiveL */
  def receiveLOr[I, I2, O](fb: => Tee[I, I2, O])(rcvL: I => Tee[I, I2, O]): Tee[I, I2, O] =
    awaitOr[Env[I, I2]#T, I, O](L)(rsn => fb.causedBy(rsn))(rcvL)

  /** syntax sugar for receiveR */
  def receiveROr[I, I2, O](fb: => Tee[I, I2, O])(rcvR: I2 => Tee[I, I2, O]): Tee[I, I2, O] =
    awaitOr[Env[I, I2]#T, I2, O](R)(rsn => fb.causedBy(rsn))(rcvR)


  //////////////////////////////////////////////////////////////////////
  // De-constructors
  //////////////////////////////////////////////////////////////////////
  type TeeAwaitL[I, I2, O] = Await[Env[I, I2]#T, Env[I, Any]#Is[I], O]
  type TeeAwaitR[I, I2, O] = Await[Env[I, I2]#T, Env[Any, I2]#T[I2], O]


  object AwaitL {
    def unapply[I, I2, O](self: TeeAwaitL[I, I2, O]):
    Option[(EarlyCause \/ I => Tee[I, I2, O])] = self match {
      case Await(req, rcv,_)
        if req.tag == 0 => Some((r: EarlyCause \/ I) =>
        Try(rcv.asInstanceOf[(EarlyCause \/ I) => Trampoline[Tee[I,I2,O]]](r).run))
      case _                               => None
    }

    /** Like `AwaitL.unapply` only allows fast test that wye is awaiting on left side */
    object is {
      def unapply[I, I2, O](self: TeeAwaitL[I, I2, O]): Boolean = self match {
        case Await(req, rcv,_) if req.tag == 0 => true
        case _                               => false
      }
    }
  }

  object AwaitR {
    def unapply[I, I2, O](self: TeeAwaitR[I, I2, O]):
    Option[(EarlyCause \/ I2 => Tee[I, I2, O])] = self match {
      case Await(req, rcv,_)
        if req.tag == 1 => Some((r: EarlyCause \/ I2) =>
        Try(rcv.asInstanceOf[(EarlyCause \/ I2) => Trampoline[Tee[I,I2,O]]](r).run))
      case _                               => None
    }


    /** Like `AwaitR.unapply` only allows fast test that wye is awaiting on left side */
    object is {
      def unapply[I, I2, O](self: TeeAwaitR[I, I2, O]): Boolean = self match {
        case Await(req, rcv,_) if req.tag == 1 => true
        case _                               => false
      }
    }
  }
}

/**
 * Operations on process that uses `tee`
 */
private[stream] trait TeeOps[+F[_], +O] {

  self: Process[F, O] =>

  /** Alternate emitting elements from `this` and `p2`, starting with `this`. */
  def interleave[F2[x] >: F[x], O2 >: O](p2: Process[F2, O2]): Process[F2, O2] =
    this.tee(p2)(scalaz.stream.tee.interleave[O2])

  /** Call `tee` with the `zipWith` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zipWith[F2[x] >: F[x], O2, O3](p2: Process[F2, O2])(f: (O, O2) => O3): Process[F2, O3] =
    this.tee(p2)(scalaz.stream.tee.zipWith(f))

  /** Call `tee` with the `zip` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zip[F2[x] >: F[x], O2](p2: Process[F2, O2]): Process[F2, (O, O2)] =
    this.tee(p2)(scalaz.stream.tee.zip)

  /**
   * When `condition` is `true`, lets through any values in `this` process, otherwise blocks
   * until `condition` becomes true again. Note that the `condition` is checked before
   * each and every read from `this`, so `condition` should return very quickly or be
   * continuous to avoid holding up the output `Process`. Use `condition.forwardFill` to
   * convert an infrequent discrete `Process` to a continuous one for use with this
   * function.
   */
  def when[F2[x] >: F[x], O2 >: O](condition: Process[F2, Boolean]): Process[F2, O2] =
    condition.tee(this)(scalaz.stream.tee.when)


  /** Delay running this `Process` until `awaken` becomes true for the first time. */
  def sleepUntil[F2[x] >: F[x], O2 >: O](awaken: Process[F2, Boolean]): Process[F2, O2] =
    Process.sleepUntil(awaken)(this)

  /**
   * Halts this `Process` as soon as `condition` becomes `true`. Note that `condition`
   * is checked before each and every read from `this`, so `condition` should return
   * very quickly or be continuous to avoid holding up the output `Process`. Use
   * `condition.forwardFill` to convert an infrequent discrete `Process` to a
   * continuous one for use with this function.
   */
  def until[F2[x] >: F[x], O2 >: O](condition: Process[F2, Boolean]): Process[F2, O2] =
    condition.tee(this)(scalaz.stream.tee.until)

}

/**
 * This class provides infix syntax specific to `Tee`. We put these here
 * rather than trying to cram them into `Process` itself using implicit
 * equality witnesses. This doesn't work out so well due to variance
 * issues.
 */
final class TeeSyntax[I, I2, O](val self: Tee[I, I2, O]) extends AnyVal {

  /** Transform the left input to a `Tee`. */
  def contramapL[I0](f: I0 => I): Tee[I0, I2, O] =
    self.contramapL_(f).asInstanceOf[Tee[I0, I2, O]]

  /** Transform the right input to a `Tee`. */
  def contramapR[I3](f: I3 => I2): Tee[I, I3, O] =
    self.contramapR_(f).asInstanceOf[Tee[I, I3, O]]

}
