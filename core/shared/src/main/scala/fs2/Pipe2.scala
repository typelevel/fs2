package fs2

import fs2.internal.{ Algebra, FreeC, NonFatal }

object Pipe2 {
  /** Creates a [[Stepper]], which allows incrementally stepping a pure `Pipe2`. */
  def stepper[I,I2,O](p: Pipe2[Pure,I,I2,O]): Stepper[I,I2,O] = {
    type ReadSegment[R] = Either[Option[Segment[I,Unit]] => R, Option[Segment[I2,Unit]] => R]
    type Read[R] = FreeC[ReadSegment,R]
    type UO = Option[(Segment[O,Unit],Stream[Read,O])]

    def prompts[X](id: ReadSegment[Option[Segment[X,Unit]]]): Stream[Read,X] = {
      Stream.eval[Read,Option[Segment[X,Unit]]](FreeC.Eval(id)).flatMap {
        case None => Stream.empty
        case Some(segment) => Stream.segment(segment).append(prompts(id))
      }
    }
    def promptsL: Stream[Read,I] = prompts[I](Left(identity))
    def promptsR: Stream[Read,I2] = prompts[I2](Right(identity))

    // Steps `s` without overhead of resource tracking
    def stepf(s: Stream[Read,O]): Read[UO] = {
      Algebra.runFoldScope(
        Algebra.Scope.newRoot[Read],
        None,
        None,
        Algebra.uncons(s.get).flatMap {
          case Some((hd,tl)) => Algebra.output1[Read,UO](Some((hd,Stream.fromFreeC(tl))))
          case None => Algebra.pure[Read,UO,Unit](())
        }, None: UO)((x,y) => y)
    }

    def go(s: Read[UO]): Stepper[I,I2,O] = Stepper.Suspend { () =>
      s.viewL.get match {
        case FreeC.Pure(None) => Stepper.Done
        case FreeC.Pure(Some((hd,tl))) => Stepper.Emits(hd, go(stepf(tl)))
        case FreeC.Fail(t) => Stepper.Fail(t)
        case bound: FreeC.Bind[ReadSegment,_,UO] =>
          val f = bound.asInstanceOf[FreeC.Bind[ReadSegment,Any,UO]].f
          val fx = bound.fx.asInstanceOf[FreeC.Eval[ReadSegment,UO]].fr
          fx match {
            case Left(recv) =>
              Stepper.AwaitL(segment => try go(f(Right(recv(segment)))) catch { case NonFatal(t) => go(f(Left(t))) })
            case Right(recv) =>
              Stepper.AwaitR(segment => try go(f(Right(recv(segment)))) catch { case NonFatal(t) => go(f(Left(t))) })
          }
        case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
      }
    }
    go(stepf(p.covary[Read].apply(promptsL, promptsR)))
  }

  /**
   * Allows stepping of a pure pipe. Each invocation of [[step]] results in
   * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
   * failed with an exception, it emitted a chunk of output, or it is awaiting input
   * from either the left or right branch.
   */
  sealed abstract class Stepper[-I,-I2,+O] {
    import Stepper._
    @annotation.tailrec
    final def step: Step[I,I2,O] = this match {
      case Suspend(s) => s().step
      case _ => this.asInstanceOf[Step[I,I2,O]]
    }
  }

  object Stepper {
    private[fs2] final case class Suspend[I,I2,O](force: () => Stepper[I,I2,O]) extends Stepper[I,I2,O]

    /** Algebra describing the result of stepping a pure `Pipe2`. */
    sealed abstract class Step[-I,-I2,+O] extends Stepper[I,I2,O]
    /** Pipe indicated it is done. */
    final case object Done extends Step[Any,Any,Nothing]
    /** Pipe failed with the specified exception. */
    final case class Fail(err: Throwable) extends Step[Any,Any,Nothing]
    /** Pipe emitted a segment of elements. */
    final case class Emits[I,I2,O](segment: Segment[O,Unit], next: Stepper[I,I2,O]) extends Step[I,I2,O]
    /** Pipe is awaiting input from the left. */
    final case class AwaitL[I,I2,O](receive: Option[Segment[I,Unit]] => Stepper[I,I2,O]) extends Step[I,I2,O]
    /** Pipe is awaiting input from the right. */
    final case class AwaitR[I,I2,O](receive: Option[Segment[I2,Unit]] => Stepper[I,I2,O]) extends Step[I,I2,O]
  }
}
