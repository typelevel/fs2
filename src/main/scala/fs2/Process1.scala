package fs2

import Stream.Handle
import Step._
import fs2.util.{Free,Functor,NotNothing,Sub1}

object process1 {

  /**
   * A `Process1` is just an ordinary function that accepts an open `Stream`
   * as input, outputs `O` values, and returns a new `Handle` when it is
   * done reading.
   */
  type Process1[I,+O] = Handle[Pure,I] => Pull[Pure,O,Handle[Pure,I]]
  // type Tee[I,I2,+O] = (Handle[Pure,I], Handle[Pure,I2]) => Pull[Pure,O,(Handle[Pure,I],Handle[Pure,I2])]

  // nb: methods are in alphabetical order

  def receive[F[_],I,O](f: Step[Chunk[I],Handle[F, I]] => Pull[F,O,Handle[F,I]]): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    _.await.flatMap(f)

  def receive1[F[_],I,O](f: Step[I,Handle[F, I]] => Pull[F,O,Handle[F,I]]): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    _.await1.flatMap(f)

  /** Output all chunks from the input `Handle`. */
  def chunks[F[_],I](implicit F: NotNothing[F]): Handle[F,I] => Pull[F,Chunk[I],Handle[F,I]] =
    receive { case chunk #: h => Pull.output1(chunk) >> chunks.apply(h) }

  /** Output a transformed version of all chunks from the input `Handle`. */
  def mapChunks[F[_],I,O](f: Chunk[I] => Chunk[O])(implicit F: NotNothing[F])
  : Handle[F,I] => Pull[F,O,Handle[F,I]]
  = receive { case chunk #: h => Pull.output(f(chunk)) >> mapChunks(f).apply(h) }

  /** Skip the first element that matches the predicate. */
  def delete[F[_],I](p: I => Boolean)(implicit F: NotNothing[F]): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { case chunk #: h =>
      chunk.indexWhere(p) match {
        case Some(i) =>
          val (before, after) = (chunk.take(i), chunk.drop(i + 1))
          Pull.output(before) >> Pull.output(after) >> id.apply(h)
        case None => Pull.output(chunk) >> delete(p).apply(h)
      }
    }

  /** Emit inputs which match the supplied predicate to the output of the returned `Pull` */
  def filter[F[_],I](f: I => Boolean)(implicit F: NotNothing[F]): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    mapChunks(_ filter f)

  /** Write all inputs to the output of the returned `Pull`. */
  def id[F[_],I](implicit F: NotNothing[F]): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    Pull.echo[F,I]

  /** Return the last element of the input `Handle`, if nonempty. */
  def last[F[_],I](implicit F: NotNothing[F]): Handle[F,I] => Pull[F,Option[I],Handle[F,I]] =
    h => Pull.last.apply(h).flatMap { o => Pull.output1(o) >> Pull.done }

  /**
   * Write all inputs to the output of the returned `Pull`, transforming elements using `f`.
   * Works in a chunky fashion and creates a `Chunk.indexedSeq` for each mapped chunk.
   */
  def lift[F[_],I,O](f: I => O)(implicit F: NotNothing[F]): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    receive { case chunk #: h => Pull.output(chunk map f) >> lift(f).apply(h) }

  /** Emit the first `n` elements of the input `Handle` and return the new `Handle`. */
  def take[F[_],I](n: Long)(implicit F: NotNothing[F]): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    h =>
      if (n <= 0) Pull.done
      else Pull.awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue)(h).flatMap {
        case chunk #: h => Pull.output(chunk) >> take(n - chunk.size.toLong).apply(h)
      }
    
  /** Emit the elements of the input `Handle` until the predicate `p` fails, and return the new `Handle`. */      
  def takeWhile[F[_], I](p: I => Boolean)(implicit F: NotNothing[F]): Handle[F, I] => Pull[F, I, Handle[F, I]] = 
    h => 
      Pull.await(h) flatMap {
        case chunk #: h =>
          chunk.indexWhere(!p(_)) match {
            case Some(0) => Pull.done
            case Some(i) => Pull.output(chunk.take(i)) >> Pull.done
            case None    => Pull.output(chunk) >> takeWhile(p).apply(h)
          }
      }
     
  /** Drop the first `n` elements of the input `Handle`, and return the new `Handle`. */    
  def drop[F[_], I](n: Long)(implicit F: NotNothing[F]): Handle[F, I] => Pull[F, I, Handle[F, I]] = 
    h =>
      if (n <= 0) id.apply(h)
      else Pull.awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue)(h).flatMap {
        case chunk #: h => drop(n - chunk.size).apply(h)
      }
      
  /** Drop the elements of the input `Handle` until the predicate `p` fails, and return the new `Handle`. */            
  def dropWhile[F[_], I](p: I => Boolean)(implicit F: NotNothing[F]): Handle[F, I] => Pull[F, I, Handle[F, I]] =
    h => 
      Pull.await(h) flatMap {
        case chunk #: h =>
          chunk.indexWhere(!p(_)) match {     
            case Some(i) => Pull.output(chunk.drop(i)) >> id.apply(h)            
            case None    => dropWhile(p).apply(h)
          }
      }
      
  /** Convert the input to a stream of solely 1-element chunks. */
  def unchunk[F[_],I](implicit F: NotNothing[F]): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive1 { case i #: h => Pull.output1(i) >> unchunk.apply(h) }

  // stepping a process

  def covary[F[_],I,O](p: Process1[I,O]): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    p.asInstanceOf[Handle[F,I] => Pull[F,O,Handle[F,I]]]

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

    def outputs: Stream[Read,O] = prompts pull covary[Read,I,O](p)
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
