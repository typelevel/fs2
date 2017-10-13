package fs2

import scala.concurrent.ExecutionContext

import cats.effect.Effect
import cats.implicits._

import fs2.async.mutable.Queue
import fs2.internal.{ Algebra, FreeC, NonFatal }

object Pipe {

  /** Creates a [[Stepper]], which allows incrementally stepping a pure pipe. */
  def stepper[I,O](p: Pipe[Pure,I,O]): Stepper[I,O] = {
    type ReadSegment[R] = Option[Segment[I,Unit]] => R
    type Read[R] = FreeC[ReadSegment, R]
    type UO = Option[(Segment[O,Unit],Stream[Read,O])]

    def prompts: Stream[Read,I] =
      Stream.eval[Read,Option[Segment[I,Unit]]](FreeC.Eval(identity)).flatMap {
        case None => Stream.empty
        case Some(segment) => Stream.segment(segment).append(prompts)
      }

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

    def go(s: Read[UO]): Stepper[I,O] = Stepper.Suspend { () =>
      s.viewL.get match {
        case FreeC.Pure(None) => Stepper.Done
        case FreeC.Pure(Some((hd,tl))) => Stepper.Emits(hd, go(stepf(tl)))
        case FreeC.Fail(t) => Stepper.Fail(t)
        case bound: FreeC.Bind[ReadSegment,_,UO] =>
          val f = bound.asInstanceOf[FreeC.Bind[ReadSegment,Any,UO]].f
          val fx = bound.fx.asInstanceOf[FreeC.Eval[ReadSegment,UO]].fr
          Stepper.Await(segment => try go(f(Right(fx(segment)))) catch { case NonFatal(t) => go(f(Left(t))) })
        case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
      }
    }
    go(stepf(prompts.through(p)))
  }

  /**
   * Allows stepping of a pure pipe. Each invocation of [[step]] results in
   * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
   * failed with an exception, it emitted a chunk of output, or it is awaiting input.
   */
  sealed abstract class Stepper[-A,+B] {
    @annotation.tailrec
    final def step: Stepper.Step[A,B] = this match {
      case Stepper.Suspend(s) => s().step
      case _ => this.asInstanceOf[Stepper.Step[A,B]]
    }
  }

  object Stepper {
    private[fs2] final case class Suspend[A,B](force: () => Stepper[A,B]) extends Stepper[A,B]

    /** Algebra describing the result of stepping a pure pipe. */
    sealed abstract class Step[-A,+B] extends Stepper[A,B]
    /** Pipe indicated it is done. */
    final case object Done extends Step[Any,Nothing]
    /** Pipe failed with the specified exception. */
    final case class Fail(err: Throwable) extends Step[Any,Nothing]
    /** Pipe emitted a segment of elements. */
    final case class Emits[A,B](segment: Segment[B,Unit], next: Stepper[A,B]) extends Step[A,B]
    /** Pipe is awaiting input. */
    final case class Await[A,B](receive: Option[Segment[A,Unit]] => Stepper[A,B]) extends Step[A,B]
  }

  /** Queue based version of [[join]] that uses the specified queue. */
  def joinQueued[F[_],A,B](q: F[Queue[F,Option[Segment[A,Unit]]]])(s: Stream[F,Pipe[F,A,B]])(implicit F: Effect[F], ec: ExecutionContext): Pipe[F,A,B] = in => {
    for {
      done <- Stream.eval(async.signalOf(false))
      q <- Stream.eval(q)
      b <- in.segments.map(Some(_)).evalMap(q.enqueue1)
             .drain
             .onFinalize(q.enqueue1(None))
             .onFinalize(done.set(true)) merge done.interrupt(s).flatMap { f =>
               f(q.dequeue.unNoneTerminate flatMap { x => Stream.segment(x) })
             }
    } yield b
  }

  /** Asynchronous version of [[join]] that queues up to `maxQueued` elements. */
  def joinAsync[F[_]:Effect,A,B](maxQueued: Int)(s: Stream[F,Pipe[F,A,B]])(implicit ec: ExecutionContext): Pipe[F,A,B] =
    joinQueued[F,A,B](async.boundedQueue(maxQueued))(s)

  /**
   * Joins a stream of pipes in to a single pipe.
   * Input is fed to the first pipe until it terminates, at which point input is
   * fed to the second pipe, and so on.
   */
  def join[F[_]:Effect,A,B](s: Stream[F,Pipe[F,A,B]])(implicit ec: ExecutionContext): Pipe[F,A,B] =
    joinQueued[F,A,B](async.synchronousQueue)(s)
}
