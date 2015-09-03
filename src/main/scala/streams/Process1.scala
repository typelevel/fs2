package streams

import Step._

object process1 {

  private[streams] trait Process1[-I,+O] {
    /** Making this a type member since only code that calls `run` will
     *  need to be aware of it. Also for compatibility. */
    type I2 >: I

    def run[F[_]]: Stream.Handle[F,I] => Pull[F,O,Stream.Handle[F,I2]]

    def apply[F[_]](s: Stream[F,I]): Stream[F,O] = Pull.run {
      s.open.flatMap { run }
    }
  }

  abstract class Mk[A,+B] extends Process1[A,B] { type I2 = A }

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
  def take[I](n: Int): Process1[I, I] = repeat { new Mk[I,I] { def run[F[_]] =
    h => for {
      hd #: h <- h.await1 // todo, preserve chunkiness
      _ <- Pull.write1(hd)
    } yield h
  }}

  def id[A]: Process1[A,A] = repeat { new Mk[A,A] { def run[F[_]] =
    h => for {
      chunk #: h <- h.await
      _ <- Pull.write(chunk)
    } yield h
  }}

  def repeat[I,O](p: Process1[I,O] { type I2 = I }): Process1[I,O] = new Mk[I,O] {
    def run[F[_]] = Pull.loop(p.run[F])
  }

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
