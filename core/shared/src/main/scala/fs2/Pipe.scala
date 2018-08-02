package fs2

import cats.effect.Concurrent
import fs2.async.mutable.Queue
import fs2.internal.FreeC
import fs2.internal.FreeC.ViewL

import scala.util.control.NonFatal

object Pipe {

  /** Creates a [[Stepper]], which allows incrementally stepping a pure pipe. */
  def stepper[I, O](p: Pipe[Pure, I, O]): Stepper[I, O] = {
    type ReadChunk[R] = Option[Chunk[I]] => R
    type Read[R] = FreeC[ReadChunk, R]
    type UO = Option[(Chunk[O], Stream[Read, O])]

    def prompts: Stream[Read, I] =
      Stream
        .eval[Read, Option[Chunk[I]]](FreeC.Eval(identity))
        .flatMap {
          case None        => Stream.empty
          case Some(chunk) => Stream.chunk(chunk).append(prompts)
        }

    // Steps `s` without overhead of resource tracking
    def stepf(s: Stream[Read, O]): Read[UO] =
      s.pull.uncons
        .flatMap {
          case Some((hd, tl)) => Pull.output1((hd, tl))
          case None           => Pull.done
        }
        .stream
        .compile
        .last

    def go(s: Read[UO]): Stepper[I, O] = Stepper.Suspend { () =>
      s.viewL match {
        case FreeC.Result.Pure(None)           => Stepper.Done
        case FreeC.Result.Pure(Some((hd, tl))) => Stepper.Emits(hd, go(stepf(tl)))
        case FreeC.Result.Fail(t)              => Stepper.Fail(t)
        case FreeC.Result.Interrupted(_, err) =>
          err
            .map { t =>
              Stepper.Fail(t)
            }
            .getOrElse(Stepper.Done)
        case view: ViewL.View[ReadChunk, x, UO] =>
          Stepper.Await(
            chunk =>
              try go(view.next(FreeC.Result.Pure(view.step(chunk))))
              catch { case NonFatal(t) => go(view.next(FreeC.Result.Fail(t))) })
        case e =>
          sys.error(
            "FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
      }
    }
    go(stepf(prompts.through(p)))
  }

  /**
    * Allows stepping of a pure pipe. Each invocation of [[step]] results in
    * a value of the [[Stepper.Step]] algebra, indicating that the pipe is either done, it
    * failed with an exception, it emitted a chunk of output, or it is awaiting input.
    */
  sealed abstract class Stepper[-A, +B] {
    @annotation.tailrec
    final def step: Stepper.Step[A, B] = this match {
      case Stepper.Suspend(s) => s().step
      case _                  => this.asInstanceOf[Stepper.Step[A, B]]
    }
  }

  object Stepper {
    private[fs2] final case class Suspend[A, B](force: () => Stepper[A, B]) extends Stepper[A, B]

    /** Algebra describing the result of stepping a pure pipe. */
    sealed abstract class Step[-A, +B] extends Stepper[A, B]

    /** Pipe indicated it is done. */
    final case object Done extends Step[Any, Nothing]

    /** Pipe failed with the specified exception. */
    final case class Fail(err: Throwable) extends Step[Any, Nothing]

    /** Pipe emitted a chunk of elements. */
    final case class Emits[A, B](chunk: Chunk[B], next: Stepper[A, B]) extends Step[A, B]

    /** Pipe is awaiting input. */
    final case class Await[A, B](receive: Option[Chunk[A]] => Stepper[A, B]) extends Step[A, B]
  }

  /** Queue based version of [[join]] that uses the specified queue. */
  def joinQueued[F[_], A, B](q: F[Queue[F, Option[Chunk[A]]]])(s: Stream[F, Pipe[F, A, B]])(
      implicit F: Concurrent[F]): Pipe[F, A, B] =
    in => {
      for {
        done <- Stream.eval(async.signalOf(false))
        q <- Stream.eval(q)
        b <- in.chunks
          .map(Some(_))
          .evalMap(q.enqueue1)
          .drain
          .onFinalize(q.enqueue1(None))
          .onFinalize(done.set(true))
          .merge(done.interrupt(s).flatMap { f =>
            f(q.dequeue.unNoneTerminate.flatMap { x =>
              Stream.chunk(x)
            })
          })
      } yield b
    }

  /** Asynchronous version of [[join]] that queues up to `maxQueued` elements. */
  def joinAsync[F[_]: Concurrent, A, B](maxQueued: Int)(
      s: Stream[F, Pipe[F, A, B]]): Pipe[F, A, B] =
    joinQueued[F, A, B](async.boundedQueue(maxQueued))(s)

  /**
    * Joins a stream of pipes in to a single pipe.
    * Input is fed to the first pipe until it terminates, at which point input is
    * fed to the second pipe, and so on.
    */
  def join[F[_]: Concurrent, A, B](s: Stream[F, Pipe[F, A, B]]): Pipe[F, A, B] =
    joinQueued[F, A, B](async.synchronousQueue)(s)
}
