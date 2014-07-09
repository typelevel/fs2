package scalaz.stream

import Process._
import scalaz.stream.Util._
import scalaz.\/._
import scala.annotation.tailrec
import scalaz.\/


object tee  {

  /** A `Tee` which alternates between emitting values from the left input and the right input. */
  def interleave[I]: Tee[I,I,I] = repeat { for {
    i1 <- awaitL[I]
    i2 <- awaitR[I]
    r <- emit(i1) ++ emit(i2)
  } yield r }

  /** A `Tee` which ignores all input from left. */
  def passR[I2]: Tee[Any,I2,I2] = awaitR[I2].repeat

  /** A `Tee` which ignores all input from the right. */
  def passL[I]: Tee[I,Any,I] = awaitL[I].repeat

  /** Echoes the right branch until the left branch becomes `true`, then halts. */
  def until[I]: Tee[Boolean,I,I] =
    awaitL[Boolean].flatMap(kill => if (kill) halt else awaitR[I] fby until)

  /** Echoes the right branch when the left branch is `true`. */
  def when[I]: Tee[Boolean,I,I] =
    awaitL[Boolean].flatMap(ok => if (ok) awaitR[I] fby when else when)

  /** Defined as `zipWith((_,_))` */
  def zip[I,I2]: Tee[I,I2,(I,I2)] = zipWith((_,_))

  /** A version of `zip` that pads the shorter stream with values. */
  def zipAll[I,I2](padI: I, padI2: I2): Tee[I,I2,(I,I2)] =
    zipWithAll(padI, padI2)((_,_))


  /**
   * Zip together two inputs, then apply the given function,
   * halting as soon as either input is exhausted.
   * This implementation reads from the left, then the right.
   */
  def zipWith[I,I2,O](f: (I,I2) => O): Tee[I,I2,O] = { for {
    i <- awaitL[I]
    i2 <- awaitR[I2]
    r <- emit(f(i,i2))
  } yield r } repeat


  /** A version of `zipWith` that pads the shorter stream with values. */
  def zipWithAll[I,I2,O](padI: I, padI2: I2)(
    f: (I,I2) => O): Tee[I,I2,O] = {
    def fbR : Tee[I,I2,O] = receiveR{ i2 => emit(f(padI,i2)) ++ fbR }
    def fbL : Tee[I,I2,O] = receiveL{ i => emit(f(i,padI2)) ++ fbL }

    def go :  Tee[I,I2,O] = {
      receiveLOr[I, I2, O](fbR) { i =>
        receiveROr[I, I2, O](emit(f(i,padI2)) ++ fbL) { i2 =>
          emit(f(i, i2)) ++ go
        }
      }
    }
    go
  }


