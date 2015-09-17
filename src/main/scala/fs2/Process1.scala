package fs2

import Stream.Handle
import Step._

object process1 {

  trait Process1[-I,+O] {
    def run[F[_]]: Stream[F,I] => Stream[F,O]
    def apply[F[_]](s: Stream[F,I]): Stream[F,O] = run(s)
  }

  // nb: methods are in alphabetical order

  def id[W]: Process1[W,W] =
    new Process1[W,W] { def run[F[_]] = _.pull[F,W](Pull.id) }

  def lift[W,W2](f: W => W2): Process1[W,W2] =
    new Process1[W,W2] { def run[F[_]] = _.pull(Pull.lift(f)) }

  def take[W](n: Int): Process1[W,W] =
    new Process1[W,W] { def run[F[_]] = _.pull(Pull.take(n)) }

  // TODO, conversion Process1[I,O] to Stepper[I,O]

  trait Read[I] { type f[r] = Option[Chunk[I]] => r }

  private[fs2] def prompts[I]: Stream[Read[I]#f,I] =
    Stream.eval[Read[I]#f,Option[Chunk[I]]](identity).flatMap {
      case None => Stream.empty
      case Some(chunk) => Stream.chunk(chunk)
    }

  def stepper[I,O](p: Process1[I,O]): Stepper[I,O] = {
    def outputs: Stream[Read[I]#f,O] = p[Read[I]#f](prompts[I])
    def stepf(s: Handle[Read[I]#f,O]): Free[Read[I]#f, Option[Step[Chunk[O],Handle[Read[I]#f, O]]]]
    = s.buffer match {
        case hd :: tl => Free.pure(Some(Step(hd, new Handle[Read[I]#f,O](tl, s.stream))))
        case List() => s.stream.step.flatMap { s => Pull.write1(s) }
         .run.runFold(None: Option[Step[Chunk[O],Handle[Read[I]#f, O]]])(
          (_,s) => Some(s))
      }
    def go(s: Free[Read[I]#f, Option[Step[Chunk[O],Handle[Read[I]#f, O]]]]): Stepper[I,O] =
      Stepper.Suspend { () =>
        s match {
          case Free.Pure(None) => Stepper.Done
          case Free.Pure(Some(s)) => Stepper.Emits(s.head, go(stepf(s.tail)))
          case Free.Fail(err) => Stepper.Fail(err)
        }
      }
    go(stepf(new Handle[Read[I]#f,O](List(), outputs)))
  }

  sealed trait Stepper[-A,+B] {
    import Stepper._
    @annotation.tailrec
    final def step: Step[A,B] = this match {
      case Suspend(s) => s().step
      case _ => this.asInstanceOf[Step[A,B]]
    }
  }

  object Stepper {
    case class Suspend[A,B](force: () => Stepper[A,B]) extends Stepper[A,B]

    sealed trait Step[-A,+B] extends Stepper[A,B]
    case object Done extends Step[Any,Nothing]
    case class Fail(err: Throwable) extends Step[Any,Nothing]
    case class Emits[A,B](chunk: Chunk[B], next: Stepper[A,B]) extends Step[A,B]
    case class Await[A,B](receive: Option[Chunk[A]] => Stepper[A,B]) extends Step[A,B]
  }
}
