package streams

object process1 {

  trait Process1[-I,+O] {
    def apply[F[_]]: Stream[F,I] => Stream[F,O]
  }

  object Process1 {
    // implicit class Process1Ops[I,+O](p: )
  }

  // nb: methods are in alphabetical order, there are going to be so many that
  // any other order will just going get confusing

  /** Await a single value, returning `None` if the input has been exhausted. */
  //def await1Option[I]: Process1[I, Option[I]] = new Mk[I, Option[I]] {
  //  def run[F[_]] = h => for {
  //    hd #: h <- h.await1 or
  //    _ <- Pull.write1(hd)
  //  } yield h
  //}

  /** Passes through `n` elements of the input, then halts. */
  //def take[I](n: Int): Process1[I, I] = repeat { new Mk[I,I] { def run[F[_]] =
  //  h => for {
  //    hd #: h <- h.await1 // todo, preserve chunkiness
  //    _ <- Pull.write1(hd)
  //  } yield h
  //}}

  def id[A]: Process1[A,A] =
    new Process1[A,A] { def apply[F[_]] = _.open.flatMap(pull1.id).run }

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
