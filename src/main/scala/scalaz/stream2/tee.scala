package scalaz.stream2

import Process._
import scalaz.stream2.Util._
import scalaz.\/._
import scala.annotation.tailrec


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
    val fbR: Tee[I,I2,O] = passR[I2] map (f(padI, _    ))
    val fbL: Tee[I,I2,O] = passL[I]  map (f(_   , padI2))
    receiveLOr(fbR)(i =>
      receiveROr(tee.feed1L(i)(fbL))(i2 => emit(f(i,i2)))) repeat
  }


  /** Feed a sequence of inputs to the left side of a `Tee`. */
  def feedL[I,I2,O](i: Seq[I])(p: Tee[I,I2,O]): Tee[I,I2,O] = {
    @tailrec
    def go(in: Seq[I], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] = {
      cur.step match {
        case Cont(Emit(os),next) => go(in,out :+ os, Try(next(End)))
        case Cont(awt@AwaitR(rcv), next) =>
          (emitAll(out.flatten) ++
            await(R[I2]: Env[I,I2]#T[I2])(rcv andThen(feedL[I,I2,O](in)))
            ) onHalt next
        case Cont(awt@AwaitL(rcv), next) =>
          if (in.nonEmpty) go(in.tail,out,Try(rcv(in.head)) onHalt next )
          else emitAll(out).asInstanceOf[Tee[I,I2,O]] ++ (awt onHalt next)
        case Done(rsn) => emitAll(out.flatten).causedBy(rsn)
      }
    }

    go(i, Vector(), p)


//    @annotation.tailrec
//    def go(in: Seq[I], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] =
//      if (in.nonEmpty) cur match {
//        case h@Halt(_) => emitSeq(out.flatten, h)
//        case Emit(h, t) => go(in, out :+ h, t)
//        case AwaitL(recv, fb, c) =>
//          val next =
//            try recv(in.head)
//            catch {
//              case End => fb
//              case e: Throwable => Halt(e)
//            }
//          go(in.tail, out, next)
//        case AwaitR(recv, fb, c) =>
//          emitSeq(out.flatten,
//            await(R[I2]: Env[I,I2]#T[I2])(recv andThen (feedL(in)), feedL(in)(fb), feedL(in)(c)))
//      }
//      else emitSeq(out.flatten, cur)
//    go(i, Vector(), p)

  }

  /** Feed a sequence of inputs to the right side of a `Tee`. */
  def feedR[I,I2,O](i: Seq[I2])(p: Tee[I,I2,O]): Tee[I,I2,O] = {
    @tailrec
    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] = {
      cur.step match {
        case Cont(Emit(os),next) => go(in,out :+ os, Try(next(End)))
        case Cont(awt@AwaitR(rcv), next) =>
          if (in.nonEmpty)   go(in.tail,out,Try(rcv(in.head)) onHalt next )
          else emitAll(out).asInstanceOf[Tee[I,I2,O]] ++ (awt onHalt next)
        case Cont(awt@AwaitL(rcv), next) =>
          (emitAll(out.flatten) ++
            await(L[I]: Env[I,I2]#T[I])(rcv andThen(feedR[I,I2,O](in)))
            ) onHalt next
        case Done(rsn) => emitAll(out.flatten).causedBy(rsn)
      }
    }

    go(i, Vector(), p)

//    @annotation.tailrec
//    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] =
//      if (in.nonEmpty) cur match {
//        case h@Halt(_) => emitSeq(out.flatten, h)
//        case Emit(h, t) => go(in, out :+ h, t)
//        case AwaitR(recv, fb, c) =>
//          val next =
//            try recv(in.head)
//            catch {
//              case End => fb
//              case e: Throwable => Halt(e)
//            }
//          go(in.tail, out, next)
//        case AwaitL(recv, fb, c) =>
//          emitSeq(out.flatten,
//            await(L[I]: Env[I,I2]#T[I])(recv andThen (feedR(in)), feedR(in)(fb), feedR(in)(c)))
//      }
//      else emitSeq(out.flatten, cur)
//    go(i, Vector(), p)

  }

  /** Feed one input to the left branch of this `Tee`. */
  def feed1L[I,I2,O](i: I)(t: Tee[I,I2,O]): Tee[I,I2,O] = feedL(Vector(i))(t)
   // wye.feed1L(i)(t).asInstanceOf[Tee[I,I2,O]]

  /** Feed one input to the right branch of this `Tee`. */
  def feed1R[I,I2,O](i2: I2)(t: Tee[I,I2,O]): Tee[I,I2,O] = feedR(Vector(i2))(t)
   // wye.feed1R(i2)(t).asInstanceOf[Tee[I,I2,O]]


  //////////////////////////////////////////////////////////////////////
  // De-constructors
  //////////////////////////////////////////////////////////////////////

  object AwaitL {
    def unapply[I,I2,O](self: Tee[I,I2,O]):
    Option[(I => Tee[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 0 =>  Some( (i : I) => Try(rcv(right(i)).run) )    // Some(rcv.asInstanceOf[I => Tee[I,I2,O]])
      case _ => None
    }
  }

  object AwaitR {
    def unapply[I,I2,O](self: Tee[I,I2,O]):
    Option[(I2 => Tee[I,I2,O])] = self match {
      case Await(req,rcv) if req.tag == 1 =>  Some( (i2 : I2) => Try(rcv(right(i2)).run) )    //Some((recv.asInstanceOf[I2 => Tee[I,I2,O]], fb, c))
      case _ => None
    }
  }
}

/**
 * Operations on process that uses `tee`
 */
private[stream2] trait TeeOps[+F[_],+O] {

  self: Process[F,O] =>

  /** Alternate emitting elements from `this` and `p2`, starting with `this`. */
  def interleave[F2[x]>:F[x],O2>:O](p2: Process[F2,O2]): Process[F2,O2] =
    this.tee(p2)(scalaz.stream2.tee.interleave[O2])

  /** Call `tee` with the `zipWith` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zipWith[F2[x]>:F[x],O2,O3](p2: Process[F2,O2])(f: (O,O2) => O3): Process[F2,O3] =
    this.tee(p2)(scalaz.stream2.tee.zipWith(f))

  /** Call `tee` with the `zip` `Tee[O,O2,O3]` defined in `tee.scala`. */
  def zip[F2[x]>:F[x],O2](p2: Process[F2,O2]): Process[F2,(O,O2)] =
    this.tee(p2)(scalaz.stream2.tee.zip)

  /**
   * When `condition` is `true`, lets through any values in `this` process, otherwise blocks
   * until `condition` becomes true again. Note that the `condition` is checked before
   * each and every read from `this`, so `condition` should return very quickly or be
   * continuous to avoid holding up the output `Process`. Use `condition.forwardFill` to
   * convert an infrequent discrete `Process` to a continuous one for use with this
   * function.
   */
  def when[F2[x]>:F[x],O2>:O](condition: Process[F2,Boolean]): Process[F2,O2] =
    condition.tee(this)(scalaz.stream2.tee.when)


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
    condition.tee(this)(scalaz.stream2.tee.until)

}
