package fs2

object process1 {

  trait Process1[-I,+O] {
    def run[F[_]]: Stream[F,I] => Stream[F,O]
    def apply[F[_]](s: Stream[F,I]): Stream[F,O] = run(s)
  }

  // nb: methods are in alphabetical order

  def id[I]: Process1[I,I] =
    new Process1[I,I] { def run[F[_]] = _.open.flatMap(Pull.id).run }

  def take[I](n: Int): Process1[I,I] =
    new Process1[I,I] { def run[F[_]] = _.open.flatMap(Pull.take(n)).run }

  // TODO, conversion Process1[I,O] to Stepper[I,O]

  sealed trait Stepper[-A,+B] {
    import Stepper._
    def step: Step[A,B] = this match {
      case Suspend(s) => s()
      case _ => this.asInstanceOf[Step[A,B]]
    }
  }

  object Stepper {
    case class Suspend[A,B](force: () => Step[A,B]) extends Stepper[A,B]

    trait Step[-A,+B] extends Stepper[A,B]
    case class Await[A,B](receive: Option[Chunk[A]] => Stepper[A,B]) extends Stepper[A,B]
    case object Done extends Stepper[Any,Nothing]
    case class Fail(err: Throwable) extends Stepper[Any,Nothing]
    case class Emits[A,B](chunk: Chunk[B], next: Stepper[A,B]) extends Stepper[A,B]
  }
}