  /** Feed a sequence of inputs to the left side of a `Tee`. */
  def feedL[I,I2,O](i: Seq[I])(p: Tee[I,I2,O]): Tee[I,I2,O] = {
    @tailrec
    def go(in: Seq[I], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] = {
      cur.step match {
        case Cont(Emit(os),next) =>
          go(in,out :+ os, Try(next(Continue)))

        case Cont(AwaitR.receive(rcv), next) =>
          emitAll(out.flatten) fby
            (awaitOr(R[I2]: Env[I,I2]#T[I2])
             (rsn => feedL(in)(rcv(left(rsn)) onHalt next))
             (i2 => feedL[I,I2,O](in)(Try(rcv(right(i2))) onHalt next)))

        case Cont(awt@AwaitL(rcv), next) =>
          if (in.nonEmpty) go(in.tail,out,Try(rcv(in.head)) onHalt next )
          else emitAll(out.flatten).asInstanceOf[Tee[I,I2,O]] fby (awt onHalt next)

        case Done(rsn) => emitAll(out.flatten).causedBy(rsn)
      }
    }

    go(i, Vector(), p)


  }

  /** Feed a sequence of inputs to the right side of a `Tee`. */
  def feedR[I,I2,O](i: Seq[I2])(p: Tee[I,I2,O]): Tee[I,I2,O] = {
    @tailrec
    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] = {
      cur.step match {
        case Cont(Emit(os),next) =>
          go(in,out :+ os, Try(next(Continue)))

        case Cont(awt@AwaitR(rcv), next) =>
          if (in.nonEmpty)   go(in.tail,out,Try(rcv(in.head)) onHalt next )
          else emitAll(out.flatten).asInstanceOf[Tee[I,I2,O]] fby (awt onHalt next)

        case Cont(AwaitL.receive(rcv), next) =>
          emitAll(out.flatten) fby
            (awaitOr(L[I]: Env[I,I2]#T[I])
             (rsn => feedR(in)(rcv(left(rsn)) onHalt next))
             (i => feedR[I,I2,O](in)(Try(rcv(right(i))) onHalt next)))

        case Done(rsn) => emitAll(out.flatten).causedBy(rsn)
      }
    }

    go(i, Vector(), p)


  }

  /** Feed one input to the left branch of this `Tee`. */
  def feed1L[I,I2,O](i: I)(t: Tee[I,I2,O]): Tee[I,I2,O] =
    feedL(Vector(i))(t)

  /** Feed one input to the right branch of this `Tee`. */
  def feed1R[I,I2,O](i2: I2)(t: Tee[I,I2,O]): Tee[I,I2,O] =
    feedR(Vector(i2))(t)

  /**
   * Signals, that _left_ side of tee terminated.
   * That causes all succeeding `AwaitL` to terminate with Kill.
   */
  def disconnectL[I,I2,O](tee:Tee[I,I2,O]) : Tee[Nothing,I2,O] ={
    tee.suspendStep.flatMap  {
      case Cont(e@Emit(_),n) =>
        e onHalt { rsn => disconnectL(Try(n(rsn))) }

      case Cont(AwaitL.receive(rcv), n) =>
        disconnectL(Try(rcv(left(End))) onHalt n)

      case Cont(AwaitR(rcv), n) =>
        await(R[I2]: Env[Nothing,I2]#T[I2])(i2 => disconnectL(Try(rcv(i2))))
        .onHalt(rsn0 => disconnectL(Try(n(rsn0))))

      case dn@Done(_) => dn.asHalt
    }
  }


  /**
   * Signals, that _right_ side of tee terminated.
   * That causes all succeeding `AwaitR` to terminate with Kill.
   */
  def disconnectR[I,I2,O](tee:Tee[I,I2,O]) : Tee[I,Nothing,O] ={
    tee.suspendStep.flatMap  {
      case Cont(e@Emit(os),n) =>
        e onHalt { rsn => disconnectR(Try(n(rsn))) }

      case Cont(AwaitR.receive(rcv), n) =>
        disconnectR(Try(rcv(left(End))) onHalt n)

      case Cont(AwaitL(rcv), n) =>
        await(L[I]: Env[I,Nothing]#T[I])(i => disconnectR(Try(rcv(i))))
        .onHalt(rsn0 => disconnectR(Try(n(rsn0))))

      case dn@Done(_) => dn.asHalt
    }
  }



  //////////////////////////////////////////////////////////////////////
  // De-constructors
  //////////////////////////////////////////////////////////////////////
  type TeeAwaitL[I,I2,O] = Await[Env[I,I2]#T,Env[I,Any]#Is[I],O]
  type TeeAwaitR[I,I2,O] = Await[Env[I,I2]#T,Env[Any,I2]#T[I2],O]


  object AwaitL {
    def unapply[I,I2,O](self: Tee[I,I2,O]):
    Option[(I => Tee[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 0 =>  Some( (i : I) => Try(rcv(right(i)).run) )    // Some(rcv.asInstanceOf[I => Tee[I,I2,O]])
      case _ => None
    }
    /** Like `AwaitL` de-constructor, but instead extracts `rcv` with possibility to pass reason for termination **/
    object receive {
      def unapply[I,I2,O](self: Tee[I,I2,O]):
      Option[(Throwable \/ I => Tee[I,I2,O])] = self match {
        case Await(req,rcv) if req.tag == 0 =>  Some( (r : Throwable \/ I) => Try(rcv(r).run) )    // Some(rcv.asInstanceOf[I => Tee[I,I2,O]])
        case _ => None
      }
    }
    /** Like `AwaitL.unapply` only allows fast test that wye is awaiting on left side **/
    object is {
      def unapply[I,I2,O](self: TeeAwaitL[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 0 => true
        case _ => false
      }
    }
  }

  object AwaitR {
    def unapply[I,I2,O](self: Tee[I,I2,O]):
    Option[(I2 => Tee[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 1 =>  Some( (i2 : I2) => Try(rcv(right(i2)).run) )    //Some((recv.asInstanceOf[I2 => Tee[I,I2,O]], fb, c))
      case _ => None
    }

    /** Like `AwaitR` de-constructor, but instead extracts `rcv` with possibility to pass reason for termination **/
    object receive {
      def unapply[I,I2,O](self: Tee[I,I2,O]):
      Option[(Throwable \/ I2=> Tee[I,I2,O])] = self match {
        case Await(req,rcv) if req.tag == 1 =>  Some( (r: Throwable \/ I2) => Try(rcv(r).run) )    //Some((recv.asInstanceOf[I2 => Tee[I,I2,O]], fb, c))
        case _ => None
      }
    }

    /** Like `AwaitR.unapply` only allows fast test that wye is awaiting on left side **/
    object is {
      def unapply[I,I2,O](self: TeeAwaitR[I,I2,O]):Boolean = self match {
        case Await(req,rcv) if req.tag == 1 => true
        case _ => false
      }
    }
  }
}

/**
 * Operations on process that uses `tee`
 */
private[stream] trait TeeOps[+F[_],+O] {

  self: Process[F,O] =>

  /** Alternate emitting elements from `this` and `p2`, starting with `this`. */
  def interleave[F2[x]>:F[x],O2>:O](p2: Process[F2,O2]): Process[F2,O2] =
    this.tee(p2)(scalaz.stream.tee.interleave[O2])

  /** Call `tee` with the `zipWith` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zipWith[F2[x]>:F[x],O2,O3](p2: Process[F2,O2])(f: (O,O2) => O3): Process[F2,O3] =
    this.tee(p2)(scalaz.stream.tee.zipWith(f))

  /** Call `tee` with the `zip` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zip[F2[x]>:F[x],O2](p2: Process[F2,O2]): Process[F2,(O,O2)] =
    this.tee(p2)(scalaz.stream.tee.zip)

  /**
   * When `condition` is `true`, lets through any values in `this` process, otherwise blocks
   * until `condition` becomes true again. Note that the `condition` is checked before
   * each and every read from `this`, so `condition` should return very quickly or be
   * continuous to avoid holding up the output `Process`. Use `condition.forwardFill` to
   * convert an infrequent discrete `Process` to a continuous one for use with this
   * function.
   */
  def when[F2[x]>:F[x],O2>:O](condition: Process[F2,Boolean]): Process[F2,O2] =
    condition.tee(this)(scalaz.stream.tee.when)


  /** Delay running this `Process` until `awaken` becomes true for the first time. */
  def sleepUntil[F2[x]>:F[x],O2>:O](awaken: Process[F2,Boolean]): Process[F2,O2] =
    awaken.dropWhile(!_).once.flatMap(b => if (b) this else halt)

  /**
   * Halts this `Process` as soon as `condition` becomes `true`. Note that `condition`
   * is checked before each and every read from `this`, so `condition` should return
   * very quickly or be continuous to avoid holding up the output `Process`. Use
   * `condition.forwardFill` to convert an infrequent discrete `Process` to a
   * continuous one for use with this function.
   */
  def until[F2[x]>:F[x],O2>:O](condition: Process[F2,Boolean]): Process[F2,O2] =
    condition.tee(this)(scalaz.stream.tee.until)

}
