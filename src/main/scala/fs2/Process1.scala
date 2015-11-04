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

  /** Map/filter simultaneously. Calls `collect` on each `Chunk` in the stream. */
  def collect[F[_],I,I2](pf: PartialFunction[I, I2]): Stream[F,I] => Stream[F,I2] =
    mapChunks(_.collect(pf))

  /** Skip the first element that matches the predicate. */
  def delete[F[_],I](p: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull { h => Pull.takeWhile((i:I) => !p(i))(h).flatMap(Pull.drop(1)).flatMap(Pull.echo) }

  /** Drop `n` elements of the input, then echo the rest. */
  def drop[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull (h => Pull.drop(n)(h) flatMap Pull.echo)

  /** Drop the elements of the input until the predicate `p` fails, then echo the rest. */
  def dropWhile[F[_], I](p: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull (h => Pull.dropWhile(p)(h) flatMap Pull.echo)

  /** Emit only inputs which match the supplied predicate. */
  def filter[F[_], I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    mapChunks(_ filter f)

  /** Emits the first input (if any) which matches the supplied predicate, to the output of the returned `Pull` */
  def find[F[_],I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull { h => Pull.find(f)(h).flatMap { case o #: h => Pull.output1(o) }}

  /**
   * Folds all inputs using an initial value `z` and supplied binary operator,
   * and emits a single element stream.
   */
  def fold[F[_],I,O](z: O)(f: (O, I) => O): Stream[F,I] => Stream[F,O] =
    _ pull { h => Pull.fold(z)(f)(h).flatMap(Pull.output1) }

  /**
   * Folds all inputs using the supplied binary operator, and emits a single-element
   * stream, or the empty stream if the input is empty.
   */
  def fold1[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] =
    _ pull { h => Pull.fold1(f)(h).flatMap(Pull.output1) }

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

  /** Output a transformed version of all chunks from the input `Handle`. */
  def mapChunks[F[_],I,O](f: Chunk[I] => Chunk[O]): Stream[F,I] => Stream[F,O] =
    _ repeatPull { _.await.flatMap { case chunk #: h => Pull.output(f(chunk)) as h }}

  /** Alias for `[[process1.fold1]]` */
  def reduce[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] = fold1(f)

  /**
   * Left fold which outputs all intermediate results. Example:
   *   `Stream(1,2,3,4) pipe process1.scan(0)(_ + _) == Stream(0,1,3,6,10)`.
   *
   * More generally:
   *   `Stream().scan(z)(f) == Stream(z)`
   *   `Stream(x1).scan(z)(f) == Stream(z, f(z,x1))`
   *   `Stream(x1,x2).scan(z)(f) == Stream(z, f(z,x1), f(f(z,x1),x2))
   *   etc
   *
   * Works in a chunky fashion, and creates a `Chunk.indexedSeq` for each converted chunk.
   */
  def scan[F[_],I,O](z: O)(f: (O, I) => O): Stream[F,I] => Stream[F,O] = {
    _ pull (_scan0(z)(f))
  }

  private def _scan0[F[_],O,I](z: O)(f: (O, I) => O): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    h => h.await.optional flatMap {
      case Some(chunk #: h) =>
        val s = chunk.scanLeft(z)(f)
        Pull.output(s) >> _scan1(s(s.size - 1))(f)(h)
      case None => Pull.output(Chunk.singleton(z)) as Handle.empty
    }
  private def _scan1[F[_],O,I](z: O)(f: (O, I) => O): Handle[F,I] => Pull[F,O,Handle[F,I]] =
    Pull.receive { case chunk #: h =>
      val s = chunk.scanLeft(z)(f).drop(1)
      Pull.output(s) >> _scan1(s(s.size - 1))(f)(h)
    }

  /**
   * Like `[[process1.scan]]`, but uses the first element of the stream as the seed.
   */
  def scan1[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] =
    _ pull { Pull.receive1 { case o #: h => _scan0(o)(f)(h) }}

  /** Emit the first `n` elements of the input `Handle` and return the new `Handle`. */
  def take[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull Pull.take(n)

  /** Emit the longest prefix of the input for which all elements test true according to `f`. */
  def takeWhile[F[_],I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull Pull.takeWhile(f)

  /** Convert the input to a stream of solely 1-element chunks. */
  def unchunk[F[_],I]: Stream[F,I] => Stream[F,I] =
    _ repeatPull { Pull.receive1 { case i #: h => Pull.output1(i) as h }}

  /** Zip the elements of the input `Handle `with its indices, and return the new `Handle` */
  def zipWithIndex[F[_],I]: Stream[F,I] => Stream[F,(I,Int)] = {
    def go(n: Int): Handle[F, I] => Pull[F, (I, Int), Handle[F, I]] = {
      Pull.receive { case chunk #: h =>
        Pull.output(chunk.zipWithIndex.map({ case (c, i) => (c, i + n) })) >> go(n + chunk.size)(h)
      }
    }
    _ pull (go(0))
  }

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
