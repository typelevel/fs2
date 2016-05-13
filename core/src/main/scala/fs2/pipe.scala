package fs2

import Stream.Handle
import fs2.util.{Free,Functor,Sub1}

object pipe {

  // nb: methods are in alphabetical order

  /** Outputs first value, and then any changed value from the last value. `eqf` is used for equality. **/
  def changes[F[_],I](eqf:(I,I) => Boolean): Stream[F,I] => Stream[F,I] =
    zipWithPrevious andThen collect {
      case (None,next) => next
      case (Some(last), next) if !eqf(last,next) => next
    }

  /** Outputs chunks with a limited maximum size, splitting as necessary. */
  def chunkLimit[F[_],I](n: Int): Stream[F,I] => Stream[F,Chunk[I]] =
    _ repeatPull { h => Pull.awaitLimit(n)(h) flatMap { case chunk #: h => Pull.output1(chunk) as h } }

  /** Outputs a list of chunks, the total size of all chunks is limited and split as necessary. */
  def chunkN[F[_],I](n: Int, allowFewer: Boolean = true): Stream[F,I] => Stream[F,List[Chunk[I]]] =
    _ repeatPull { h => Pull.awaitN(n, allowFewer)(h) flatMap { case chunks #: h => Pull.output1(chunks) as h }}

  /** Output all chunks from the input `Handle`. */
  def chunks[F[_],I]: Stream[F,I] => Stream[F,Chunk[I]] =
    _ repeatPull { _.await.flatMap { case chunk #: h => Pull.output1(chunk) as h }}

  /** Map/filter simultaneously. Calls `collect` on each `Chunk` in the stream. */
  def collect[F[_],I,I2](pf: PartialFunction[I, I2]): Stream[F,I] => Stream[F,I2] =
    mapChunks(_.collect(pf))

  /** Emits the first element of the Stream for which the partial function is defined. */
  def collectFirst[F[_],I,I2](pf: PartialFunction[I, I2]): Stream[F,I] => Stream[F,I2] =
    _ pull { h => Pull.find(pf.isDefinedAt)(h) flatMap { case i #: h => Pull.output1(pf(i)) }}

  /** Skip the first element that matches the predicate. */
  def delete[F[_],I](p: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull { h => Pull.takeWhile((i:I) => !p(i))(h).flatMap(Pull.drop(1)).flatMap(Pull.echo) }

  /** Drop `n` elements of the input, then echo the rest. */
  def drop[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull (h => Pull.drop(n)(h) flatMap Pull.echo)

  /** Drop the elements of the input until the predicate `p` fails, then echo the rest. */
  def dropWhile[F[_], I](p: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull (h => Pull.dropWhile(p)(h) flatMap Pull.echo)

  /** Emits `true` as soon as a matching element is received, else `false` if no input matches */
  def exists[F[_], I](p: I => Boolean): Stream[F, I] => Stream[F, Boolean] =
    _ pull { h => Pull.forall[F,I](!p(_))(h) flatMap { i => Pull.output1(!i) }}

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

  /**
   * Emits a single `true` value if all input matches the predicate.
   * Halts with `false` as soon as a non-matching element is received.
   */
  def forall[F[_], I](p: I => Boolean): Stream[F,I] => Stream[F,Boolean] =
    _ pull (h => Pull.forall(p)(h) flatMap Pull.output1)

  /** Write all inputs to the output of the returned `Pull`. */
  def id[F[_],I]: Stream[F,I] => Stream[F,I] =
    s => s

  /** Return the last element of the input `Handle`, if nonempty. */
  def last[F[_],I]: Stream[F,I] => Stream[F,Option[I]] =
    _ pull { h => Pull.last(h).flatMap { o => Pull.output1(o) }}

  /** Return the last element of the input `Handle` if nonempty, otherwise li. */
  def lastOr[F[_],I](li: => I): Stream[F,I] => Stream[F,I] =
    _ pull { h => Pull.last(h).flatMap {
      case Some(o) => Pull.output1(o)
      case None => Pull.output1(li)
    }}

  /**
   * Write all inputs to the output of the returned `Pull`, transforming elements using `f`.
   * Works in a chunky fashion and creates a `Chunk.indexedSeq` for each mapped chunk.
   */
  def lift[F[_],I,O](f: I => O): Stream[F,I] => Stream[F,O] =
    _ map f

  /** Output a transformed version of all chunks from the input `Handle`. */
  def mapChunks[F[_],I,O](f: Chunk[I] => Chunk[O]): Stream[F,I] => Stream[F,O] =
    _ repeatPull { _.await.flatMap { case chunk #: h => Pull.output(f(chunk)) as h }}

  /**
    * Maps a running total according to `S` and the input with the function `f`.
    *
    * @example {{{
    * scala> Stream("Hello", "World")
    *      |   .mapAccumulate(0)((l, s) => (l + s.length, s.head)).toVector
    * res0: Vector[(Int, Char)] = Vector((5,H), (10,W))
    * }}}
    */
  def mapAccumulate[F[_],S,I,O](init: S)(f: (S,I) => (S,O)): Stream[F,I] => Stream[F,(S,O)] =
    _ pull Pull.receive { case chunk #: h =>
      val f2 = (s: S, i: I) => {
        val (newS, newO) = f(s, i)
        (newS, (newS, newO))
      }
      val (s, o) = chunk.mapAccumulate(init)(f2)
      Pull.output(o) >> _mapAccumulate0(s)(f2)(h)
    }
  private def _mapAccumulate0[F[_],S,I,O](init: S)(f: (S,I) => (S,(S,O))): Handle[F,I] => Pull[F,(S,O),Handle[F,I]] =
    Pull.receive { case chunk #: h =>
      val (s, o) = chunk.mapAccumulate(init)(f)
      Pull.output(o) >> _mapAccumulate0(s)(f)(h)
    }

  /**
   * Behaves like `id`, but starts fetching the next chunk before emitting the current,
   * enabling processing on either side of the `prefetch` to run in parallel.
   */
  def prefetch[F[_]:Async,I]: Stream[F,I] => Stream[F,I] =
    _ repeatPull { _.receive {
      case hd #: tl => Pull.prefetch(tl) flatMap { p => Pull.output(hd) >> p }}}

  /** Rethrow any `Left(err)`. Preserves chunkiness. */
  def rethrow[F[_],I]: Stream[F,Either[Throwable,I]] => Stream[F,I] =
    _.chunks.flatMap { es =>
      val errs = es collect { case Left(e) => e }
      val ok = es collect { case Right(i) => i }
      errs.uncons match {
        case None => Stream.chunk(ok)
        case Some((err, _)) => Stream.fail(err) // only first error is reported
      }
    }

  /** Alias for `[[pipe.fold1]]` */
  def reduce[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] = fold1(f)

  /**
   * Left fold which outputs all intermediate results. Example:
   *   `Stream(1,2,3,4) through pipe.scan(0)(_ + _) == Stream(0,1,3,6,10)`.
   *
   * More generally:
   *   `Stream().scan(z)(f) == Stream(z)`
   *   `Stream(x1).scan(z)(f) == Stream(z, f(z,x1))`
   *   `Stream(x1,x2).scan(z)(f) == Stream(z, f(z,x1), f(f(z,x1),x2))`
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
   * Like `[[pipe.scan]]`, but uses the first element of the stream as the seed.
   */
  def scan1[F[_],I](f: (I, I) => I): Stream[F,I] => Stream[F,I] =
    _ pull { Pull.receive1 { case o #: h => _scan0(o)(f)(h) }}

  /** Emit the given values, then echo the rest of the input. */
  def shiftRight[F[_],I](head: I*): Stream[F,I] => Stream[F,I] =
    _ pull { h => Pull.echo(h.push(Chunk.indexedSeq(Vector(head: _*)))) }

  /** Writes the sum of all input elements, or zero if the input is empty. */
  def sum[F[_],I](implicit ev: Numeric[I]): Stream[F,I] => Stream[F,I] =
    fold(ev.zero)(ev.plus)

  /** Emits all elements of the input except the first one. */
  def tail[F[_],I]: Stream[F,I] => Stream[F,I] =
    drop(1)

  /** Emit the first `n` elements of the input `Handle` and return the new `Handle`. */
  def take[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull Pull.take(n)

  /** Emits the last `n` elements of the input. */
  def takeRight[F[_],I](n: Long): Stream[F,I] => Stream[F,I] =
    _ pull { h => Pull.takeRight(n)(h).flatMap(is => Pull.output(Chunk.indexedSeq(is))) }

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough[F[_],I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull Pull.takeThrough(f)

  /** Emit the longest prefix of the input for which all elements test true according to `f`. */
  def takeWhile[F[_],I](f: I => Boolean): Stream[F,I] => Stream[F,I] =
    _ pull Pull.takeWhile(f)

  /** Convert the input to a stream of solely 1-element chunks. */
  def unchunk[F[_],I]: Stream[F,I] => Stream[F,I] =
    _ repeatPull { Pull.receive1 { case i #: h => Pull.output1(i) as h }}

  /**
   * Halt the input stream at the first `None`.
   *
   * @example {{{
   * scala> unNoneTerminate(Stream(Some(1), Some(2), None, Some(3), None)).toVector
   * res0: Vector[Int] = Vector(1, 2)
   * }}}
   */
  def unNoneTerminate[F[_],I]: Stream[F,Option[I]] => Stream[F,I] =
    _ repeatPull { _.receive {
      case hd #: tl =>
        val out = Chunk.indexedSeq(hd.toVector.takeWhile { _.isDefined }.collect { case Some(i) => i })
        if (out.size == hd.size) Pull.output(out) as tl
        else if (out.isEmpty) Pull.done
        else Pull.output(out) >> Pull.done
    }}

  /**
   * Groups inputs into separate `Vector` objects of size `n`.
   *
   * @example {{{
   * scala> Stream(1, 2, 3, 4, 5).vectorChunkN(2).toVector
   * res0: Vector[Vector[Int]] = Vector(Vector(1, 2), Vector(3, 4), Vector(5))
   * }}}
   */
  def vectorChunkN[F[_],I](n: Int, allowFewer: Boolean = true): Stream[F,I] => Stream[F,Vector[I]] =
    chunkN(n, allowFewer) andThen (_.map(i => i.foldLeft(Vector.empty[I])((v, c) => v ++ c.iterator)))

  /** Zip the elements of the input `Handle` with its indices, and return the new `Handle` */
  def zipWithIndex[F[_],I]: Stream[F,I] => Stream[F,(I,Int)] = {
    mapAccumulate[F, Int, I, I](-1) {
      case (i, x) => (i + 1, x)
    } andThen(_.map(_.swap))
  }

  /**
    * Zip the elements of the input `Handle` with its next element wrapped into `Some`, and return the new `Handle`.
    * The last element is zipped with `None`.
    */
  def zipWithNext[F[_], I]: Stream[F, I] => Stream[F, (I, Option[I])] = {
    def go(last: I): Handle[F, I] => Pull[F, (I, Option[I]), Handle[F, I]] =
      Pull.receiveOption {
        case None => Pull.output1((last, None)) as Handle.empty
        case Some(chunk #: h) =>
          val (newLast, zipped) = chunk.mapAccumulate(last) {
            case (prev, next) => (next, (prev, Some(next)))
          }
          Pull.output(zipped) >> go(newLast)(h)
      }
    _ pull Pull.receive1 { case head #: h => go(head)(h) }
  }

  /**
    * Zip the elements of the input `Handle` with its previous element wrapped into `Some`, and return the new `Handle`.
    * The first element is zipped with `None`.
    */
  def zipWithPrevious[F[_], I]: Stream[F, I] => Stream[F, (Option[I], I)] = {
    mapAccumulate[F, Option[I], I, (Option[I], I)](None) {
      case (prev, next) => (Some(next), (prev, next))
    } andThen(_.map { case (_, prevNext) => prevNext })
  }

  /**
    * Zip the elements of the input `Handle` with its previous and next elements wrapped into `Some`, and return the new `Handle`.
    * The first element is zipped with `None` as the previous element,
    * the last element is zipped with `None` as the next element.
    */
  def zipWithPreviousAndNext[F[_], I]: Stream[F, I] => Stream[F, (Option[I], I, Option[I])] = {
    (zipWithPrevious[F, I] andThen zipWithNext[F, (Option[I], I)]) andThen {
      _.map {
        case ((prev, that), None) => (prev, that, None)
        case ((prev, that), Some((_, next))) => (prev, that, Some(next))
      }
    }
  }

  /**
   * Zips the input with a running total according to `S`, up to but not including the current element. Thus the initial
   * `z` value is the first emitted to the output:
   * {{{
   * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length).toList
   * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
   * }}}
   *
   * @see [[zipWithScan1]]
   */
  def zipWithScan[F[_],I,S](z: S)(f: (S, I) => S): Stream[F,I] => Stream[F,(I,S)] =
    _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s)) }.map(_._2)

  /**
   * Zips the input with a running total according to `S`, including the current element. Thus the initial
   * `z` value is the first emitted to the output:
   * {{{
   * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length).toList
   * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
   * }}}
   *
   * @see [[zipWithScan]]
   */
  def zipWithScan1[F[_],I,S](z: S)(f: (S, I) => S): Stream[F,I] => Stream[F,(I,S)] =
    _.mapAccumulate(z) { (s,i) => val s2 = f(s,i); (s2, (i,s2)) }.map(_._2)

  // stepping a process

  def covary[F[_],I,O](p: Stream[Pure,I] => Stream[Pure,O]): Pipe[F,I,O] =
    p.asInstanceOf[Pipe[F,I,O]]

  def stepper[I,O](p: Stream[Pure,I] => Stream[Pure,O]): Stepper[I,O] = {
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
