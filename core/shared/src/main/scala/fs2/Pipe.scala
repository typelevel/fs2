package fs2

import scala.concurrent.ExecutionContext

import cats.effect.Effect
import cats.implicits._

import fs2.async.mutable.Queue

object Pipe {

  // /** Creates a [[Stepper]], which allows incrementally stepping a pure pipe. */
  // def stepper[I,O](s: Pipe[Pure,I,O]): Stepper[I,O] = {
  //   type Read[+R] = Option[Chunk[I]] => R
  //   def readFunctor: Functor[Read] = new Functor[Read] {
  //     def map[A,B](fa: Read[A])(g: A => B): Read[B]
  //       = fa andThen g
  //   }
  //   def prompts: Stream[Read,I] =
  //     Stream.eval[Read, Option[Chunk[I]]](identity).flatMap {
  //       case None => Stream.empty
  //       case Some(chunk) => Stream.chunk(chunk).append(prompts)
  //     }
  //
  //   def outputs: Stream[Read,O] = covary[Read,I,O](s)(prompts)
  //   def stepf(s: Handle[Read,O]): Free[Read, Option[(Chunk[O],Handle[Read, O])]]
  //   = s.buffer match {
  //       case hd :: tl => Free.pure(Some((hd, new Handle[Read,O](tl, s.stream))))
  //       case List() => s.stream.step.flatMap { s => Pull.output1(s) }
  //        .close.runFoldFree(None: Option[(Chunk[O],Handle[Read, O])])(
  //         (_,s) => Some(s))
  //     }
  //   def go(s: Free[Read, Option[(Chunk[O],Handle[Read, O])]]): Stepper[I,O] =
  //     Stepper.Suspend { () =>
  //       s.unroll[Read](readFunctor, Sub1.sub1[Read]) match {
  //         case Free.Unroll.Fail(err) => Stepper.Fail(err)
  //         case Free.Unroll.Pure(None) => Stepper.Done
  //         case Free.Unroll.Pure(Some((hd, tl))) => Stepper.Emits(hd, go(stepf(tl)))
  //         case Free.Unroll.Eval(recv) => Stepper.Await(chunk => go(recv(chunk)))
  //       }
  //     }
  //   go(stepf(new Handle[Read,O](List(), outputs)))
  // }
  //
  // /**
  //  * Allows stepping of a pure pipe. Each invocation of [[step]] results in
  //  * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
  //  * failed with an exception, it emitted a chunk of output, or it is awaiting input.
  //  */
  // sealed trait Stepper[-A,+B] {
  //   @annotation.tailrec
  //   final def step: Stepper.Step[A,B] = this match {
  //     case Stepper.Suspend(s) => s().step
  //     case _ => this.asInstanceOf[Stepper.Step[A,B]]
  //   }
  // }
  //
  // object Stepper {
  //   private[fs2] final case class Suspend[A,B](force: () => Stepper[A,B]) extends Stepper[A,B]
  //
  //   /** Algebra describing the result of stepping a pure pipe. */
  //   sealed trait Step[-A,+B] extends Stepper[A,B]
  //   /** Pipe indicated it is done. */
  //   final case object Done extends Step[Any,Nothing]
  //   /** Pipe failed with the specified exception. */
  //   final case class Fail(err: Throwable) extends Step[Any,Nothing]
  //   /** Pipe emitted a chunk of elements. */
  //   final case class Emits[A,B](chunk: Chunk[B], next: Stepper[A,B]) extends Step[A,B]
  //   /** Pipe is awaiting input. */
  //   final case class Await[A,B](receive: Option[Chunk[A]] => Stepper[A,B]) extends Step[A,B]
  // }

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
