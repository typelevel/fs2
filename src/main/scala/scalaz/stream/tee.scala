package scalaz.stream

import Process._

/**
 * Module of various `Tee` processes.
 */
trait tee {

  /** A `Tee` which alternates between emitting values from the left input and the right input. */
  def interleave[I]: Tee[I,I,I] = repeat { for {
    i1 <- awaitL[I]
    i2 <- awaitR[I]
    r <- emit(i1) ++ emit(i2)
  } yield r }

  /** A `Tee` which ignores all input from left. */
  def passR[I2]: Tee[Any,I2,I2] = awaitR[I2].repeat

  /* A `Tee` which ignores all input from the right. */
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
}

object tee extends tee {

  /** Feed a sequence of inputs to the left side of a `Tee`. */
  def feedL[I,I2,O](i: Seq[I])(p: Tee[I,I2,O]): Tee[I,I2,O] = {
    @annotation.tailrec
    def go(in: Seq[I], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] =
      if (in.nonEmpty) cur match {
        case h@Halt(_) => emitSeq(out.flatten, h)
        case Emit(h, t) => go(in, out :+ h, t)
        case AwaitL(recv, fb, c) =>
          val next =
            try recv(in.head)
            catch {
              case End => fb
              case e: Throwable => Halt(e)
            }
          go(in.tail, out, next)
        case AwaitR(recv, fb, c) =>
          emitSeq(out.flatten,
          await(R[I2]: Env[I,I2]#T[I2])(recv andThen (feedL(in)), feedL(in)(fb), feedL(in)(c)))
      }
      else emitSeq(out.flatten, cur)
    go(i, Vector(), p)
  }

  /** Feed a sequence of inputs to the right side of a `Tee`. */
  def feedR[I,I2,O](i: Seq[I2])(p: Tee[I,I2,O]): Tee[I,I2,O] = {
    @annotation.tailrec
    def go(in: Seq[I2], out: Vector[Seq[O]], cur: Tee[I,I2,O]): Tee[I,I2,O] =
      if (in.nonEmpty) cur match {
        case h@Halt(_) => emitSeq(out.flatten, h)
        case Emit(h, t) => go(in, out :+ h, t)
        case AwaitR(recv, fb, c) =>
          val next =
            try recv(in.head)
            catch {
              case End => fb
              case e: Throwable => Halt(e)
            }
          go(in.tail, out, next)
        case AwaitL(recv, fb, c) =>
          emitSeq(out.flatten,
          await(L[I]: Env[I,I2]#T[I])(recv andThen (feedR(in)), feedR(in)(fb), feedR(in)(c)))
      }
      else emitSeq(out.flatten, cur)
    go(i, Vector(), p)
  }

  /** Feed one input to the left branch of this `Tee`. */
  def feed1L[I,I2,O](i: I)(t: Tee[I,I2,O]): Tee[I,I2,O] =
    wye.feed1L(i)(t).asInstanceOf[Tee[I,I2,O]]

  /** Feed one input to the right branch of this `Tee`. */
  def feed1R[I,I2,O](i2: I2)(t: Tee[I,I2,O]): Tee[I,I2,O] =
    wye.feed1R(i2)(t).asInstanceOf[Tee[I,I2,O]]

  object AwaitL {
    def unapply[I,I2,O](self: Tee[I,I2,O]):
        Option[(I => Tee[I,I2,O], Tee[I,I2,O], Tee[I,I2,O])] = self match {
      case Await(req,recv,fb,c) if req.tag == 0 => Some((recv.asInstanceOf[I => Tee[I,I2,O]], fb, c))
      case _ => None
    }
    def apply[I,I2,O](recv: I => Tee[I,I2,O],
                      fallback: Tee[I,I2,O] = halt,
                      cleanup: Tee[I,I2,O] = halt): Tee[I,I2,O] =
      await(L[I]: Env[I,I2]#T[I])(recv, fallback, cleanup)
  }
  object AwaitR {
    def unapply[I,I2,O](self: Tee[I,I2,O]):
        Option[(I2 => Tee[I,I2,O], Tee[I,I2,O], Tee[I,I2,O])] = self match {
      case Await(req,recv,fb,c) if req.tag == 1 => Some((recv.asInstanceOf[I2 => Tee[I,I2,O]], fb, c))
      case _ => None
    }
    def apply[I,I2,O](recv: I2 => Tee[I,I2,O],
                      fallback: Tee[I,I2,O] = halt,
                      cleanup: Tee[I,I2,O] = halt): Tee[I,I2,O] =
      await(R[I2]: Env[I,I2]#T[I2])(recv, fallback, cleanup)
  }
}
