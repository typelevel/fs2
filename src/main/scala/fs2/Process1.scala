package fs2

import Stream.Handle
import Step._
import fs2.util.{Free,Functor,Sub1}

object process1 {

  /**
   * A `Process1` is just an ordinary function that accepts an open `Stream`
   * as input, outputs `O` values, and returns a new `Handle` when it is
   * done reading.
   */
  type Process1[-I,+O] = Stream[Pure,I] => Stream[Pure,O]

  // nb: methods are in alphabetical order

  /** Output all chunks from the input `Handle`. */
  def chunks[F[_],I]: Stream[F,I] => Stream[F,Chunk[I]] =
    _ repeatPull { _.await.flatMap { case chunk #: h => Pull.output1(chunk) as h }}

  /** Output a transformed version of all chunks from the input `Handle`. */
  def mapChunks[F[_],I,O](f: Chunk[I] => Chunk[O]): Stream[F,I] => Stream[F,O] =
    _ repeatPull { _.await.flatMap { case chunk #: h => Pull.output(f(chunk)) as h }}

  /** Emit inputs which match the supplied predicate to the output of the returned `Pull` */
  def filter[F[_], I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    mapChunks(_ filter f)

  /** Write all inputs to the output of the returned `Pull`. */
  def id[F[_],I]: Stream[F,I] => Stream[F,I] =
    s => s

  /** Return the last element of the input `Handle`, if nonempty. */
  def last[F[_],I]: Stream[F,I] => Stream[F,Option[I]] =
    _ pull { h => Pull.last(h).flatMap { o => Pull.output1(o) }}

  /**
   * Write all inputs to the output of the returned `Pull`, transforming elements using `f`.
   * Works in a chunky fashion and creates a `Chunk.indexedSeq` for each mapped chunk.
   */
  def lift[F[_],I,O](f: I => O): Stream[F,I] => Stream[F,O] =
    _ map f

  /** Emit the first `n` elements of the input `Handle` and return the new `Handle`. */
  def take[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull Pull.take(n)

  /** Convert the input to a stream of solely 1-element chunks. */
  def unchunk[F[_],I]: Stream[F,I] => Stream[F,I] =
    _ repeatPull { h => h.await1 flatMap { case i #: h => Pull.output1(i) as h }}

  // stepping a process

  def covary[F[_],I,O](p: Process1[I,O]): Stream[F,I] => Stream[F,O] =
    p.asInstanceOf[Stream[F,I] => Stream[F,O]]

  def stepper[I,O](p: Process1[I,O]): Stepper[I,O] = {
    type Read[+R] = Option[Chunk[I]] => R
    def readFunctor: Functor[Read] = new Functor[Read] {
      def map[A,B](fa: Read[A])(g: A => B): Read[B]
        = fa andThen g
    }
    def prompts: Stream[Read,I] =
      Stream.eval[Read, Option[Chunk[I]]](identity).flatMap[Read,I] {
        case None => Stream.empty
        case Some(chunk) => Stream.chunk(chunk).append[Read,I](prompts)
      }

    def outputs: Stream[Read,O] = covary[Read,I,O](p)(prompts)
    def stepf(s: Handle[Read,O]): Free[Read, Option[Step[Chunk[O],Handle[Read, O]]]]
    = s.buffer match {
        case hd :: tl => Free.pure(Some(Step(hd, new Handle[Read,O](tl, s.stream))))
        case List() => s.stream.step.flatMap { s => Pull.output1(s) }
         .run.runFold(None: Option[Step[Chunk[O],Handle[Read, O]]])(
          (_,s) => Some(s))
      }
    def go(s: Free[Read, Option[Step[Chunk[O],Handle[Read, O]]]]): Stepper[I,O] =
      Stepper.Suspend { () =>
        s.unroll[Read](readFunctor, Sub1.sub1[Read]) match {
          case Free.Unroll.Fail(err) => Stepper.Fail(err)
          case Free.Unroll.Pure(None) => Stepper.Done
          case Free.Unroll.Pure(Some(s)) => Stepper.Emits(s.head, go(stepf(s.tail)))
          case Free.Unroll.Eval(recv) => Stepper.Await(chunk => go(recv(chunk)))
        }
      }
    go(stepf(new Handle[Read,O](List(), outputs)))
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
    private[fs2] case class Suspend[A,B](force: () => Stepper[A,B]) extends Stepper[A,B]

    sealed trait Step[-A,+B] extends Stepper[A,B]
    case object Done extends Step[Any,Nothing]
    case class Fail(err: Throwable) extends Step[Any,Nothing]
    case class Emits[A,B](chunk: Chunk[B], next: Stepper[A,B]) extends Step[A,B]
    case class Await[A,B](receive: Option[Chunk[A]] => Stepper[A,B]) extends Step[A,B]
  }
}
