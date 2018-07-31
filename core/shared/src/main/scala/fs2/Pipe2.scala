package fs2

import fs2.internal.FreeC
import scala.util.control.NonFatal

object Pipe2 {

  /** Creates a [[Stepper]], which allows incrementally stepping a pure `Pipe2`. */
  def stepper[I, I2, O](p: Pipe2[Pure, I, I2, O]): Stepper[I, I2, O] = {
    type ReadChunk[R] =
      Either[Option[Chunk[I]] => R, Option[Chunk[I2]] => R]
    type Read[R] = FreeC[ReadChunk, R]
    type UO = Option[(Chunk[O], Stream[Read, O])]

    def prompts[X](id: ReadChunk[Option[Chunk[X]]]): Stream[Read, X] =
      Stream.eval[Read, Option[Chunk[X]]](FreeC.Eval(id)).flatMap {
        case None        => Stream.empty
        case Some(chunk) => Stream.chunk(chunk).append(prompts(id))
      }
    def promptsL: Stream[Read, I] = prompts[I](Left(identity))
    def promptsR: Stream[Read, I2] = prompts[I2](Right(identity))

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

    def go(s: Read[UO]): Stepper[I, I2, O] = Stepper.Suspend { () =>
      s.viewL.get match {
        case FreeC.Result.Pure(None)           => Stepper.Done
        case FreeC.Result.Pure(Some((hd, tl))) => Stepper.Emits(hd, go(stepf(tl)))
        case FreeC.Result.Fail(t)              => Stepper.Fail(t)
        case FreeC.Result.Interrupted(_, err) =>
          err
            .map { t =>
              Stepper.Fail(t)
            }
            .getOrElse(Stepper.Done)
        case bound: FreeC.Bind[ReadChunk, _, UO] =>
          val f = bound.asInstanceOf[FreeC.Bind[ReadChunk, Any, UO]].f
          val fx = bound.fx.asInstanceOf[FreeC.Eval[ReadChunk, UO]].fr
          fx match {
            case Left(recv) =>
              Stepper.AwaitL(
                chunk =>
                  try go(f(FreeC.Result.Pure(recv(chunk))))
                  catch { case NonFatal(t) => go(f(FreeC.Result.Fail(t))) })
            case Right(recv) =>
              Stepper.AwaitR(
                chunk =>
                  try go(f(FreeC.Result.Pure(recv(chunk))))
                  catch { case NonFatal(t) => go(f(FreeC.Result.Fail(t))) })
          }
        case e =>
          sys.error(
            "FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
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
  sealed abstract class Stepper[-I, -I2, +O] {
    import Stepper._
    @annotation.tailrec
    final def step: Step[I, I2, O] = this match {
      case Suspend(s) => s().step
      case _          => this.asInstanceOf[Step[I, I2, O]]
    }
  }

  object Stepper {
    private[fs2] final case class Suspend[I, I2, O](force: () => Stepper[I, I2, O])
        extends Stepper[I, I2, O]

    /** Algebra describing the result of stepping a pure `Pipe2`. */
    sealed abstract class Step[-I, -I2, +O] extends Stepper[I, I2, O]

    /** Pipe indicated it is done. */
    final case object Done extends Step[Any, Any, Nothing]

    /** Pipe failed with the specified exception. */
    final case class Fail(err: Throwable) extends Step[Any, Any, Nothing]

    /** Pipe emitted a chunk of elements. */
    final case class Emits[I, I2, O](chunk: Chunk[O], next: Stepper[I, I2, O])
        extends Step[I, I2, O]

    /** Pipe is awaiting input from the left. */
    final case class AwaitL[I, I2, O](receive: Option[Chunk[I]] => Stepper[I, I2, O])
        extends Step[I, I2, O]

    /** Pipe is awaiting input from the right. */
    final case class AwaitR[I, I2, O](receive: Option[Chunk[I2]] => Stepper[I, I2, O])
        extends Step[I, I2, O]
  }
}
