package fs2

import cats.data.NonEmptyList

import scala.collection.generic.CanBuildFrom
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import cats.{Applicative, Eq, Functor, Id, Monoid, Semigroup, ~>}
import cats.effect.{Effect, IO, Sync, Timer}
import cats.implicits.{catsSyntaxEither => _, _}
import fs2.async.Promise
import fs2.internal.{Algebra, FreeC, Token}

/**
  * A stream producing output of type `O` and which may evaluate `F`
  * effects. If `F` is [[Pure]], the stream evaluates no effects.
  *
  * Much of the API of `Stream` is defined in [[Stream.InvariantOps]].
  *
  * Laws (using infix syntax):
  *
  * `append` forms a monoid in conjunction with `empty`:
  *   - `empty append s == s` and `s append empty == s`.
  *   - `(s1 append s2) append s3 == s1 append (s2 append s3)`
  *
  * And `cons` is consistent with using `++` to prepend a single segment:
  *   - `s.cons(seg) == Stream.segment(seg) ++ s`
  *
  * `Stream.raiseError` propagates until being caught by `handleErrorWith`:
  *   - `Stream.raiseError(e) handleErrorWith h == h(e)`
  *   - `Stream.raiseError(e) ++ s == Stream.raiseError(e)`
  *   - `Stream.raiseError(e) flatMap f == Stream.raiseError(e)`
  *
  * `Stream` forms a monad with `emit` and `flatMap`:
  *   - `Stream.emit >=> f == f` (left identity)
  *   - `f >=> Stream.emit === f` (right identity - note weaker equality notion here)
  *   - `(f >=> g) >=> h == f >=> (g >=> h)` (associativity)
  *  where `Stream.emit(a)` is defined as `segment(Segment.singleton(a)) and
  *  `f >=> g` is defined as `a => a flatMap f flatMap g`
  *
  * The monad is the list-style sequencing monad:
  *   - `(a ++ b) flatMap f == (a flatMap f) ++ (b flatMap f)`
  *   - `Stream.empty flatMap f == Stream.empty`
  *
  * '''Technical notes'''
  *
  * ''Note:'' since the segment structure of the stream is observable, and
  * `s flatMap Stream.emit` produces a stream of singleton segments,
  * the right identity law uses a weaker notion of equality, `===` which
  * normalizes both sides with respect to segment structure:
  *
  *   `(s1 === s2) = normalize(s1) == normalize(s2)`
  *   where `==` is full equality
  *   (`a == b` iff `f(a)` is identical to `f(b)` for all `f`)
  *
  * `normalize(s)` can be defined as `s.flatMap(Stream.emit)`, which just
  * produces a singly-chunked stream from any input stream `s`.
  *
  * ''Note:'' For efficiency `[[Stream.map]]` function operates on an entire
  * segment at a time and preserves segment structure, which differs from
  * the `map` derived from the monad (`s map f == s flatMap (f andThen Stream.emit)`)
  * which would produce singleton segments. In particular, if `f` throws errors, the
  * segmented version will fail on the first ''segment'' with an error, while
  * the unsegmented version will fail on the first ''element'' with an error.
  * Exceptions in pure code like this are strongly discouraged.
  *
  *
  * @hideImplicitConversion PureOps
  * @hideImplicitConversion IdOps
  * @hideImplicitConversion EmptyOps
  * @hideImplicitConversion covaryPure
  */
final class Stream[+F[_], +O] private (private val free: FreeC[Algebra[Nothing, Nothing, ?], Unit])
    extends AnyVal {

  private[fs2] def get[F2[x] >: F[x], O2 >: O]: FreeC[Algebra[F2, O2, ?], Unit] =
    free.asInstanceOf[FreeC[Algebra[F2, O2, ?], Unit]]

  /**
    * Returns a stream of `O` values wrapped in `Right` until the first error, which is emitted wrapped in `Left`.
    *
    * @example {{{
    * scala> (Stream(1,2,3) ++ Stream.raiseError(new RuntimeException) ++ Stream(4,5,6)).attempt.toList
    * res0: List[Either[Throwable,Int]] = List(Right(1), Right(2), Right(3), Left(java.lang.RuntimeException))
    * }}}
    *
    * [[rethrow]] is the inverse of `attempt`, with the caveat that anything after the first failure is discarded.
    */
  def attempt: Stream[F, Either[Throwable, O]] =
    map(Right(_): Either[Throwable, O]).handleErrorWith(e => Stream.emit(Left(e)))

  /**
    * Alias for `_.map(_ => o2)`.
    *
    * @example {{{
    * scala> Stream(1,2,3).as(0).toList
    * res0: List[Int] = List(0, 0, 0)
    * }}}
    */
  def as[O2](o2: O2): Stream[F, O2] = map(_ => o2)

  /**
    * Behaves like the identity function, but requests `n` elements at a time from the input.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> val buf = new scala.collection.mutable.ListBuffer[String]()
    * scala> Stream.range(0, 100).covary[IO].
    *      |   evalMap(i => IO { buf += s">$i"; i }).
    *      |   buffer(4).
    *      |   evalMap(i => IO { buf += s"<$i"; i }).
    *      |   take(10).
    *      |   compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    * scala> buf.toList
    * res1: List[String] = List(>0, >1, >2, >3, <0, <1, <2, <3, >4, >5, >6, >7, <4, <5, <6, <7, >8, >9, >10, >11, <8, <9)
    * }}}
    */
  def buffer(n: Int): Stream[F, O] =
    this.repeatPull {
      _.unconsN(n, allowFewer = true).flatMap {
        case Some((hd, tl)) => Pull.output(hd).as(Some(tl))
        case None           => Pull.pure(None)
      }
    }

  /**
    * Behaves like the identity stream, but emits no output until the source is exhausted.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> val buf = new scala.collection.mutable.ListBuffer[String]()
    * scala> Stream.range(0, 10).covary[IO].
    *      |   evalMap(i => IO { buf += s">$i"; i }).
    *      |   bufferAll.
    *      |   evalMap(i => IO { buf += s"<$i"; i }).
    *      |   take(4).
    *      |   compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 2, 3)
    * scala> buf.toList
    * res1: List[String] = List(>0, >1, >2, >3, >4, >5, >6, >7, >8, >9, <0, <1, <2, <3)
    * }}}
    */
  def bufferAll: Stream[F, O] = bufferBy(_ => true)

  /**
    * Behaves like the identity stream, but requests elements from its
    * input in blocks that end whenever the predicate switches from true to false.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> val buf = new scala.collection.mutable.ListBuffer[String]()
    * scala> Stream.range(0, 10).covary[IO].
    *      |   evalMap(i => IO { buf += s">$i"; i }).
    *      |   bufferBy(_ % 2 == 0).
    *      |   evalMap(i => IO { buf += s"<$i"; i }).
    *      |   compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    * scala> buf.toList
    * res1: List[String] = List(>0, >1, <0, <1, >2, >3, <2, <3, >4, >5, <4, <5, >6, >7, <6, <7, >8, >9, <8, <9)
    * }}}
    */
  def bufferBy(f: O => Boolean): Stream[F, O] = {
    def go(buffer: Catenable[Segment[O, Unit]], last: Boolean, s: Stream[F, O]): Pull[F, O, Unit] =
      s.pull.unconsChunk.flatMap {
        case Some((hd, tl)) =>
          val (out, buf, newLast) = {
            hd.foldLeft((Catenable.empty: Catenable[Chunk[O]], Vector.empty[O], last)) {
              case ((out, buf, last), i) =>
                val cur = f(i)
                if (!cur && last)
                  (out :+ Chunk.vector(buf :+ i), Vector.empty, cur)
                else (out, buf :+ i, cur)
            }
          }
          if (out.isEmpty) {
            go(buffer :+ Segment.vector(buf), newLast, tl)
          } else {
            Pull.output(Segment.catenated(buffer ++ out.map(Segment.chunk))) >> go(
              Catenable.singleton(Segment.vector(buf)),
              newLast,
              tl)
          }
        case None => Pull.output(Segment.catenated(buffer))
      }
    go(Catenable.empty, false, this).stream
  }

  /**
    * Emits only elements that are distinct from their immediate predecessors
    * according to `f`, using natural equality for comparison.
    *
    * Note that `f` is called for each element in the stream multiple times
    * and hence should be fast (e.g., an accessor). It is not intended to be
    * used for computationally intensive conversions. For such conversions,
    * consider something like: `src.map(o => (o, f(o))).changesBy(_._2).map(_._1)`
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> Stream(1,1,2,4,6,9).changesBy(_ % 2).toList
    * res0: List[Int] = List(1, 2, 9)
    * }}}
    */
  def changesBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, O] =
    filterWithPrevious((o1, o2) => eq.neqv(f(o1), f(o2)))

  /**
    * Outputs all chunks from the source stream.
    *
    * @example {{{
    * scala> (Stream(1) ++ Stream(2, 3) ++ Stream(4, 5, 6)).chunks.toList
    * res0: List[Chunk[Int]] = List(Chunk(1), Chunk(2, 3), Chunk(4, 5, 6))
    * }}}
    */
  def chunks: Stream[F, Chunk[O]] =
    this.repeatPull(_.unconsChunk.flatMap {
      case None           => Pull.pure(None)
      case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
    })

  /**
    * Outputs chunk with a limited maximum size, splitting as necessary.
    *
    * @example {{{
    * scala> (Stream(1) ++ Stream(2, 3) ++ Stream(4, 5, 6)).chunkLimit(2).toList
    * res0: List[Chunk[Int]] = List(Chunk(1), Chunk(2, 3), Chunk(4, 5), Chunk(6))
    * }}}
    */
  def chunkLimit(n: Int): Stream[F, Chunk[O]] =
    this.repeatPull {
      _.unconsLimit(n).flatMap {
        case None           => Pull.pure(None)
        case Some((hd, tl)) => Pull.output1(hd.force.toChunk).as(Some(tl))
      }
    }

  /**
    * Filters and maps simultaneously. Calls `collect` on each segment in the stream.
    *
    * @example {{{
    * scala> Stream(Some(1), Some(2), None, Some(3), None, Some(4)).collect { case Some(i) => i }.toList
    * res0: List[Int] = List(1, 2, 3, 4)
    * }}}
    */
  def collect[O2](pf: PartialFunction[O, O2]): Stream[F, O2] =
    mapSegments(_.collect(pf))

  /**
    * Emits the first element of the stream for which the partial function is defined.
    *
    * @example {{{
    * scala> Stream(None, Some(1), Some(2), None, Some(3)).collectFirst { case Some(i) => i }.toList
    * res0: List[Int] = List(1)
    * }}}
    */
  def collectFirst[O2](pf: PartialFunction[O, O2]): Stream[F, O2] =
    this.pull
      .find(pf.isDefinedAt)
      .flatMap {
        case None           => Pull.pure(None)
        case Some((hd, tl)) => Pull.output1(pf(hd)).as(None)
      }
      .stream

  /**
    * Prepends a segment onto the front of this stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).cons(Segment.vector(Vector(-1, 0))).toList
    * res0: List[Int] = List(-1, 0, 1, 2, 3)
    * }}}
    */
  def cons[O2 >: O](s: Segment[O2, Unit]): Stream[F, O2] =
    Stream.segment(s) ++ this

  /**
    * Prepends a chunk onto the front of this stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).consChunk(Chunk.vector(Vector(-1, 0))).toList
    * res0: List[Int] = List(-1, 0, 1, 2, 3)
    * }}}
    */
  def consChunk[O2 >: O](c: Chunk[O2]): Stream[F, O2] =
    if (c.isEmpty) this else Stream.chunk(c) ++ this

  /**
    * Prepends a single value onto the front of this stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).cons1(0).toList
    * res0: List[Int] = List(0, 1, 2, 3)
    * }}}
    */
  def cons1[O2 >: O](o: O2): Stream[F, O2] =
    cons(Segment.singleton(o))

  /**
    * Lifts this stream to the specified output type.
    *
    * @example {{{
    * scala> Stream(Some(1), Some(2), Some(3)).covaryOutput[Option[Int]]
    * res0: Stream[Pure,Option[Int]] = Stream(..)
    * }}}
    */
  def covaryOutput[O2 >: O]: Stream[F, O2] = this.asInstanceOf[Stream[F, O2]]

  /**
    * Skips the first element that matches the predicate.
    *
    * @example {{{
    * scala> Stream.range(1, 10).delete(_ % 2 == 0).toList
    * res0: List[Int] = List(1, 3, 4, 5, 6, 7, 8, 9)
    * }}}
    */
  def delete(p: O => Boolean): Stream[F, O] =
    this.pull
      .takeWhile(o => !p(o))
      .flatMap {
        case None    => Pull.pure(None)
        case Some(s) => s.drop(1).pull.echo
      }
      .stream

  /**
    * Removes all output values from this stream.
    *
    * Often used with `merge` to run one side of the merge for its effect
    * while getting outputs from the opposite side of the merge.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.eval(IO(println("x"))).drain.compile.toVector.unsafeRunSync
    * res0: Vector[Nothing] = Vector()
    * }}}
    */
  def drain: Stream[F, Nothing] = this.mapSegments(_ => Segment.empty)

  /**
    * Drops `n` elements of the input, then echoes the rest.
    *
    * @example {{{
    * scala> Stream.range(0,10).drop(5).toList
    * res0: List[Int] = List(5, 6, 7, 8, 9)
    * }}}
    */
  def drop(n: Long): Stream[F, O] =
    this.pull.drop(n).flatMap(_.map(_.pull.echo).getOrElse(Pull.done)).stream

  /**
    * Drops the last element.
    *
    * @example {{{
    * scala> Stream.range(0,10).dropLast.toList
    * res0: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8)
    * }}}
    */
  def dropLast: Stream[F, O] = dropLastIf(_ => true)

  /**
    * Drops the last element if the predicate evaluates to true.
    *
    * @example {{{
    * scala> Stream.range(0,10).dropLastIf(_ > 5).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8)
    * }}}
    */
  def dropLastIf(p: O => Boolean): Stream[F, O] = {
    def go(last: Chunk[O], s: Stream[F, O]): Pull[F, O, Unit] =
      s.pull.unconsChunk.flatMap {
        case Some((hd, tl)) =>
          if (hd.nonEmpty) Pull.outputChunk(last) >> go(hd, tl)
          else go(last, tl)
        case None =>
          val o = last(last.size - 1)
          if (p(o)) {
            val (prefix, _) = last.splitAt(last.size - 1)
            Pull.outputChunk(prefix)
          } else Pull.outputChunk(last)
      }
    def unconsNonEmptyChunk(s: Stream[F, O]): Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] =
      s.pull.unconsChunk.flatMap {
        case Some((hd, tl)) =>
          if (hd.nonEmpty) Pull.pure(Some((hd, tl)))
          else unconsNonEmptyChunk(tl)
        case None => Pull.pure(None)
      }
    unconsNonEmptyChunk(this).flatMap {
      case Some((hd, tl)) => go(hd, tl)
      case None           => Pull.done
    }.stream
  }

  /**
    * Outputs all but the last `n` elements of the input.
    *
    * @example {{{
    * scala> Stream.range(0,10).dropRight(5).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def dropRight(n: Int): Stream[F, O] =
    if (n <= 0) this
    else {
      def go(acc: Vector[O], s: Stream[F, O]): Pull[F, O, Option[Unit]] =
        s.pull.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            val all = acc ++ hd.force.toVector
            Pull.output(Segment.vector(all.dropRight(n))) >> go(all.takeRight(n), tl)
        }
      go(Vector.empty, this).stream
    }

  /**
    * Like [[dropWhile]], but drops the first value which tests false.
    *
    * @example {{{
    * scala> Stream.range(0,10).dropThrough(_ != 4).toList
    * res0: List[Int] = List(5, 6, 7, 8, 9)
    * }}}
    */
  def dropThrough(p: O => Boolean): Stream[F, O] =
    this.pull
      .dropThrough(p)
      .flatMap(_.map(_.pull.echo).getOrElse(Pull.done))
      .stream

  /**
    * Drops elements from the head of this stream until the supplied predicate returns false.
    *
    * @example {{{
    * scala> Stream.range(0,10).dropWhile(_ != 4).toList
    * res0: List[Int] = List(4, 5, 6, 7, 8, 9)
    * }}}
    */
  def dropWhile(p: O => Boolean): Stream[F, O] =
    this.pull
      .dropWhile(p)
      .flatMap(_.map(_.pull.echo).getOrElse(Pull.done))
      .stream

  /**
    * Emits `true` as soon as a matching element is received, else `false` if no input matches.
    *
    * @example {{{
    * scala> Stream.range(0,10).exists(_ == 4).toList
    * res0: List[Boolean] = List(true)
    * scala> Stream.range(0,10).exists(_ == 10).toList
    * res1: List[Boolean] = List(false)
    * }}}
    */
  def exists(p: O => Boolean): Stream[F, Boolean] =
    this.pull.forall(!p(_)).flatMap(r => Pull.output1(!r)).stream

  /**
    * Emits only inputs which match the supplied predicate.
    *
    * @example {{{
    * scala> Stream.range(0,10).filter(_ % 2 == 0).toList
    * res0: List[Int] = List(0, 2, 4, 6, 8)
    * }}}
    */
  def filter(p: O => Boolean): Stream[F, O] = mapSegments(_.filter(p))

  /**
    * Like `filter`, but the predicate `f` depends on the previously emitted and
    * current elements.
    *
    * @example {{{
    * scala> Stream(1, -1, 2, -2, 3, -3, 4, -4).filterWithPrevious((previous, current) => previous < current).toList
    * res0: List[Int] = List(1, 2, 3, 4)
    * }}}
    */
  def filterWithPrevious(f: (O, O) => Boolean): Stream[F, O] = {
    def go(last: O, s: Stream[F, O]): Pull[F, O, Option[Unit]] =
      s.pull.uncons.flatMap {
        case None           => Pull.pure(None)
        case Some((hd, tl)) =>
          // Check if we can emit this chunk unmodified
          Pull
            .segment(
              hd.fold((true, last)) {
                  case ((acc, last), o) => (acc && f(last, o), o)
                }
                .mapResult(_._2))
            .flatMap {
              case (allPass, newLast) =>
                if (allPass) {
                  Pull.output(hd) >> go(newLast, tl)
                } else {
                  Pull
                    .segment(hd
                      .fold((Vector.empty[O], last)) {
                        case ((acc, last), o) =>
                          if (f(last, o)) (acc :+ o, o)
                          else (acc, last)
                      }
                      .mapResult(_._2))
                    .flatMap {
                      case (acc, newLast) =>
                        Pull.output(Segment.vector(acc)) >> go(newLast, tl)
                    }
                }
            }
      }
    this.pull.uncons1.flatMap {
      case None           => Pull.pure(None)
      case Some((hd, tl)) => Pull.output1(hd) >> go(hd, tl)
    }.stream
  }

  /**
    * Emits the first input (if any) which matches the supplied predicate.
    *
    * @example {{{
    * scala> Stream.range(1,10).find(_ % 2 == 0).toList
    * res0: List[Int] = List(2)
    * }}}
    */
  def find(f: O => Boolean): Stream[F, O] =
    this.pull
      .find(f)
      .flatMap {
        _.map { case (hd, tl) => Pull.output1(hd) }.getOrElse(Pull.done)
      }
      .stream

  /**
    * Folds all inputs using an initial value `z` and supplied binary operator,
    * and emits a single element stream.
    *
    * @example {{{
    * scala> Stream(1, 2, 3, 4, 5).fold(0)(_ + _).toList
    * res0: List[Int] = List(15)
    * }}}
    */
  def fold[O2](z: O2)(f: (O2, O) => O2): Stream[F, O2] =
    this.pull.fold(z)(f).flatMap(Pull.output1).stream

  /**
    * Folds all inputs using the supplied binary operator, and emits a single-element
    * stream, or the empty stream if the input is empty.
    *
    * @example {{{
    * scala> Stream(1, 2, 3, 4, 5).fold1(_ + _).toList
    * res0: List[Int] = List(15)
    * }}}
    */
  def fold1[O2 >: O](f: (O2, O2) => O2): Stream[F, O2] =
    this.pull.fold1(f).flatMap(_.map(Pull.output1).getOrElse(Pull.done)).stream

  /**
    * Alias for `map(f).foldMonoid`.
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> Stream(1, 2, 3, 4, 5).foldMap(_ => 1).toList
    * res0: List[Int] = List(5)
    * }}}
    */
  def foldMap[O2](f: O => O2)(implicit O2: Monoid[O2]): Stream[F, O2] =
    fold(O2.empty)((acc, o) => O2.combine(acc, f(o)))

  /**
    * Emits a single `true` value if all input matches the predicate.
    * Halts with `false` as soon as a non-matching element is received.
    *
    * @example {{{
    * scala> Stream(1, 2, 3, 4, 5).forall(_ < 10).toList
    * res0: List[Boolean] = List(true)
    * }}}
    */
  def forall(p: O => Boolean): Stream[F, Boolean] =
    this.pull.forall(p).flatMap(Pull.output1).stream

  /**
    * Partitions the input into a stream of segments according to a discriminator function.
    *
    * Each chunk in the source stream is grouped using the supplied discriminator function
    * and the results of the grouping are emitted each time the discriminator function changes
    * values.
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> Stream("Hello", "Hi", "Greetings", "Hey").groupAdjacentBy(_.head).toList.map { case (k,vs) => k -> vs.force.toList }
    * res0: List[(Char,List[String])] = List((H,List(Hello, Hi)), (G,List(Greetings)), (H,List(Hey)))
    * }}}
    */
  def groupAdjacentBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, (O2, Segment[O, Unit])] = {

    def go(current: Option[(O2, Segment[O, Unit])],
           s: Stream[F, O]): Pull[F, (O2, Segment[O, Unit]), Unit] =
      s.pull.unconsChunk.flatMap {
        case Some((hd, tl)) =>
          val (k1, out) = current.getOrElse((f(hd(0)), Segment.empty[O]))
          doChunk(hd, tl, k1, out, None)
        case None =>
          val l = current
            .map { case (k1, out) => Pull.output1((k1, out)) }
            .getOrElse(Pull
              .pure(()))
          l >> Pull.done
      }

    @annotation.tailrec
    def doChunk(chunk: Chunk[O],
                s: Stream[F, O],
                k1: O2,
                out: Segment[O, Unit],
                acc: Option[Segment[(O2, Segment[O, Unit]), Unit]])
      : Pull[F, (O2, Segment[O, Unit]), Unit] = {
      val differsAt = chunk.indexWhere(v => eq.neqv(f(v), k1)).getOrElse(-1)
      if (differsAt == -1) {
        // whole chunk matches the current key, add this chunk to the accumulated output
        val newOut: Segment[O, Unit] = out ++ Segment.chunk(chunk)
        acc match {
          case None      => go(Some((k1, newOut)), s)
          case Some(acc) =>
            // potentially outputs one additional chunk (by splitting the last one in two)
            Pull.output(acc) >> go(Some((k1, newOut)), s)
        }
      } else {
        // at least part of this chunk does not match the current key, need to group and retain chunkiness
        // split the chunk into the bit where the keys match and the bit where they don't
        val matching = Segment.chunk(chunk).take(differsAt)
        val newOut: Segment[O, Unit] = out ++ matching.voidResult
        val nonMatching = chunk.drop(differsAt)
        // nonMatching is guaranteed to be non-empty here, because we know the last element of the chunk doesn't have
        // the same key as the first
        val k2 = f(nonMatching(0))
        doChunk(nonMatching,
                s,
                k2,
                Segment.empty[O],
                Some(acc.getOrElse(Segment.empty) ++ Segment((k1, newOut))))
      }
    }

    go(None, this).stream
  }

  /**
    * Emits the first element of this stream (if non-empty) and then halts.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).head.toList
    * res0: List[Int] = List(1)
    * }}}
    */
  def head: Stream[F, O] = take(1)

  /**
    * Emits the specified separator between every pair of elements in the source stream.
    *
    * @example {{{
    * scala> Stream(1, 2, 3, 4, 5).intersperse(0).toList
    * res0: List[Int] = List(1, 0, 2, 0, 3, 0, 4, 0, 5)
    * }}}
    */
  def intersperse[O2 >: O](separator: O2): Stream[F, O2] =
    this.pull.echo1.flatMap {
      case None => Pull.pure(None)
      case Some(s) =>
        s.repeatPull {
            _.uncons.flatMap {
              case None => Pull.pure(None)
              case Some((hd, tl)) =>
                val interspersed = {
                  val bldr = Vector.newBuilder[O2]
                  hd.force.toVector.foreach { o =>
                    bldr += separator; bldr += o
                  }
                  Chunk.vector(bldr.result)
                }
                Pull.output(Segment.chunk(interspersed)) >> Pull.pure(Some(tl))
            }
          }
          .pull
          .echo
    }.stream

  /**
    * Returns the last element of this stream, if non-empty.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).last.toList
    * res0: List[Option[Int]] = List(Some(3))
    * }}}
    */
  def last: Stream[F, Option[O]] =
    this.pull.last.flatMap(Pull.output1).stream

  /**
    * Returns the last element of this stream, if non-empty, otherwise the supplied `fallback` value.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).lastOr(0).toList
    * res0: List[Int] = List(3)
    * scala> Stream.empty.lastOr(0).toList
    * res1: List[Int] = List(0)
    * }}}
    */
  def lastOr[O2 >: O](fallback: => O2): Stream[F, O2] =
    this.pull.last.flatMap {
      case Some(o) => Pull.output1(o)
      case None    => Pull.output1(fallback)
    }.stream

  /**
    * Maps a running total according to `S` and the input with the function `f`.
    *
    * @example {{{
    * scala> Stream("Hello", "World").mapAccumulate(0)((l, s) => (l + s.length, s.head)).toVector
    * res0: Vector[(Int, Char)] = Vector((5,H), (10,W))
    * }}}
    */
  def mapAccumulate[S, O2](init: S)(f: (S, O) => (S, O2)): Stream[F, (S, O2)] = {
    val f2 = (s: S, o: O) => {
      val (newS, newO) = f(s, o)
      (newS, (newS, newO))
    }
    this.scanSegments(init)((acc, seg) => seg.mapAccumulate(acc)(f2).mapResult(_._2))
  }

  /**
    * Applies the specified pure function to each input and emits the result.
    *
    * @example {{{
    * scala> Stream("Hello", "World!").map(_.size).toList
    * res0: List[Int] = List(5, 6)
    * }}}
    */
  def map[O2](f: O => O2): Stream[F, O2] =
    this.repeatPull(_.uncons.flatMap {
      case None           => Pull.pure(None);
      case Some((hd, tl)) => Pull.output(hd.map(f)).as(Some(tl))
    })

  /**
    * Applies the specified pure function to each chunk in this stream.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).append(Stream(4, 5, 6)).mapChunks { c => val ints = c.toInts; for (i <- 0 until ints.values.size) ints.values(i) = 0; ints.toSegment }.toList
    * res0: List[Int] = List(0, 0, 0, 0, 0, 0)
    * }}}
    */
  def mapChunks[O2](f: Chunk[O] => Segment[O2, Unit]): Stream[F, O2] =
    this.repeatPull {
      _.unconsChunk.flatMap {
        case None           => Pull.pure(None);
        case Some((hd, tl)) => Pull.output(f(hd)).as(Some(tl))
      }
    }

  /**
    * Applies the specified pure function to each segment in this stream.
    *
    * @example {{{
    * scala> (Stream.range(1,5) ++ Stream.range(5,10)).mapSegments(s => s.scan(0)(_ + _).voidResult).toList
    * res0: List[Int] = List(0, 1, 3, 6, 10, 0, 5, 11, 18, 26, 35)
    * }}}
    */
  def mapSegments[O2](f: Segment[O, Unit] => Segment[O2, Unit]): Stream[F, O2] =
    this.repeatPull {
      _.uncons.flatMap {
        case None           => Pull.pure(None);
        case Some((hd, tl)) => Pull.output(f(hd)).as(Some(tl))
      }
    }

  /**
    * Behaves like the identity function but halts the stream on an error and does not return the error.
    *
    * @example {{{
    * scala> (Stream(1,2,3) ++ Stream.raiseError(new RuntimeException) ++ Stream(4, 5, 6)).mask.toList
    * res0: List[Int] = List(1, 2, 3)
    * }}}
    */
  def mask: Stream[F, O] = this.handleErrorWith(_ => Stream.empty)

  /**
    * Emits each output wrapped in a `Some` and emits a `None` at the end of the stream.
    *
    * `s.noneTerminate.unNoneTerminate == s`
    *
    * @example {{{
    * scala> Stream(1,2,3).noneTerminate.toList
    * res0: List[Option[Int]] = List(Some(1), Some(2), Some(3), None)
    * }}}
    */
  def noneTerminate: Stream[F, Option[O]] = map(Some(_)) ++ Stream.emit(None)

  /**
    * Repeat this stream an infinite number of times.
    *
    * `s.repeat == s ++ s ++ s ++ ...`
    *
    * @example {{{
    * scala> Stream(1,2,3).repeat.take(8).toList
    * res0: List[Int] = List(1, 2, 3, 1, 2, 3, 1, 2)
    * }}}
    */
  def repeat: Stream[F, O] =
    this ++ repeat

  /**
    * Converts a `Stream[F,Either[Throwable,O]]` to a `Stream[F,O]`, which emits right values and fails upon the first `Left(t)`.
    * Preserves chunkiness.
    *
    * @example {{{
    * scala> Stream(Right(1), Right(2), Left(new RuntimeException), Right(3)).rethrow.handleErrorWith(t => Stream(-1)).toList
    * res0: List[Int] = List(-1)
    * }}}
    */
  def rethrow[O2](implicit ev: O <:< Either[Throwable, O2]): Stream[F, O2] = {
    val _ = ev // Convince scalac that ev is used
    this.asInstanceOf[Stream[F, Either[Throwable, O2]]].segments.flatMap { s =>
      val errs = s.collect { case Left(e) => e }
      errs.force.uncons1 match {
        case Left(())        => Stream.segment(s.collect { case Right(i) => i })
        case Right((hd, tl)) => Stream.raiseError(hd)
      }
    }
  }

  /** Alias for [[fold1]]. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F, O2] = fold1(f)

  /**
    * Left fold which outputs all intermediate results.
    *
    * @example {{{
    * scala> Stream(1,2,3,4).scan(0)(_ + _).toList
    * res0: List[Int] = List(0, 1, 3, 6, 10)
    * }}}
    *
    * More generally:
    *   `Stream().scan(z)(f) == Stream(z)`
    *   `Stream(x1).scan(z)(f) == Stream(z, f(z,x1))`
    *   `Stream(x1,x2).scan(z)(f) == Stream(z, f(z,x1), f(f(z,x1),x2))`
    *   etc
    */
  def scan[O2](z: O2)(f: (O2, O) => O2): Stream[F, O2] =
    (Pull.output1(z) >> scan_(z)(f)).stream

  private def scan_[O2](z: O2)(f: (O2, O) => O2): Pull[F, O2, Unit] =
    this.pull.uncons.flatMap {
      case None => Pull.done
      case Some((hd, tl)) =>
        hd.scan(z)(f).mapResult(_._2).force.uncons1 match {
          case Left(acc) => tl.scan_(acc)(f)
          case Right((_, out)) =>
            Pull.segment(out).flatMap { acc =>
              tl.scan_(acc)(f)
            }
        }
    }

  /**
    * Like `[[scan]]`, but uses the first element of the stream as the seed.
    *
    * @example {{{
    * scala> Stream(1,2,3,4).scan1(_ + _).toList
    * res0: List[Int] = List(1, 3, 6, 10)
    * }}}
    */
  def scan1[O2 >: O](f: (O2, O2) => O2): Stream[F, O2] =
    this.pull.uncons1.flatMap {
      case None           => Pull.done
      case Some((hd, tl)) => Pull.output1(hd) >> tl.scan_(hd: O2)(f)
    }.stream

  /**
    * Scopes are typically inserted automatically, at the boundary of a pull (i.e., when a pull
    * is converted to a stream). This method allows a scope to be explicitly demarcated so that
    * resources can be freed earlier than when using automatically inserted scopes. This is
    * useful when using `streamNoScope` to convert from `Pull` to `Stream` -- i.e., by choosing
    * to *not* have scopes inserted automatically, you may end up needing to demarcate scopes
    * manually at a higher level in the stream structure.
    *
    * Note: see the disclaimer about the use of `streamNoScope`.
    */
  def scope: Stream[F, O] = Stream.fromFreeC(Algebra.scope(get))

  /**
    * Outputs the segments of this stream as output values, ensuring each segment has maximum size `n`, splitting segments as necessary.
    *
    * @example {{{
    * scala> Stream(1,2,3).repeat.segmentLimit(2).take(5).toList
    * res0: List[Segment[Int,Unit]] = List(Chunk(1, 2), Chunk(3), Chunk(1, 2), Chunk(3), Chunk(1, 2))
    * }}}
    */
  def segmentLimit(n: Int): Stream[F, Segment[O, Unit]] =
    this.repeatPull {
      _.unconsLimit(n).flatMap {
        case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
        case None           => Pull.pure(None)
      }
    }

  /**
    * Outputs segments of size `n`.
    *
    * Segments from the source stream are split as necessary.
    * If `allowFewer` is true, the last segment that is emitted may have less than `n` elements.
    *
    * @example {{{
    * scala> Stream(1,2,3).repeat.segmentN(2).take(5).toList
    * res0: List[Segment[Int,Unit]] = List(Chunk(1, 2), catenated(Chunk(3), Chunk(1)), Chunk(2, 3), Chunk(1, 2), catenated(Chunk(3), Chunk(1)))
    * }}}
    */
  def segmentN(n: Int, allowFewer: Boolean = true): Stream[F, Segment[O, Unit]] =
    this.repeatPull {
      _.unconsN(n, allowFewer).flatMap {
        case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
        case None           => Pull.pure(None)
      }
    }

  /**
    * Outputs all segments from the source stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).repeat.segments.take(5).toList
    * res0: List[Segment[Int,Unit]] = List(Chunk(1, 2, 3), Chunk(1, 2, 3), Chunk(1, 2, 3), Chunk(1, 2, 3), Chunk(1, 2, 3))
    * }}}
    */
  def segments: Stream[F, Segment[O, Unit]] =
    this.repeatPull(_.uncons.flatMap {
      case None           => Pull.pure(None);
      case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
    })

  /**
    * Groups inputs in fixed size chunks by passing a "sliding window"
    * of size `n` over them. If the input contains less than or equal to
    * `n` elements, only one chunk of this size will be emitted.
    *
    * @example {{{
    * scala> Stream(1, 2, 3, 4).sliding(2).toList
    * res0: List[scala.collection.immutable.Queue[Int]] = List(Queue(1, 2), Queue(2, 3), Queue(3, 4))
    * }}}
    * @throws scala.IllegalArgumentException if `n` <= 0
    */
  def sliding(n: Int): Stream[F, collection.immutable.Queue[O]] = {
    require(n > 0, "n must be > 0")
    def go(window: collection.immutable.Queue[O],
           s: Stream[F, O]): Pull[F, collection.immutable.Queue[O], Unit] =
      s.pull.uncons.flatMap {
        case None => Pull.done
        case Some((hd, tl)) =>
          hd.scan(window)((w, i) => w.dequeue._2.enqueue(i))
            .mapResult(_._2)
            .force
            .drop(1) match {
            case Left((w2, _)) => go(w2, tl)
            case Right(out) =>
              Pull.segment(out).flatMap { window =>
                go(window, tl)
              }
          }
      }
    this.pull
      .unconsN(n, true)
      .flatMap {
        case None => Pull.done
        case Some((hd, tl)) =>
          val window = hd
            .fold(collection.immutable.Queue.empty[O])(_.enqueue(_))
            .force
            .run
            ._2
          Pull.output1(window) >> go(window, tl)
      }
      .stream
  }

  /**
    * Breaks the input into chunks where the delimiter matches the predicate.
    * The delimiter does not appear in the output. Two adjacent delimiters in the
    * input result in an empty chunk in the output.
    *
    * @example {{{
    * scala> Stream.range(0, 10).split(_ % 4 == 0).toList
    * res0: List[Segment[Int,Unit]] = List(empty, catenated(Chunk(1), Chunk(2), Chunk(3), empty), catenated(Chunk(5), Chunk(6), Chunk(7), empty), Chunk(9))
    * }}}
    */
  def split(f: O => Boolean): Stream[F, Segment[O, Unit]] = {
    def go(buffer: Catenable[Segment[O, Unit]], s: Stream[F, O]): Pull[F, Segment[O, Unit], Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          hd.force.splitWhile(o => !(f(o))) match {
            case Left((_, out)) =>
              if (out.isEmpty) go(buffer, tl)
              else go(buffer ++ out.map(Segment.chunk), tl)
            case Right((out, tl2)) =>
              val b2 =
                if (out.nonEmpty) buffer ++ out.map(Segment.chunk) else buffer
              (if (b2.nonEmpty) Pull.output1(Segment.catenated(b2))
               else Pull.pure(())) >>
                go(Catenable.empty, tl.cons(tl2.force.drop(1).fold(_ => Segment.empty, identity)))
          }
        case None =>
          if (buffer.nonEmpty) Pull.output1(Segment.catenated(buffer))
          else Pull.done
      }
    go(Catenable.empty, this).stream
  }

  /**
    * Emits all elements of the input except the first one.
    *
    * @example {{{
    * scala> Stream(1,2,3).tail.toList
    * res0: List[Int] = List(2, 3)
    * }}}
    */
  def tail: Stream[F, O] = drop(1)

  /**
    * Emits the first `n` elements of this stream.
    *
    * @example {{{
    * scala> Stream.range(0,1000).take(5).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def take(n: Long): Stream[F, O] = this.pull.take(n).stream

  /**
    * Emits the last `n` elements of the input.
    *
    * @example {{{
    * scala> Stream.range(0,1000).takeRight(5).toList
    * res0: List[Int] = List(995, 996, 997, 998, 999)
    * }}}
    */
  def takeRight(n: Long): Stream[F, O] =
    this.pull.takeRight(n).flatMap(c => Pull.output(Segment.chunk(c))).stream

  /**
    * Like [[takeWhile]], but emits the first value which tests false.
    *
    * @example {{{
    * scala> Stream.range(0,1000).takeThrough(_ != 5).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4, 5)
    * }}}
    */
  def takeThrough(p: O => Boolean): Stream[F, O] =
    this.pull.takeThrough(p).stream

  /**
    * Emits the longest prefix of the input for which all elements test true according to `f`.
    *
    * @example {{{
    * scala> Stream.range(0,1000).takeWhile(_ != 5).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def takeWhile(p: O => Boolean, takeFailure: Boolean = false): Stream[F, O] =
    this.pull.takeWhile(p, takeFailure).stream

  /**
    * Converts the input to a stream of 1-element chunks.
    *
    * @example {{{
    * scala> (Stream(1,2,3) ++ Stream(4,5,6)).unchunk.segments.toList
    * res0: List[Segment[Int,Unit]] = List(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6))
    * }}}
    */
  def unchunk: Stream[F, O] =
    this.repeatPull {
      _.uncons1.flatMap {
        case None           => Pull.pure(None);
        case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
      }
    }

  /**
    * Filters any 'None'.
    *
    * @example {{{
    * scala> Stream(Some(1), Some(2), None, Some(3), None).unNone.toList
    * res0: List[Int] = List(1, 2, 3)
    * }}}
    */
  def unNone[O2](implicit ev: O <:< Option[O2]): Stream[F, O2] = {
    val _ = ev // Convince scalac that ev is used
    this.asInstanceOf[Stream[F, Option[O2]]].collect { case Some(o2) => o2 }
  }

  /**
    * Halts the input stream at the first `None`.
    *
    * @example {{{
    * scala> Stream(Some(1), Some(2), None, Some(3), None).unNoneTerminate.toList
    * res0: List[Int] = List(1, 2)
    * }}}
    */
  def unNoneTerminate[O2](implicit ev: O <:< Option[O2]): Stream[F, O2] =
    this.repeatPull {
      _.uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          Pull
            .segment(hd.takeWhile(_.isDefined).map(_.get))
            .map(_.fold(_ => Some(tl), _ => None))
      }
    }

  /**
    * Zips the elements of the input stream with its indices, and returns the new stream.
    *
    * @example {{{
    * scala> Stream("The", "quick", "brown", "fox").zipWithIndex.toList
    * res0: List[(String,Long)] = List((The,0), (quick,1), (brown,2), (fox,3))
    * }}}
    */
  def zipWithIndex: Stream[F, (O, Long)] =
    this.scanSegments(0L) { (index, s) =>
      s.withSize.zipWith(Segment.from(index))((_, _)).mapResult {
        case Left(((_, len), remRight)) => len + index
        case Right((_, remLeft))        => sys.error("impossible")
      }
    }

  /**
    * Zips each element of this stream with the next element wrapped into `Some`.
    * The last element is zipped with `None`.
    *
    * @example {{{
    * scala> Stream("The", "quick", "brown", "fox").zipWithNext.toList
    * res0: List[(String,Option[String])] = List((The,Some(quick)), (quick,Some(brown)), (brown,Some(fox)), (fox,None))
    * }}}
    */
  def zipWithNext: Stream[F, (O, Option[O])] = {
    def go(last: O, s: Stream[F, O]): Pull[F, (O, Option[O]), Unit] =
      s.pull.uncons.flatMap {
        case None => Pull.output1((last, None))
        case Some((hd, tl)) =>
          Pull
            .segment(hd.mapAccumulate(last) {
              case (prev, next) => (next, (prev, Some(next)))
            })
            .flatMap { case (_, newLast) => go(newLast, tl) }
      }
    this.pull.uncons1.flatMap {
      case Some((hd, tl)) => go(hd, tl)
      case None           => Pull.done
    }.stream
  }

  /**
    * Zips each element of this stream with the previous element wrapped into `Some`.
    * The first element is zipped with `None`.
    *
    * @example {{{
    * scala> Stream("The", "quick", "brown", "fox").zipWithPrevious.toList
    * res0: List[(Option[String],String)] = List((None,The), (Some(The),quick), (Some(quick),brown), (Some(brown),fox))
    * }}}
    */
  def zipWithPrevious: Stream[F, (Option[O], O)] =
    mapAccumulate[Option[O], (Option[O], O)](None) {
      case (prev, next) => (Some(next), (prev, next))
    }.map { case (_, prevNext) => prevNext }

  /**
    * Zips each element of this stream with its previous and next element wrapped into `Some`.
    * The first element is zipped with `None` as the previous element,
    * the last element is zipped with `None` as the next element.
    *
    * @example {{{
    * scala> Stream("The", "quick", "brown", "fox").zipWithPreviousAndNext.toList
    * res0: List[(Option[String],String,Option[String])] = List((None,The,Some(quick)), (Some(The),quick,Some(brown)), (Some(quick),brown,Some(fox)), (Some(brown),fox,None))
    * }}}
    */
  def zipWithPreviousAndNext: Stream[F, (Option[O], O, Option[O])] =
    zipWithPrevious.zipWithNext.map {
      case ((prev, that), None)            => (prev, that, None)
      case ((prev, that), Some((_, next))) => (prev, that, Some(next))
    }

  /**
    * Zips the input with a running total according to `S`, up to but not including the current element. Thus the initial
    * `z` value is the first emitted to the output:
    *
    * @example {{{
    * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan(0)(_ + _.length).toList
    * res0: List[(String,Int)] = List((uno,0), (dos,3), (tres,6), (cuatro,10))
    * }}}
    *
    * @see [[zipWithScan1]]
    */
  def zipWithScan[O2](z: O2)(f: (O2, O) => O2): Stream[F, (O, O2)] =
    this
      .mapAccumulate(z) { (s, o) =>
        val s2 = f(s, o); (s2, (o, s))
      }
      .map(_._2)

  /**
    * Zips the input with a running total according to `S`, including the current element. Thus the initial
    * `z` value is the first emitted to the output:
    *
    * @example {{{
    * scala> Stream("uno", "dos", "tres", "cuatro").zipWithScan1(0)(_ + _.length).toList
    * res0: List[(String, Int)] = List((uno,3), (dos,6), (tres,10), (cuatro,16))
    * }}}
    *
    * @see [[zipWithScan]]
    */
  def zipWithScan1[O2](z: O2)(f: (O2, O) => O2): Stream[F, (O, O2)] =
    this
      .mapAccumulate(z) { (s, o) =>
        val s2 = f(s, o); (s2, (o, s2))
      }
      .map(_._2)

  override def toString: String = "Stream(..)"
}

object Stream {
  private[fs2] def fromFreeC[F[_], O](free: FreeC[Algebra[F, O, ?], Unit]): Stream[F, O] =
    new Stream(free.asInstanceOf[FreeC[Algebra[Nothing, Nothing, ?], Unit]])

  /** Creates a pure stream that emits the supplied values. To convert to an effectful stream, use [[covary]]. */
  def apply[O](os: O*): Stream[Pure, O] = emits(os)

  /**
    * Creates a single element stream that gets its value by evaluating the supplied effect. If the effect fails, a `Left`
    * is emitted. Otherwise, a `Right` is emitted.
    *
    * Use [[eval]] instead if a failure while evaluating the effect should fail the stream.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.attemptEval(IO(10)).compile.toVector.unsafeRunSync
    * res0: Vector[Either[Throwable,Int]] = Vector(Right(10))
    * scala> Stream.attemptEval(IO(throw new RuntimeException)).compile.toVector.unsafeRunSync
    * res1: Vector[Either[Throwable,Nothing]] = Vector(Left(java.lang.RuntimeException))
    * }}}
    */
  def attemptEval[F[_], O](fo: F[O]): Stream[F, Either[Throwable, O]] =
    fromFreeC(Pull.attemptEval(fo).flatMap(Pull.output1).get)

  /**
    * Creates a stream that depends on a resource allocated by an effect, ensuring the resource is
    * released regardless of how the stream is used.
    *
    * @param r resource to acquire at start of stream
    * @param use function which uses the acquired resource to generate a stream of effectful outputs
    * @param release function which returns an effect that releases the resource
    *
    * A typical use case for bracket is working with files or network sockets. The resource effect
    * opens a file and returns a reference to it. The `use` function reads bytes and transforms them
    * in to some stream of elements (e.g., bytes, strings, lines, etc.). The `release` action closes
    * the file.
    */
  def bracket[F[_], R, O](r: F[R])(use: R => Stream[F, O], release: R => F[Unit]): Stream[F, O] =
    fromFreeC(Algebra.acquire[F, O, R](r, release).flatMap {
      case (r, token) =>
        use(r).get[F, O].transformWith {
          case Left(err) => Algebra.release(token).flatMap(_ => FreeC.Fail(err))
          case Right(_)  => Algebra.release(token)
        }
    })

  private[fs2] def bracketWithToken[F[_], R, O](
      r: F[R])(use: R => Stream[F, O], release: R => F[Unit]): Stream[F, (Token, O)] =
    fromFreeC(Algebra.acquire[F, (Token, O), R](r, release).flatMap {
      case (r, token) =>
        use(r)
          .map(o => (token, o))
          .get[F, (Token, O)]
          .transformWith {
            case Left(err) => Algebra.release(token).flatMap(_ => FreeC.Fail(err))
            case Right(_)  => Algebra.release(token)
          }
    })

  /**
    * Creates a pure stream that emits the elements of the supplied chunk.
    *
    * @example {{{
    * scala> Stream.chunk(Chunk(1,2,3)).toList
    * res0: List[Int] = List(1, 2, 3)
    * }}}
    */
  def chunk[O](os: Chunk[O]): Stream[Pure, O] = segment(Segment.chunk(os))

  /**
    * Creates an infinite pure stream that always returns the supplied value.
    *
    * Elements are emitted in finite segments with `segmentSize` number of elements.
    *
    * @example {{{
    * scala> Stream.constant(0).take(5).toList
    * res0: List[Int] = List(0, 0, 0, 0, 0)
    * }}}
    */
  def constant[O](o: O, segmentSize: Int = 256): Stream[Pure, O] =
    segment(Segment.constant(o).take(segmentSize).voidResult).repeat

  /**
    * A continuous stream of the elapsed time, computed using `System.nanoTime`.
    * Note that the actual granularity of these elapsed times depends on the OS, for instance
    * the OS may only update the current time every ten milliseconds or so.
    */
  def duration[F[_]](implicit F: Sync[F]): Stream[F, FiniteDuration] =
    Stream.eval(F.delay(System.nanoTime)).flatMap { t0 =>
      Stream.repeatEval(F.delay((System.nanoTime - t0).nanos))
    }

  /**
    * Creates a singleton pure stream that emits the supplied value.
    *
    * @example {{{
    * scala> Stream.emit(0).toList
    * res0: List[Int] = List(0)
    * }}}
    */
  def emit[O](o: O): Stream[Pure, O] = fromFreeC(Algebra.output1[Pure, O](o))

  /**
    * Creates a pure stream that emits the supplied values.
    *
    * @example {{{
    * scala> Stream.emits(List(1, 2, 3)).toList
    * res0: List[Int] = List(1, 2, 3)
    * }}}
    */
  def emits[O](os: Seq[O]): Stream[Pure, O] =
    if (os.isEmpty) empty
    else if (os.size == 1) emit(os.head)
    else fromFreeC(Algebra.output[Pure, O](Segment.seq(os)))

  private[fs2] val empty_ =
    fromFreeC[Nothing, Nothing](Algebra.pure[Nothing, Nothing, Unit](())): Stream[Nothing, Nothing]

  /** Empty pure stream. */
  def empty: Stream[Pure, Nothing] = empty_

  /**
    * Creates a single element stream that gets its value by evaluating the supplied effect. If the effect fails,
    * the returned stream fails.
    *
    * Use [[attemptEval]] instead if a failure while evaluating the effect should be emitted as a value.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.eval(IO(10)).compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(10)
    * scala> Stream.eval(IO(throw new RuntimeException)).compile.toVector.attempt.unsafeRunSync
    * res1: Either[Throwable,Vector[Nothing]] = Left(java.lang.RuntimeException)
    * }}}
    */
  def eval[F[_], O](fo: F[O]): Stream[F, O] =
    fromFreeC(Algebra.eval(fo).flatMap(Algebra.output1))

  /**
    * Creates a stream that evaluates the supplied `fa` for its effect, discarding the output value.
    * As a result, the returned stream emits no elements and hence has output type `Nothing`.
    *
    * Alias for `eval(fa).drain`.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.eval_(IO(println("Ran"))).compile.toVector.unsafeRunSync
    * res0: Vector[Nothing] = Vector()
    * }}}
    */
  def eval_[F[_], A](fa: F[A]): Stream[F, Nothing] =
    fromFreeC(Algebra.eval(fa).map(_ => ()))

  /**
    * A continuous stream which is true after `d, 2d, 3d...` elapsed duration,
    * and false otherwise.
    * If you'd like a 'discrete' stream that will actually block until `d` has elapsed,
    * use `awakeEvery` on [[Scheduler]] instead.
    */
  def every[F[_]](d: FiniteDuration): Stream[F, Boolean] = {
    def go(lastSpikeNanos: Long): Stream[F, Boolean] =
      Stream.suspend {
        val now = System.nanoTime
        if ((now - lastSpikeNanos) > d.toNanos) Stream.emit(true) ++ go(now)
        else Stream.emit(false) ++ go(lastSpikeNanos)
      }
    go(0)
  }

  /**
    * Lifts an iterator into a Stream
    */
  def fromIterator[F[_], A](iterator: Iterator[A])(implicit F: Sync[F]): Stream[F, A] = {
    def getNext(i: Iterator[A]): F[Option[(A, Iterator[A])]] =
      F.delay(i.hasNext)
        .flatMap(b => if (b) F.delay(i.next()).map(a => (a, i).some) else F.pure(None))
    Stream.unfoldEval(iterator)(getNext)
  }

  /**
    * Lifts an effect that generates a stream in to a stream. Alias for `eval(f).flatMap(_)`.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.force(IO(Stream(1,2,3).covary[IO])).compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(1, 2, 3)
    * }}}
    */
  def force[F[_], A](f: F[Stream[F, A]]): Stream[F, A] =
    eval(f).flatMap(s => s)

  /**
    * An infinite `Stream` that repeatedly applies a given function
    * to a start value. `start` is the first value emitted, followed
    * by `f(start)`, then `f(f(start))`, and so on.
    *
    * @example {{{
    * scala> Stream.iterate(0)(_ + 1).take(10).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    * }}}
    */
  def iterate[A](start: A)(f: A => A): Stream[Pure, A] =
    emit(start) ++ iterate(f(start))(f)

  /**
    * Like [[iterate]], but takes an effectful function for producing
    * the next state. `start` is the first value emitted.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.iterateEval(0)(i => IO(i + 1)).take(10).compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    * }}}
    */
  def iterateEval[F[_], A](start: A)(f: A => F[A]): Stream[F, A] =
    emit(start) ++ eval(f(start)).flatMap(iterateEval(_)(f))

  /**
    * Gets the current scope, allowing manual leasing or interruption.
    * This is a low-level method and generally should not be used by user code.
    */
  def getScope[F[_]]: Stream[F, Scope[F]] =
    Stream.fromFreeC(Algebra.getScope[F, Scope[F], Scope[F]].flatMap(Algebra.output1(_)))

  /**
    * Creates a stream that, when run, fails with the supplied exception.
    *
    * @example {{{
    * scala> import scala.util.Try
    * scala> Try(Stream.raiseError(new RuntimeException).toList)
    * res0: Try[List[Nothing]] = Failure(java.lang.RuntimeException)
    * scala> import cats.effect.IO
    * scala> Stream.raiseError(new RuntimeException).covary[IO].compile.drain.attempt.unsafeRunSync
    * res0: Either[Throwable,Unit] = Left(java.lang.RuntimeException)
    * }}}
    */
  def raiseError[O](e: Throwable): Stream[Pure, O] =
    fromFreeC(Algebra.raiseError(e))

  /**
    * Lazily produce the range `[start, stopExclusive)`. If you want to produce
    * the sequence in one chunk, instead of lazily, use
    * `emits(start until stopExclusive)`.
    *
    * @example {{{
    * scala> Stream.range(10, 20, 2).toList
    * res0: List[Int] = List(10, 12, 14, 16, 18)
    * }}}
    */
  def range(start: Int, stopExclusive: Int, by: Int = 1): Stream[Pure, Int] =
    unfold(start) { i =>
      if ((by > 0 && i < stopExclusive && start < stopExclusive) ||
          (by < 0 && i > stopExclusive && start > stopExclusive))
        Some((i, i + by))
      else None
    }

  /**
    * Lazily produce a sequence of nonoverlapping ranges, where each range
    * contains `size` integers, assuming the upper bound is exclusive.
    * Example: `ranges(0, 1000, 10)` results in the pairs
    * `(0, 10), (10, 20), (20, 30) ... (990, 1000)`
    *
    * Note: The last emitted range may be truncated at `stopExclusive`. For
    * instance, `ranges(0,5,4)` results in `(0,4), (4,5)`.
    *
    * @example {{{
    * scala> Stream.ranges(0, 20, 5).toList
    * res0: List[(Int,Int)] = List((0,5), (5,10), (10,15), (15,20))
    * }}}
    *
    * @throws IllegalArgumentException if `size` <= 0
    */
  def ranges(start: Int, stopExclusive: Int, size: Int): Stream[Pure, (Int, Int)] = {
    require(size > 0, "size must be > 0, was: " + size)
    unfold(start) { lower =>
      if (lower < stopExclusive)
        Some((lower -> ((lower + size).min(stopExclusive)), lower + size))
      else
        None
    }
  }

  /** Alias for `eval(fo).repeat`. */
  def repeatEval[F[_], O](fo: F[O]): Stream[F, O] = eval(fo).repeat

  /**
    * Creates a pure stream that emits the values of the supplied segment.
    *
    * @example {{{
    * scala> Stream.segment(Segment.from(0)).take(5).toList
    * res0: List[Long] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def segment[O](s: Segment[O, Unit]): Stream[Pure, O] =
    fromFreeC(Algebra.output[Pure, O](s))

  /**
    * Returns a stream that evaluates the supplied by-name each time the stream is used,
    * allowing use of a mutable value in stream computations.
    *
    * Note: it's generally easier to reason about such computations using effectful
    * values. That is, allocate the mutable value in an effect and then use
    * `Stream.eval(fa).flatMap { a => ??? }`.
    *
    * @example {{{
    * scala> Stream.suspend {
    *      |   val digest = java.security.MessageDigest.getInstance("SHA-256")
    *      |   val bytes: Stream[Pure,Byte] = ???
    *      |   bytes.chunks.fold(digest) { (d,c) => d.update(c.toBytes.values); d }
    *      | }
    * }}}
    */
  def suspend[F[_], O](s: => Stream[F, O]): Stream[F, O] =
    fromFreeC(Algebra.suspend(s.get))

  /**
    * Creates a stream by successively applying `f` until a `None` is returned, emitting
    * each output `O` and using each output `S` as input to the next invocation of `f`.
    *
    * @example {{{
    * scala> Stream.unfold(0)(i => if (i < 5) Some(i -> (i+1)) else None).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def unfold[S, O](s: S)(f: S => Option[(O, S)]): Stream[Pure, O] =
    segment(Segment.unfold(s)(f))

  /**
    * Like [[unfold]] but each invocation of `f` provides a chunk of output.
    *
    * @example {{{
    * scala> Stream.unfoldSegment(0)(i => if (i < 5) Some(Segment.seq(List.fill(i)(i)) -> (i+1)) else None).toList
    * res0: List[Int] = List(1, 2, 2, 3, 3, 3, 4, 4, 4, 4)
    * }}}
    */
  def unfoldSegment[S, O](s: S)(f: S => Option[(Segment[O, Unit], S)]): Stream[Pure, O] =
    unfold(s)(f).flatMap(segment)

  /** Like [[unfold]], but takes an effectful function. */
  def unfoldEval[F[_], S, O](s: S)(f: S => F[Option[(O, S)]]): Stream[F, O] = {
    def go(s: S): Stream[F, O] =
      eval(f(s)).flatMap {
        case Some((o, s)) => emit(o) ++ go(s)
        case None         => empty
      }
    suspend(go(s))
  }

  /** Alias for [[unfoldSegmentEval]] with slightly better type inference when `f` returns a `Chunk`. */
  def unfoldChunkEval[F[_], S, O](s: S)(f: S => F[Option[(Chunk[O], S)]])(
      implicit F: Functor[F]): Stream[F, O] =
    unfoldSegmentEval(s)(s => f(s).map(_.map { case (c, s) => Segment.chunk(c) -> s }))

  /** Like [[unfoldSegment]], but takes an effectful function. */
  def unfoldSegmentEval[F[_], S, O](s: S)(
      f: S => F[Option[(Segment[O, Unit], S)]]): Stream[F, O] = {
    def go(s: S): Stream[F, O] =
      eval(f(s)).flatMap {
        case Some((o, s)) => segment(o) ++ go(s)
        case None         => empty
      }
    suspend(go(s))
  }

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  implicit def InvariantOps[F[_], O](s: Stream[F, O]): InvariantOps[F, O] =
    new InvariantOps(s.get)

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  final class InvariantOps[F[_], O] private[Stream] (
      private val free: FreeC[Algebra[F, O, ?], Unit])
      extends AnyVal {
    private def self: Stream[F, O] = Stream.fromFreeC(free)

    /** Appends `s2` to the end of this stream. */
    def ++[O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] = self.append(s2)

    /** Appends `s2` to the end of this stream. Alias for `s1 ++ s2`. */
    def append[O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
      fromFreeC(self.get[F, O2].flatMap { _ =>
        s2.get
      })

    /**
      * Emits only elements that are distinct from their immediate predecessors,
      * using natural equality for comparison.
      *
      * @example {{{
      * scala> import cats.implicits._
      * scala> Stream(1,1,2,2,2,3,3).changes.toList
      * res0: List[Int] = List(1, 2, 3)
      * }}}
      */
    def changes(implicit eq: Eq[O]): Stream[F, O] =
      self.filterWithPrevious(eq.neqv)

    /**
      * Gets a projection of this stream that allows converting it to an `F[..]` in a number of ways.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> val prg: IO[Vector[Int]] = Stream.eval(IO(1)).append(Stream(2,3,4)).compile.toVector
      * scala> prg.unsafeRunSync
      * res2: Vector[Int] = Vector(1, 2, 3, 4)
      * }}}
      */
    def compile: Stream.ToEffect[F, O] = new Stream.ToEffect[F, O](self.free)

    /**
      * Runs the supplied stream in the background as elements from this stream are pulled.
      *
      * The resulting stream terminates upon termination of this stream. The background stream will
      * be interrupted at that point. Early termination of `that` does not terminate the resulting stream.
      *
      * Any errors that occur in either `this` or `that` stream result in the overall stream terminating
      * with an error.
      *
      * Upon finalization, the resulting stream will interrupt the background stream and wait for it to be
      * finalized.
      *
      * This method is similar to `this mergeHaltL that.drain` but ensures the `that.drain` stream continues
      * to be evaluated regardless of how `this` is evaluated or how the resulting stream is processed.
      * This method is also similar to `Stream(this,that).join(2)` but terminates `that` upon termination of
      * `this`.
      *
      * @example {{{
      * scala> import cats.effect.IO, scala.concurrent.ExecutionContext.Implicits.global
      * scala> val data: Stream[IO,Int] = Stream.range(1, 10).covary[IO]
      * scala> Stream.eval(async.signalOf[IO,Int](0)).flatMap(s => Stream(s).concurrently(data.evalMap(s.set))).flatMap(_.discrete).takeWhile(_ < 9, true).compile.last.unsafeRunSync
      * res0: Option[Int] = Some(9)
      * }}}
      */
    def concurrently[O2](that: Stream[F, O2])(implicit F: Effect[F],
                                              ec: ExecutionContext): Stream[F, O] =
      Stream.eval(async.promise[F, Unit]).flatMap { interruptR =>
        Stream.eval(async.promise[F, Option[Throwable]]).flatMap { doneR =>
          Stream.eval(async.promise[F, Throwable]).flatMap { interruptL =>
            def runR =
              that
                .interruptWhen(interruptR.get.attempt)
                .compile
                .drain
                .attempt
                .map { _.left.toOption }
                .flatMap { r =>
                  // to prevent deadlock, done must be signalled before `interruptL`
                  // in case the interruptL is signaled before the `L` stream may be in
                  // its `append` code, that requires `get` to complete, which won't ever complete,
                  // b/c it will be evaluated after `interruptL`
                  doneR.complete(r) >>
                    r.fold(F.unit)(interruptL.complete)
                }

            // There is slight chance that interruption in case of failure will arrive later than
            // `self` terminates.
            // To prevent such interruption to be `swallowed` we append stream, that results in
            // evaluation of the result.
            Stream.eval(async.fork(runR)) >>
              self
                .interruptWhen(interruptL.get.map(Either.left[Throwable, Unit]))
                .onFinalize { interruptR.complete(()) }
                .append(Stream.eval(doneR.get).flatMap {
                  case None      => Stream.empty
                  case Some(err) => Stream.raiseError(err)
                })
          }
        }
      }

    /**
      * Lifts this stream to the specified effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream(1, 2, 3).covary[IO]
      * res0: Stream[IO,Int] = Stream(..)
      * }}}
      */
    def covary[F2[x] >: F[x]]: Stream[F2, O] = self.asInstanceOf[Stream[F2, O]]

    /**
      * Lifts this stream to the specified effect and output types.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.empty.covaryAll[IO,Int]
      * res0: Stream[IO,Int] = Stream(..)
      * }}}
      */
    def covaryAll[F2[x] >: F[x], O2 >: O]: Stream[F2, O2] =
      self.asInstanceOf[Stream[F2, O2]]

    /**
      * Like `[[merge]]`, but tags each output with the branch it came from.
      *
      * @example {{{
      * scala> import scala.concurrent.duration._, scala.concurrent.ExecutionContext.Implicits.global, cats.effect.IO
      * scala> val s = Scheduler[IO](1).flatMap { scheduler =>
      *      |   val s1 = scheduler.awakeEvery[IO](1000.millis).scan(0)((acc, i) => acc + 1)
      *      |   s1.either(scheduler.sleep_[IO](500.millis) ++ s1).take(10)
      *      | }
      * scala> s.take(10).compile.toVector.unsafeRunSync
      * res0: Vector[Either[Int,Int]] = Vector(Left(0), Right(0), Left(1), Right(1), Left(2), Right(2), Left(3), Right(3), Left(4), Right(4))
      * }}}
      */
    def either[O2](that: Stream[F, O2])(implicit F: Effect[F],
                                        ec: ExecutionContext): Stream[F, Either[O, O2]] =
      self.map(Left(_)).merge(that.map(Right(_)))

    /**
      * Like `either`, but halts as soon as `self` halts.
      */
    def eitherHaltL[O2](that: Stream[F, O2])(implicit F: Effect[F],
                                             ec: ExecutionContext): Stream[F, Either[O, O2]] =
      self.map(Left(_)).mergeHaltL(that.map(Right(_)))

    /**
      * Like `either`, but halts as soon as `that` halts.
      */
    def eitherHaltR[O2](that: Stream[F, O2])(implicit F: Effect[F],
                                             ec: ExecutionContext): Stream[F, Either[O, O2]] =
      self.map(Left(_)).mergeHaltR(that.map(Right(_)))

    /**
      * Like `either`, but halts as soon as either `self` or `that` halts.
      */
    def eitherHaltBoth[O2](that: Stream[F, O2])(implicit F: Effect[F],
                                                ec: ExecutionContext): Stream[F, Either[O, O2]] =
      self.map(Left(_)).mergeHaltBoth(that.map(Right(_)))

    /**
      * Alias for `flatMap(o => Stream.eval(f(o)))`.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream(1,2,3,4).evalMap(i => IO(println(i))).compile.drain.unsafeRunSync
      * res0: Unit = ()
      * }}}
      */
    def evalMap[O2](f: O => F[O2]): Stream[F, O2] =
      self.flatMap(o => Stream.eval(f(o)))

    /**
      * Like `[[Stream#mapAccumulate]]`, but accepts a function returning an `F[_]`.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream(1,2,3,4).covary[IO].evalMapAccumulate(0)((acc,i) => IO((i, acc + i))).compile.toVector.unsafeRunSync
      * res0: Vector[(Int, Int)] = Vector((1,1), (2,3), (3,5), (4,7))
      * }}}
      */
    def evalMapAccumulate[S, O2](s: S)(f: (S, O) => F[(S, O2)]): Stream[F, (S, O2)] = {
      def go(s: S, in: Stream[F, O]): Pull[F, (S, O2), Unit] =
        in.pull.uncons1.flatMap {
          case None => Pull.done
          case Some((hd, tl)) =>
            Pull.eval(f(s, hd)).flatMap {
              case (ns, o) =>
                Pull.output1((ns, o)) >> go(ns, tl)
            }
        }

      go(s, self).stream
    }

    /**
      * Like `[[Stream#scan]]`, but accepts a function returning an `F[_]`.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream(1,2,3,4).evalScan(0)((acc,i) => IO(acc + i)).compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(0, 1, 3, 6, 10)
      * }}}
      */
    def evalScan[O2](z: O2)(f: (O2, O) => F[O2]): Stream[F, O2] = {
      def go(z: O2, s: Stream[F, O]): Pull[F, O2, Option[Stream[F, O2]]] =
        s.pull.uncons1.flatMap {
          case Some((hd, tl)) =>
            Pull.eval(f(z, hd)).flatMap { o =>
              Pull.output1(o) >> go(o, tl)
            }
          case None => Pull.pure(None)
        }
      self.pull.uncons1.flatMap {
        case Some((hd, tl)) =>
          Pull.eval(f(z, hd)).flatMap { o =>
            Pull.output(Segment.seq(List(z, o))) >> go(o, tl)
          }
        case None => Pull.output1(z) >> Pull.pure(None)
      }.stream
    }

    /**
      * Like [[Stream#evalMap]], but will evaluate effects in parallel, emitting the results
      * downstream in the same order as the input stream. The number of concurrent effects
      * is limited by the `parallelism` parameter.
      *
      * See [[Stream#mapAsyncUnordered]] if there is no requirement to retain the order of
      * the original stream.
      *
      * @example {{{
      * scala> import cats.effect.IO, scala.concurrent.ExecutionContext.Implicits.global
      * scala> Stream(1,2,3,4).mapAsync(2)(i => IO(println(i))).compile.drain.unsafeRunSync
      * res0: Unit = ()
      * }}}
      */
    def mapAsync[O2](parallelism: Int)(
        f: O => F[O2])(implicit F: Effect[F], executionContext: ExecutionContext): Stream[F, O2] =
      Stream
        .eval(async.mutable.Queue.bounded[F, Option[F[Either[Throwable, O2]]]](parallelism))
        .flatMap { queue =>
          queue.dequeue.unNoneTerminate
            .evalMap(identity)
            .rethrow
            .concurrently {
              self
                .evalMap { o =>
                  Promise.empty[F, Either[Throwable, O2]].flatMap { promise =>
                    queue.enqueue1(Some(promise.get)).as {
                      Stream.eval(f(o).attempt).evalMap(promise.complete)
                    }
                  }
                }
                .join(parallelism)
                .drain
                .onFinalize(queue.enqueue1(None))
            }
        }

    /**
      * Like [[Stream#evalMap]], but will evaluate effects in parallel, emitting the results
      * downstream. The number of concurrent effects is limited by the `parallelism` parameter.
      *
      * See [[Stream#mapAsync]] if retaining the original order of the stream is required.
      *
      * @example {{{
      * scala> import cats.effect.IO, scala.concurrent.ExecutionContext.Implicits.global
      * scala> Stream(1,2,3,4).mapAsync(2)(i => IO(println(i))).compile.drain.unsafeRunSync
      * res0: Unit = ()
      * }}}
      */
    def mapAsyncUnordered[O2](parallelism: Int)(
        f: O => F[O2])(implicit F: Effect[F], executionContext: ExecutionContext): Stream[F, O2] =
      self.map(o => Stream.eval(f(o))).join(parallelism)

    /**
      * Creates a stream whose elements are generated by applying `f` to each output of
      * the source stream and concatenated all of the results.
      *
      * @example {{{
      * scala> Stream(1, 2, 3).flatMap { i => Stream.segment(Segment.seq(List.fill(i)(i))) }.toList
      * res0: List[Int] = List(1, 2, 2, 3, 3, 3)
      * }}}
      */
    def flatMap[O2](f: O => Stream[F, O2]): Stream[F, O2] =
      Stream.fromFreeC[F, O2](Algebra.uncons(self.get[F, O]).flatMap {

        case Some((hd, tl)) =>
          // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
          // check if hd has only a single element, and if so, process it directly instead of folding.
          // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
          val only: Option[O] = tl match {
            case FreeC.Pure(_) =>
              hd.force.uncons1.toOption.flatMap {
                case (o, tl) => tl.force.uncons1.fold(_ => Some(o), _ => None)
              }
            case _ => None
          }
          only match {
            case None =>
              hd.map(f)
                .foldRightLazy(Stream.fromFreeC(tl).flatMap(f))(_ ++ _)
                .get

            case Some(o) =>
              f(o).get

          }
        case None => Stream.empty.covaryAll[F, O2].get
      })

    /** Alias for `flatMap(_ => s2)`. */
    def >>[O2](s2: => Stream[F, O2]): Stream[F, O2] =
      flatMap { _ =>
        s2
      }

    /**
      * Folds this stream with the monoid for `O`.
      *
      * @example {{{
      * scala> import cats.implicits._
      * scala> Stream(1, 2, 3, 4, 5).foldMonoid.toList
      * res0: List[Int] = List(15)
      * }}}
      */
    def foldMonoid(implicit O: Monoid[O]): Stream[F, O] =
      self.fold(O.empty)(O.combine)

    /**
      * Divide this streams into groups of elements received within a time window,
      * or limited by the number of the elements, whichever happens first.
      *
      * Empty groups can occur if no elements can be pulled from upstream
      * in a given time window. If you would like to have only non-empty groups,
      * you can `collect` out the empty groups (see the provided example).
      *
      * Note that n must be a positive integer.
      *
      * @example {{{
      * scala> import cats.data.NonEmptyVector, scala.concurrent.duration._
      *
      * scala> import cats.effect.IO, scala.concurrent.ExecutionContext.Implicits.global
      *
      * scala> val grouped = Stream(1, 2, 3, 4, 5).covary[IO].groupWithinV(2, 10.seconds)
      *
      * scala> grouped.compile.toVector.unsafeRunSync
      * res0: Vector[Vector[Int]] = Vector(Vector(1, 2), Vector(3, 4), Vector(5))
      *
      * // Or you can filter non-empty groups
      * scala> val emptyGrouped = Stream.empty
      *      |   .covaryOutput[Int]
      *      |   .covary[IO]
      *      |   .groupWithinV(2, 10.milliseconds)
      *      |   .map(NonEmptyVector.fromVector)
      *      |   .collect{case Some(nonEmpty) => nonEmpty}
      *
      * scala> emptyGrouped.compile.toVector.unsafeRunSync
      * res1: Vector[NonEmptyVector[Int]] = Vector()
      * }}}
      */
    def groupWithinV(n: Int, d: FiniteDuration)(implicit F: Effect[F],
                                                timer: Timer[F],
                                                ec: ExecutionContext): Stream[F, Vector[O]] = {
      require(n > 0)

      type TimeoutTick = Object

      // Note that this is NOT referentially transparent
      def unsafeNewTimeoutTick: TimeoutTick = new Object

      def startTimeout[A](tick: A, queue: async.mutable.Queue[F, A]): F[Unit] =
        async.fork {
          timer.sleep(d) *> queue.enqueue1(tick)
        }

      def startTimeoutPull[A](tick: A, queue: async.mutable.Queue[F, A]): Pull[F, Nothing, Unit] =
        Pull.eval(startTimeout(tick, queue))

      def go(acc: Vector[O],
             currentTimeout: TimeoutTick,
             stream: Stream[F, Either[TimeoutTick, O]],
             tickQueue: async.mutable.Queue[F, TimeoutTick]): Pull[F, Vector[O], Unit] =
        stream.pull
          .unconsLimit(n.toLong - acc.size.toLong)
          .covaryOutput[Vector[O]]
          .flatMap[F, Vector[O], Unit] {
            case None => Pull.output1(acc)
            case Some((segment, restOfStream)) =>
              val (timeoutOpt, elems) =
                segment.force.toVector.foldLeft((Option.empty[TimeoutTick], Vector.empty[O])) {
                  case (innerAcc, Left(timeout))               => innerAcc.copy(_1 = Some(timeout))
                  case ((timeout, builtUpVector), Right(elem)) => (timeout, builtUpVector :+ elem)
                }
              val totalNewElems = acc ++ elems
              val newTick = unsafeNewTimeoutTick
              val outputAndRestartTimeout =
                Pull.output1(totalNewElems) >> startTimeoutPull(newTick, tickQueue)
              val continueAccumulating =
                Pull.suspend(go(totalNewElems, currentTimeout, restOfStream, tickQueue))
              val restartGo =
                Pull.suspend(go(Vector.empty, newTick, restOfStream, tickQueue))
              timeoutOpt match {
                case Some(timedout) if timedout == currentTimeout =>
                  outputAndRestartTimeout >> restartGo
                case Some(_) =>
                  // We continue accumulating because the timeout we see is no
                  // longer a valid timeout, it's been invalidated by a new
                  // startTimeoutPull call, i.e. it's been invalidated by a restart
                  // in outputAndRestartTimeout
                  continueAccumulating
                case None if totalNewElems.size >= n =>
                  outputAndRestartTimeout >> restartGo
                case None =>
                  continueAccumulating
              }
          }

      for {
        tickQueue <- Stream.eval(async.mutable.Queue.synchronous[F, TimeoutTick](F, ec))
        mergedStreams = tickQueue.dequeue.eitherHaltR(self)
        newTick = unsafeNewTimeoutTick
        _ <- Stream.eval(startTimeout(newTick, tickQueue))
        result <- go(Vector.empty, newTick, mergedStreams, tickQueue).stream
      } yield result
    }

    def groupWithin(n: Int, d: FiniteDuration)(implicit F: Effect[F],
                                               timer: Timer[F],
                                               ec: ExecutionContext): Stream[F, Segment[O, Unit]] =
      groupWithinV(n, d).map(Segment.vector)

    /**
      * Determinsitically interleaves elements, starting on the left, terminating when the ends of both branches are reached naturally.
      *
      * @example {{{
      * scala> Stream(1, 2, 3).interleaveAll(Stream(4, 5, 6, 7)).toList
      * res0: List[Int] = List(1, 4, 2, 5, 3, 6, 7)
      * }}}
      */
    def interleaveAll(that: Stream[F, O]): Stream[F, O] =
      self
        .map(Some(_): Option[O])
        .zipAll(that.map(Some(_): Option[O]))(None, None)
        .flatMap {
          case (o1Opt, o2Opt) =>
            Stream(o1Opt.toSeq: _*) ++ Stream(o2Opt.toSeq: _*)
        }

    /**
      * Determinsitically interleaves elements, starting on the left, terminating when the end of either branch is reached naturally.
      *
      * @example {{{
      * scala> Stream(1, 2, 3).interleave(Stream(4, 5, 6, 7)).toList
      * res0: List[Int] = List(1, 4, 2, 5, 3, 6)
      * }}}
      */
    def interleave(that: Stream[F, O]): Stream[F, O] =
      this.zip(that).flatMap { case (o1, o2) => Stream(o1, o2) }

    /**
      * Let through the `s2` branch as long as the `s1` branch is `false`,
      * listening asynchronously for the left branch to become `true`.
      * This halts as soon as either branch halts.
      *
      * Consider using the overload that takes a `Signal`.
      *
      * Caution: interruption is checked as elements are pulled from the returned stream. As a result,
      * streams which stop pulling from the returned stream end up uninterrubtible. For example,
      * `s.interruptWhen(s2).flatMap(_ => infiniteStream)` will not be interrupted when `s2` is true
      * because `s1.interruptWhen(s2)` is never pulled for another element after the first element has been
      * emitted. To fix, consider `s.flatMap(_ => infiniteStream).interruptWhen(s2)`.
      */
    def interruptWhen(haltWhenTrue: Stream[F, Boolean])(implicit F: Effect[F],
                                                        ec: ExecutionContext): Stream[F, O] =
      Stream.eval(async.promise[F, Either[Throwable, Unit]]).flatMap { interruptL =>
        Stream.eval(async.promise[F, Unit]).flatMap { doneR =>
          Stream.eval(async.promise[F, Unit]).flatMap { interruptR =>
            def runR =
              haltWhenTrue
                .evalMap {
                  case false => F.pure(false)
                  case true  => interruptL.complete(Right(())).as(true)
                }
                .takeWhile(!_)
                .interruptWhen(interruptR.get.attempt)
                .compile
                .drain
                .attempt
                .flatMap { r =>
                  interruptL.complete(r).attempt *> doneR.complete(())
                }

            Stream.eval(async.fork(runR)) >>
              self
                .interruptWhen(interruptL.get)
                .onFinalize(interruptR.complete(()) *> doneR.get)

          }
        }
      }

    /** Alias for `interruptWhen(haltWhenTrue.discrete)`. */
    def interruptWhen(haltWhenTrue: async.immutable.Signal[F, Boolean])(
        implicit F: Effect[F],
        ec: ExecutionContext): Stream[F, O] =
      interruptWhen(haltWhenTrue.discrete)

    /**
      * Interrupts the stream, when `haltOnSignal` finishes its evaluation.
      */
    def interruptWhen(haltOnSignal: F[Either[Throwable, Unit]])(
        implicit F: Effect[F],
        ec: ExecutionContext): Stream[F, O] =
      Stream
        .getScope[F]
        .flatMap { scope =>
          Stream.eval(async.fork(haltOnSignal.flatMap(scope.interrupt))).flatMap { _ =>
            self
          }
        }
        .interruptScope

    /**
      * Creates a scope that may be interrupted by calling scope#interrupt.
      */
    def interruptScope(implicit F: Effect[F], ec: ExecutionContext): Stream[F, O] =
      Stream.fromFreeC(Algebra.interruptScope(self.get))

    /**
      * Nondeterministically merges a stream of streams (`outer`) in to a single stream,
      * opening at most `maxOpen` streams at any point in time.
      *
      * The outer stream is evaluated and each resulting inner stream is run concurrently,
      * up to `maxOpen` stream. Once this limit is reached, evaluation of the outer stream
      * is paused until one or more inner streams finish evaluating.
      *
      * When the outer stream stops gracefully, all inner streams continue to run,
      * resulting in a stream that will stop when all inner streams finish
      * their evaluation.
      *
      * When the outer stream fails, evaluation of all inner streams is interrupted
      * and the resulting stream will fail with same failure.
      *
      * When any of the inner streams fail, then the outer stream and all other inner
      * streams are interrupted, resulting in stream that fails with the error of the
      * stream that caused initial failure.
      *
      * Finalizers on each inner stream are run at the end of the inner stream,
      * concurrently with other stream computations.
      *
      * Finalizers on the outer stream are run after all inner streams have been pulled
      * from the outer stream but not before all inner streams terminate -- hence finalizers on the outer stream will run
      * AFTER the LAST finalizer on the very last inner stream.
      *
      * Finalizers on the returned stream are run after the outer stream has finished
      * and all open inner streams have finished.
      *
      * @param maxOpen    Maximum number of open inner streams at any time. Must be > 0.
      */
    def join[O2](maxOpen: Int)(implicit ev: O <:< Stream[F, O2],
                               F: Effect[F],
                               ec: ExecutionContext): Stream[F, O2] = {
      val _ = ev // Convince scalac that ev is used
      assert(maxOpen > 0, "maxOpen must be > 0, was: " + maxOpen)
      val outer = self.asInstanceOf[Stream[F, Stream[F, O2]]]
      Stream.eval(async.signalOf(None: Option[Option[Throwable]])).flatMap { done =>
        Stream.eval(async.semaphore(maxOpen)).flatMap { available =>
          Stream
            .eval(async.signalOf(1l))
            .flatMap { running => // starts with 1 because outer stream is running by default
              Stream
                .eval(async.mutable.Queue
                  .synchronousNoneTerminated[F, Segment[O2, Unit]])
                .flatMap { outputQ => // sync queue assures we won't overload heap when resulting stream is not able to catchup with inner streams
                  // stops the join evaluation
                  // all the streams will be terminated. If err is supplied, that will get attached to any error currently present
                  def stop(rslt: Option[Throwable]): F[Unit] =
                    done.modify {
                      case rslt0 @ Some(Some(err0)) =>
                        rslt.fold[Option[Option[Throwable]]](rslt0) { err =>
                          Some(Some(new CompositeFailure(err0, NonEmptyList.of(err))))
                        }
                      case _ => Some(rslt)
                    } *> outputQ.enqueue1(None)

                  val incrementRunning: F[Unit] = running.modify(_ + 1).as(())
                  val decrementRunning: F[Unit] =
                    running.modify(_ - 1).flatMap { c =>
                      if (c.now == 0) stop(None)
                      else F.unit
                    }

                  // runs inner stream
                  // each stream is forked.
                  // terminates when killSignal is true
                  // if fails will enq in queue failure
                  // note that supplied scope's resources must be leased before the inner stream forks the execution to another thread
                  // and that it must be released once the inner stream terminates or fails.
                  def runInner(inner: Stream[F, O2], outerScope: Scope[F]): F[Unit] =
                    outerScope.lease.flatMap {
                      case Some(lease) =>
                        available.decrement *>
                          incrementRunning *>
                          async.fork {
                            inner.segments
                              .evalMap { s =>
                                outputQ.enqueue1(Some(s))
                              }
                              .interruptWhen(done.map(_.nonEmpty))
                              . // must be AFTER enqueue to the sync queue, otherwise the process may hang to enq last item while being interrupted
                              compile
                              .drain
                              .attempt
                              .flatMap {
                                case Right(()) => F.unit
                                case Left(err) => stop(Some(err))
                              } *>
                              lease.cancel *> //todo: propagate failure here on exception ???
                              available.increment *>
                              decrementRunning
                          }

                      case None =>
                        F.raiseError(
                          new Throwable("Outer scope is closed during inner stream startup"))
                    }

                  // runs the outer stream, interrupts when kill == true, and then decrements the `running`
                  def runOuter: F[Unit] =
                    outer
                      .flatMap { inner =>
                        Stream.getScope[F].evalMap { outerScope =>
                          runInner(inner, outerScope)
                        }
                      }
                      .interruptWhen(done.map(_.nonEmpty))
                      .compile
                      .drain
                      .attempt
                      .flatMap {
                        case Right(_)  => F.unit
                        case Left(err) => stop(Some(err))
                      } *> decrementRunning

                  // awaits when all streams (outer + inner) finished,
                  // and then collects result of the stream (outer + inner) execution
                  def signalResult: Stream[F, O2] =
                    done.discrete.take(1).flatMap {
                      _.flatten
                        .fold[Stream[Pure, O2]](Stream.empty)(Stream.raiseError)
                    }

                  Stream.eval(async.start(runOuter)) >>
                    outputQ.dequeue.unNoneTerminate
                      .flatMap { Stream.segment(_).covary[F] }
                      .onFinalize {
                        stop(None) *> running.discrete
                          .dropWhile(_ > 0)
                          .take(1)
                          .compile
                          .drain
                      } ++
                      signalResult
                }
            }
        }
      }
    }

    /** Like [[join]] but races all inner streams simultaneously. */
    def joinUnbounded[O2](implicit ev: O <:< Stream[F, O2],
                          F: Effect[F],
                          ec: ExecutionContext): Stream[F, O2] =
      join(Int.MaxValue)

    /**
      * Interleaves the two inputs nondeterministically. The output stream
      * halts after BOTH `s1` and `s2` terminate normally, or in the event
      * of an uncaught failure on either `s1` or `s2`. Has the property that
      * `merge(Stream.empty, s) == s` and `merge(raiseError(e), s)` will
      * eventually terminate with `raiseError(e)`, possibly after emitting some
      * elements of `s` first.
      *
      * The implementation always tries to pull one chunk from each side
      * before waiting for it to be consumed by resulting stream.
      * As such, there may be up to two chunks (one from each stream)
      * waiting to be processed while the resulting stream
      * is processing elements.
      *
      * Also note that if either side produces empty chunk,
      * the processing on that side continues,
      * w/o downstream requiring to consume result.
      *
      * If either side does not emit anything (i.e. as result of drain) that side
      * will continue to run even when the resulting stream did not ask for more data.
      *
      * Note that even when `s1.merge(s2.drain) == s1.concurrently(s2)`, the `concurrently` alternative is
      * more efficient.
      *
      *
      * @example {{{
      * scala> import scala.concurrent.duration._, scala.concurrent.ExecutionContext.Implicits.global, cats.effect.IO
      * scala> val s = Scheduler[IO](1).flatMap { scheduler =>
      *      |   val s1 = scheduler.awakeEvery[IO](500.millis).scan(0)((acc, i) => acc + 1)
      *      |   s1.merge(scheduler.sleep_[IO](250.millis) ++ s1)
      *      | }
      * scala> s.take(6).compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(0, 0, 1, 1, 2, 2)
      * }}}
      */
    def merge[O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                            ec: ExecutionContext): Stream[F, O2] =
      Stream.eval(async.semaphore(0)).flatMap { doneSem =>
        Stream.eval(async.promise[F, Unit]).flatMap { interruptL =>
          Stream.eval(async.promise[F, Unit]).flatMap { interruptR =>
            Stream.eval(async.promise[F, Throwable]).flatMap { interruptY =>
              Stream
                .eval(async.unboundedQueue[F, Option[(F[Unit], Segment[O2, Unit])]])
                .flatMap { outQ => // note that the queue actually contains up to 2 max elements thanks to semaphores guarding each side.
                  def runUpstream(s: Stream[F, O2], interrupt: Promise[F, Unit]): F[Unit] =
                    async.semaphore(1).flatMap { guard =>
                      s.segments
                        .interruptWhen(interrupt.get.attempt)
                        .evalMap { segment =>
                          guard.decrement >>
                            outQ.enqueue1(Some((guard.increment, segment)))
                        }
                        .compile
                        .drain
                        .attempt
                        .flatMap {
                          case Right(_) =>
                            doneSem.increment >>
                              doneSem.decrementBy(2) >>
                              async.fork(outQ.enqueue1(None))
                          case Left(err) =>
                            interruptY.complete(err) >>
                              doneSem.increment
                        }
                    }

                  Stream.eval(async.fork(runUpstream(self, interruptL))) >>
                    Stream.eval(async.fork(runUpstream(that, interruptR))) >>
                    outQ.dequeue.unNoneTerminate
                      .flatMap {
                        case (signal, segment) =>
                          Stream.eval(signal) >>
                            Stream.segment(segment)
                      }
                      .interruptWhen(interruptY.get.map(Left(_): Either[Throwable, Unit]))
                      .onFinalize {
                        interruptL.complete(()) >>
                          interruptR.complete(())
                      }

                }

            }
          }
        }
      }

    /** Like `merge`, but halts as soon as _either_ branch halts. */
    def mergeHaltBoth[O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                                    ec: ExecutionContext): Stream[F, O2] =
      self.noneTerminate.merge(that.noneTerminate).unNoneTerminate

    /** Like `merge`, but halts as soon as the `s1` branch halts. */
    def mergeHaltL[O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                                 ec: ExecutionContext): Stream[F, O2] =
      self.noneTerminate.merge(that.map(Some(_))).unNoneTerminate

    /** Like `merge`, but halts as soon as the `s2` branch halts. */
    def mergeHaltR[O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                                 ec: ExecutionContext): Stream[F, O2] =
      that.mergeHaltL(self)

    /**
      * Like `observe` but observes with a function `O => F[Unit]` instead of a sink.
      * Alias for `evalMap(o => f(o).as(o))`.
      */
    def observe1(f: O => F[Unit])(implicit F: Functor[F]): Stream[F, O] =
      self.evalMap(o => f(o).as(o))

    /**
      * Synchronously sends values through `sink`.
      *
      * If `sink` fails, then resulting stream will fail. If sink `halts` the evaluation will halt too.
      *
      * Note that observe will only output full segments of `O` that are known to be successfully processed
      * by `sink`. So if Sink terminates/fail in midle of segment processing, the segment will not be available
      * in resulting stream.
      *
      * @example {{{
      * scala> import scala.concurrent.ExecutionContext.Implicits.global, cats.effect.IO, cats.implicits._
      * scala> Stream(1, 2, 3).covary[IO].observe(Sink.showLinesStdOut).map(_ + 1).compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(2, 3, 4)
      * }}}
      */
    def observe(sink: Sink[F, O])(implicit F: Effect[F], ec: ExecutionContext): Stream[F, O] =
      observeAsync(1)(sink)

    /** Send chunks through `sink`, allowing up to `maxQueued` pending _segments_ before blocking `s`. */
    def observeAsync(maxQueued: Int)(sink: Sink[F, O])(implicit F: Effect[F],
                                                       ec: ExecutionContext): Stream[F, O] =
      Stream.eval(async.semaphore[F](maxQueued - 1)).flatMap { guard =>
        Stream.eval(async.unboundedQueue[F, Option[Segment[O, Unit]]]).flatMap { outQ =>
          Stream.eval(async.unboundedQueue[F, Option[Segment[O, Unit]]]).flatMap { sinkQ =>
            val inputStream =
              self.segments.noneTerminate.evalMap {
                case Some(segment) =>
                  sinkQ.enqueue1(Some(segment)) >>
                    guard.decrement

                case None =>
                  sinkQ.enqueue1(None)
              }

            val sinkStream =
              sinkQ.dequeue.unNoneTerminate
                .flatMap { segment =>
                  Stream.segment(segment) ++
                    Stream.eval_(outQ.enqueue1(Some(segment)))
                }
                .to(sink) ++
                Stream.eval_(outQ.enqueue1(None))

            val runner =
              sinkStream.concurrently(inputStream) ++
                Stream.eval_(outQ.enqueue1(None))

            val outputStream =
              outQ.dequeue.unNoneTerminate
                .flatMap { segment =>
                  Stream.segment(segment) ++
                    Stream.eval_(guard.increment)
                }

            outputStream.concurrently(runner)

          }
        }
      }

    /**
      * Run `s2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`.
      *
      * Note: this should *not* be used for resource cleanup! Use `bracket` or `onFinalize` instead.
      *
      * @example {{{
      * scala> Stream(1, 2, 3).onComplete(Stream(4, 5)).toList
      * res0: List[Int] = List(1, 2, 3, 4, 5)
      * }}}
      */
    def onComplete[O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
      (self.handleErrorWith(e => s2 ++ Stream.raiseError(e))) ++ s2

    /**
      * If `this` terminates with `Stream.raiseError(e)`, invoke `h(e)`.
      *
      * @example {{{
      * scala> Stream(1, 2, 3).append(Stream.raiseError(new RuntimeException)).handleErrorWith(t => Stream(0)).toList
      * res0: List[Int] = List(1, 2, 3, 0)
      * }}}
      */
    def handleErrorWith[O2 >: O](h: Throwable => Stream[F, O2]): Stream[F, O2] =
      fromFreeC(Algebra.scope(self.get[F, O2]).handleErrorWith { e =>
        h(e).get[F, O2]
      })

    /**
      * Run the supplied effectful action at the end of this stream, regardless of how the stream terminates.
      */
    def onFinalize(f: F[Unit])(implicit F: Applicative[F]): Stream[F, O] =
      Stream.bracket(F.pure(()))(_ => self, _ => f)

    /** Like `interrupt` but resumes the stream when left branch goes to true. */
    def pauseWhen(pauseWhenTrue: Stream[F, Boolean])(implicit F: Effect[F],
                                                     ec: ExecutionContext): Stream[F, O] =
      async.hold[F, Option[Boolean]](Some(false), pauseWhenTrue.noneTerminate).flatMap {
        pauseSignal =>
          def pauseIfNeeded: F[Unit] =
            pauseSignal.get.flatMap {
              case Some(false) => F.pure(())
              case _           => pauseSignal.discrete.dropWhile(_.getOrElse(true)).take(1).compile.drain
            }

          self.segments
            .flatMap { segment =>
              Stream.eval(pauseIfNeeded) >>
                Stream.segment(segment)
            }
            .interruptWhen(pauseSignal.discrete.map(_.isEmpty))
      }

    /** Alias for `pauseWhen(pauseWhenTrue.discrete)`. */
    def pauseWhen(pauseWhenTrue: async.immutable.Signal[F, Boolean])(
        implicit F: Effect[F],
        ec: ExecutionContext): Stream[F, O] =
      pauseWhen(pauseWhenTrue.discrete)

    /** Alias for `prefetchN(1)`. */
    def prefetch(implicit ec: ExecutionContext, F: Effect[F]): Stream[F, O] = prefetchN(1)

    /**
      * Behaves like `identity`, but starts fetches up to `n` segments in parallel with downstream
      * consumption, enabling processing on either side of the `prefetchN` to run in parallel.
      */
    def prefetchN(n: Int)(implicit ec: ExecutionContext, F: Effect[F]): Stream[F, O] =
      Stream.eval(async.boundedQueue[F, Option[Segment[O, Unit]]](n)).flatMap { queue =>
        queue.dequeue.unNoneTerminate
          .flatMap(Stream.segment(_))
          .concurrently(self.segments.noneTerminate.to(queue.enqueue))
      }

    /** Gets a projection of this stream that allows converting it to a `Pull` in a number of ways. */
    def pull: Stream.ToPull[F, O] = new Stream.ToPull(self.free)

    /**
      * Reduces this stream with the Semigroup for `O`.
      *
      * @example {{{
      * scala> import cats.implicits._
      * scala> Stream("The", "quick", "brown", "fox").intersperse(" ").reduceSemigroup.toList
      * res0: List[String] = List(The quick brown fox)
      * }}}
      */
    def reduceSemigroup(implicit S: Semigroup[O]): Stream[F, O] =
      self.reduce(S.combine(_, _))

    /**
      * Repartitions the input with the function `f`. On each step `f` is applied
      * to the input and all elements but the last of the resulting sequence
      * are emitted. The last element is then appended to the next input using the
      * Semigroup `S`.
      *
      * @example {{{
      * scala> import cats.implicits._
      * scala> Stream("Hel", "l", "o Wor", "ld").repartition(s => Chunk.array(s.split(" "))).toList
      * res0: List[String] = List(Hello, World)
      * }}}
      */
    def repartition(f: O => Chunk[O])(implicit S: Semigroup[O]): Stream[F, O] =
      pull
        .scanSegments(Option.empty[O]) { (carry, segment) =>
          segment
            .scan((Segment.empty[O], carry)) {
              case ((_, carry), o) =>
                val o2: O = carry.fold(o)(S.combine(_, o))
                val partitions: Chunk[O] = f(o2)
                if (partitions.isEmpty) Segment.chunk(partitions) -> None
                else if (partitions.size == 1) Segment.empty -> partitions.last
                else
                  Segment.chunk(partitions.take(partitions.size - 1)) -> partitions.last
            }
            .mapResult(_._2)
            .flatMap { case (out, carry) => out }
            .mapResult { case ((out, carry), unit) => carry }
        }
        .flatMap {
          case Some(carry) => Pull.output1(carry); case None => Pull.done
        }
        .stream

    /**
      * Repeatedly invokes `using`, running the resultant `Pull` each time, halting when a pull
      * returns `None` instead of `Some(nextStream)`.
      */
    def repeatPull[O2](
        using: Stream.ToPull[F, O] => Pull[F, O2, Option[Stream[F, O]]]): Stream[F, O2] =
      Pull.loop(using.andThen(_.map(_.map(_.pull))))(self.pull).stream

    /** Deprecated alias for `compile.drain`. */
    @deprecated("Use compile.drain instead", "0.10.0")
    def run(implicit F: Sync[F]): F[Unit] = compile.drain

    /** Deprecated alias for `compile.fold`. */
    @deprecated("Use compile.fold instead", "0.10.0")
    def runFold[B](init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
      compile.fold(init)(f)

    /** Deprecated alias for `compile.toVector`. */
    @deprecated("Use compile.toVector instead", "0.10.0")
    def runLog(implicit F: Sync[F]): F[Vector[O]] = compile.toVector

    /** Deprecated alias for `compile.last`. */
    @deprecated("Use compile.last instead", "0.10.0")
    def runLast(implicit F: Sync[F]): F[Option[O]] = compile.last

    /**
      * Like `scan` but `f` is applied to each segment of the source stream.
      * The resulting segment is emitted and the result of the segment is used in the
      * next invocation of `f`.
      *
      * Many stateful pipes can be implemented efficiently (i.e., supporting fusion) with this method.
      */
    def scanSegments[S, O2](init: S)(f: (S, Segment[O, Unit]) => Segment[O2, S]): Stream[F, O2] =
      scanSegmentsOpt(init)(s => Some(seg => f(s, seg)))

    /**
      * More general version of `scanSegments` where the current state (i.e., `S`) can be inspected
      * to determine if another segment should be pulled or if the stream should terminate.
      * Termination is signaled by returning `None` from `f`. Otherwise, a function which consumes
      * the next segment is returned wrapped in `Some`.
      *
      * @example {{{
      * scala> def take[F[_],O](s: Stream[F,O], n: Long): Stream[F,O] =
      *      |   s.scanSegmentsOpt(n) { n => if (n <= 0) None else Some(_.take(n).mapResult(_.fold(_._2, _ => 0))) }
      * scala> take(Stream.range(0,100), 5).toList
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def scanSegmentsOpt[S, O2](init: S)(
        f: S => Option[Segment[O, Unit] => Segment[O2, S]]): Stream[F, O2] =
      self.pull.scanSegmentsOpt(init)(f).stream

    /**
      * Transforms this stream using the given `Pipe`.
      *
      * @example {{{
      * scala> Stream("Hello", "world").through(text.utf8Encode).toVector.toArray
      * res0: Array[Byte] = Array(72, 101, 108, 108, 111, 119, 111, 114, 108, 100)
      * }}}
      */
    def through[O2](f: Pipe[F, O, O2]): Stream[F, O2] = f(self)

    /**
      * Transforms this stream using the given pure `Pipe`.
      *
      * Sometimes this has better type inference than `through` (e.g., when `F` is `Nothing`).
      */
    def throughPure[O2](f: Pipe[Pure, O, O2]): Stream[F, O2] = f(self)

    /** Transforms this stream and `s2` using the given `Pipe2`. */
    def through2[O2, O3](s2: Stream[F, O2])(f: Pipe2[F, O, O2, O3]): Stream[F, O3] =
      f(self, s2)

    /**
      * Transforms this stream and `s2` using the given pure `Pipe2`.
      *
      * Sometimes this has better type inference than `through2` (e.g., when `F` is `Nothing`).
      */
    def through2Pure[O2, O3](s2: Stream[F, O2])(f: Pipe2[Pure, O, O2, O3]): Stream[F, O3] =
      f(self, s2)

    /**
      * Applies the given sink to this stream.
      *
      * @example {{{
      * scala> import cats.effect.IO, cats.implicits._
      * scala> Stream(1,2,3).covary[IO].to(Sink.showLinesStdOut).compile.drain.unsafeRunSync
      * res0: Unit = ()
      * }}}
      */
    def to(f: Sink[F, O]): Stream[F, Unit] = f(self)

    /**
      * Translates effect type from `F` to `G` using the supplied `FunctionK`.
      */
    def translate[G[_]](u: F ~> G): Stream[G, O] =
      Stream.fromFreeC[G, O](Algebra.translate[F, G, O](self.get, u))

    private type ZipWithCont[G[_], I, O2, R] =
      Either[(Segment[I, Unit], Stream[G, I]), Stream[G, I]] => Pull[G, O2, Option[R]]

    private def zipWith_[O2, O3](that: Stream[F, O2])(
        k1: ZipWithCont[F, O, O3, Nothing],
        k2: ZipWithCont[F, O2, O3, Nothing])(f: (O, O2) => O3): Stream[F, O3] = {
      def go(leg1: StepLeg[F, O], leg2: StepLeg[F, O2]): Pull[F, O3, Option[Nothing]] =
        Pull.segment(leg1.head.zipWith(leg2.head)(f)).flatMap {
          case Left(((), extra2)) =>
            leg1.stepLeg.flatMap {
              case None      => k2(Left((extra2, leg2.stream)))
              case Some(tl1) => go(tl1, leg2.setHead(extra2))
            }
          case Right(((), extra1)) =>
            leg2.stepLeg.flatMap {
              case None      => k1(Left((extra1, leg1.stream)))
              case Some(tl2) => go(leg1.setHead(extra1), tl2)
            }
        }

      self.pull.stepLeg.flatMap {
        case Some(leg1) =>
          that.pull.stepLeg
            .flatMap {
              case Some(leg2) => go(leg1, leg2)
              case None       => k1(Left((leg1.head, leg1.stream)))
            }

        case None => k2(Right(that))
      }.stream
    }

    /**
      * Determinsitically zips elements, terminating when the ends of both branches
      * are reached naturally, padding the left branch with `pad1` and padding the right branch
      * with `pad2` as necessary.
      *
      *
      * @example {{{
      * scala> Stream(1,2,3).zipAll(Stream(4,5,6,7))(0,0).toList
      * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6), (0,7))
      * }}}
      */
    def zipAll[O2](that: Stream[F, O2])(pad1: O, pad2: O2): Stream[F, (O, O2)] =
      zipAllWith(that)(pad1, pad2)(Tuple2.apply)

    /**
      * Determinsitically zips elements with the specified function, terminating
      * when the ends of both branches are reached naturally, padding the left
      * branch with `pad1` and padding the right branch with `pad2` as necessary.
      *
      * @example {{{
      * scala> Stream(1,2,3).zipAllWith(Stream(4,5,6,7))(0, 0)(_ + _).toList
      * res0: List[Int] = List(5, 7, 9, 7)
      * }}}
      */
    def zipAllWith[O2, O3](that: Stream[F, O2])(pad1: O, pad2: O2)(
        f: (O, O2) => O3): Stream[F, O3] = {
      def cont1(z: Either[(Segment[O, Unit], Stream[F, O]), Stream[F, O]])
        : Pull[F, O3, Option[Nothing]] = {
        def contLeft(s: Stream[F, O]): Pull[F, O3, Option[Nothing]] =
          s.pull.uncons.flatMap {
            case None => Pull.pure(None)
            case Some((hd, tl)) =>
              Pull.output(hd.map(o => f(o, pad2))) >> contLeft(tl)
          }
        z match {
          case Left((hd, tl)) =>
            Pull.output(hd.map(o => f(o, pad2))) >> contLeft(tl)
          case Right(h) => contLeft(h)
        }
      }
      def cont2(z: Either[(Segment[O2, Unit], Stream[F, O2]), Stream[F, O2]])
        : Pull[F, O3, Option[Nothing]] = {
        def contRight(s: Stream[F, O2]): Pull[F, O3, Option[Nothing]] =
          s.pull.uncons.flatMap {
            case None => Pull.pure(None)
            case Some((hd, tl)) =>
              Pull.output(hd.map(o2 => f(pad1, o2))) >> contRight(tl)
          }
        z match {
          case Left((hd, tl)) =>
            Pull.output(hd.map(o2 => f(pad1, o2))) >> contRight(tl)
          case Right(h) => contRight(h)
        }
      }
      zipWith_[O2, O3](that)(cont1, cont2)(f)
    }

    /**
      * Determinsitically zips elements, terminating when the end of either branch is reached naturally.
      *
      * @example {{{
      * scala> Stream(1, 2, 3).zip(Stream(4, 5, 6, 7)).toList
      * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6))
      * }}}
      */
    def zip[O2](that: Stream[F, O2]): Stream[F, (O, O2)] =
      zipWith(that)(Tuple2.apply)

    /**
      * Determinsitically zips elements using the specified function,
      * terminating when the end of either branch is reached naturally.
      *
      * @example {{{
      * scala> Stream(1, 2, 3).zipWith(Stream(4, 5, 6, 7))(_ + _).toList
      * res0: List[Int] = List(5, 7, 9)
      * }}}
      */
    def zipWith[O2, O3](that: Stream[F, O2])(f: (O, O2) => O3): Stream[F, O3] =
      zipWith_[O2, O3](that)(sh => Pull.pure(None), h => Pull.pure(None))(f)
  }

  /** Provides syntax for pure empty pipes. */
  implicit def EmptyOps(s: Stream[Pure, Nothing]): EmptyOps =
    new EmptyOps(s.get[Pure, Nothing])

  /** Provides syntax for pure empty pipes. */
  final class EmptyOps private[Stream] (private val free: FreeC[Algebra[Pure, Nothing, ?], Unit])
      extends AnyVal {
    private def self: Stream[Pure, Nothing] =
      Stream.fromFreeC[Pure, Nothing](free)

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F, Nothing] = self.asInstanceOf[Stream[F, Nothing]]

    /** Lifts this stream to the specified effect and output types. */
    def covaryAll[F[_], O]: Stream[F, O] = self.asInstanceOf[Stream[F, O]]
  }

  /** Provides syntax for pure pipes. */
  implicit def PureOps[O](s: Stream[Pure, O]): PureOps[O] =
    new PureOps(s.get[Pure, O])

  /** Provides syntax for pure pipes. */
  final class PureOps[O] private[Stream] (private val free: FreeC[Algebra[Pure, O, ?], Unit])
      extends AnyVal {
    private def self: Stream[Pure, O] = Stream.fromFreeC[Pure, O](free)

    def ++[F[_], O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
      covary[F].append(s2)

    def append[F[_], O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
      covary[F].append(s2)

    def concurrently[F[_], O2](that: Stream[F, O2])(implicit F: Effect[F],
                                                    ec: ExecutionContext): Stream[F, O] =
      covary[F].concurrently(that)

    def covary[F[_]]: Stream[F, O] = self.asInstanceOf[Stream[F, O]]

    def covaryAll[F[_], O2 >: O]: Stream[F, O2] =
      self.asInstanceOf[Stream[F, O2]]

    def either[F[_], O2](s2: Stream[F, O2])(implicit F: Effect[F],
                                            ec: ExecutionContext): Stream[F, Either[O, O2]] =
      covary[F].either(s2)

    def evalMap[F[_], O2](f: O => F[O2]): Stream[F, O2] = covary[F].evalMap(f)

    def evalScan[F[_], O2](z: O2)(f: (O2, O) => F[O2]): Stream[F, O2] =
      covary[F].evalScan(z)(f)

    def mapAsync[F[_], O2](parallelism: Int)(
        f: O => F[O2])(implicit F: Effect[F], executionContext: ExecutionContext): Stream[F, O2] =
      covary[F].mapAsync(parallelism)(f)

    def mapAsyncUnordered[F[_], O2](parallelism: Int)(
        f: O => F[O2])(implicit F: Effect[F], executionContext: ExecutionContext): Stream[F, O2] =
      covary[F].mapAsyncUnordered(parallelism)(f)

    def flatMap[F[_], O2](f: O => Stream[F, O2]): Stream[F, O2] =
      covary[F].flatMap(f)

    def >>[F[_], O2](s2: => Stream[F, O2]): Stream[F, O2] = flatMap { _ =>
      s2
    }

    def interleave[F[_], O2 >: O](s2: Stream[F, O2]): Stream[F, O2] =
      covaryAll[F, O2].interleave(s2)

    def interleaveAll[F[_], O2 >: O](s2: Stream[F, O2]): Stream[F, O2] =
      covaryAll[F, O2].interleaveAll(s2)

    def interruptWhen[F[_]](haltWhenTrue: Stream[F, Boolean])(implicit F: Effect[F],
                                                              ec: ExecutionContext): Stream[F, O] =
      covary[F].interruptWhen(haltWhenTrue)

    def interruptWhen[F[_]](haltWhenTrue: async.immutable.Signal[F, Boolean])(
        implicit F: Effect[F],
        ec: ExecutionContext): Stream[F, O] =
      covary[F].interruptWhen(haltWhenTrue)

    def join[F[_], O2](maxOpen: Int)(implicit ev: O <:< Stream[F, O2],
                                     F: Effect[F],
                                     ec: ExecutionContext): Stream[F, O2] =
      covary[F].join(maxOpen)

    def joinUnbounded[F[_], O2](implicit ev: O <:< Stream[F, O2],
                                F: Effect[F],
                                ec: ExecutionContext): Stream[F, O2] =
      covary[F].joinUnbounded

    def merge[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                                  ec: ExecutionContext): Stream[F, O2] =
      covary[F].merge(that)

    def mergeHaltBoth[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                                          ec: ExecutionContext): Stream[F, O2] =
      covary[F].mergeHaltBoth(that)

    def mergeHaltL[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                                       ec: ExecutionContext): Stream[F, O2] =
      covary[F].mergeHaltL(that)

    def mergeHaltR[F[_], O2 >: O](that: Stream[F, O2])(implicit F: Effect[F],
                                                       ec: ExecutionContext): Stream[F, O2] =
      covary[F].mergeHaltR(that)

    def observe1[F[_]](f: O => F[Unit])(implicit F: Functor[F]): Stream[F, O] =
      covary[F].observe1(f)

    def observe[F[_]](sink: Sink[F, O])(implicit F: Effect[F], ec: ExecutionContext): Stream[F, O] =
      covary[F].observe(sink)

    def observeAsync[F[_]](maxQueued: Int)(sink: Sink[F, O])(implicit F: Effect[F],
                                                             ec: ExecutionContext): Stream[F, O] =
      covary[F].observeAsync(maxQueued)(sink)

    def onComplete[F[_], O2 >: O](s2: => Stream[F, O2]): Stream[F, O2] =
      covary[F].onComplete(s2)

    def handleErrorWith[F[_], O2 >: O](h: Throwable => Stream[F, O2]): Stream[F, O2] =
      covary[F].handleErrorWith(h)

    def onFinalize[F[_]](f: F[Unit])(implicit F: Applicative[F]): Stream[F, O] =
      covary[F].onFinalize(f)

    def pauseWhen[F[_]](pauseWhenTrue: Stream[F, Boolean])(implicit F: Effect[F],
                                                           ec: ExecutionContext): Stream[F, O] =
      covary[F].pauseWhen(pauseWhenTrue)

    def pauseWhen[F[_]](pauseWhenTrue: async.immutable.Signal[F, Boolean])(
        implicit F: Effect[F],
        ec: ExecutionContext): Stream[F, O] =
      covary[F].pauseWhen(pauseWhenTrue)

    /** Runs this pure stream and returns the emitted elements in a collection of the specified type. Note: this method is only available on pure streams. */
    def to[C[_]](implicit cbf: CanBuildFrom[Nothing, O, C[O]]): C[O] =
      covary[IO].compile.to[C].unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a list. Note: this method is only available on pure streams. */
    def toList: List[O] = covary[IO].compile.toList.unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a vector. Note: this method is only available on pure streams. */
    def toVector: Vector[O] = covary[IO].compile.toVector.unsafeRunSync

    def zipAll[F[_], O2](that: Stream[F, O2])(pad1: O, pad2: O2): Stream[F, (O, O2)] =
      covary[F].zipAll(that)(pad1, pad2)

    def zipAllWith[F[_], O2, O3](that: Stream[F, O2])(pad1: O, pad2: O2)(
        f: (O, O2) => O3): Stream[F, O3] =
      covary[F].zipAllWith(that)(pad1, pad2)(f)

    def zip[F[_], O2](s2: Stream[F, O2]): Stream[F, (O, O2)] =
      covary[F].zip(s2)

    def zipWith[F[_], O2, O3](s2: Stream[F, O2])(f: (O, O2) => O3): Stream[F, O3] =
      covary[F].zipWith(s2)(f)
  }

  implicit def IdOps[O](s: Stream[Id, O]): IdOps[O] =
    new IdOps(s.get[Id, O])

  /** Provides syntax for pure pipes based on `cats.Id`. */
  final class IdOps[O] private[Stream] (private val free: FreeC[Algebra[Id, O, ?], Unit])
      extends AnyVal {
    private def self: Stream[Id, O] = Stream.fromFreeC[Id, O](free)

    private def idToApplicative[F[_]: Applicative]: Id ~> F =
      new (Id ~> F) { def apply[A](a: Id[A]) = a.pure[F] }

    def covaryId[F[_]: Applicative]: Stream[F, O] = self.translate(idToApplicative[F])
  }

  /** Projection of a `Stream` providing various ways to get a `Pull` from the `Stream`. */
  final class ToPull[F[_], O] private[Stream] (
      private val free: FreeC[Algebra[Nothing, Nothing, ?], Unit])
      extends AnyVal {

    private def self: Stream[F, O] =
      Stream.fromFreeC(free.asInstanceOf[FreeC[Algebra[F, O, ?], Unit]])

    /**
      * Waits for a segment of elements to be available in the source stream.
      * The segment of elements along with a new stream are provided as the resource of the returned pull.
      * The new stream can be used for subsequent operations, like awaiting again.
      * A `None` is returned as the resource of the pull upon reaching the end of the stream.
      */
    def uncons: Pull[F, Nothing, Option[(Segment[O, Unit], Stream[F, O])]] =
      Pull.fromFreeC(Algebra.uncons(self.get)).map {
        _.map { case (hd, tl) => (hd, Stream.fromFreeC(tl)) }
      }

    /** Like [[uncons]] but waits for a chunk instead of an entire segment. */
    def unconsChunk: Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.force.unconsChunk match {
            case Left(())        => tl.pull.unconsChunk
            case Right((c, tl2)) => Pull.pure(Some((c, tl.cons(tl2))))
          }
      }

    /** Like [[uncons]] but waits for a single element instead of an entire segment. */
    def uncons1: Pull[F, Nothing, Option[(O, Stream[F, O])]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.force.uncons1 match {
            case Left(_)          => tl.pull.uncons1
            case Right((hd, tl2)) => Pull.pure(Some(hd -> tl.cons(tl2)))
          }
      }

    /**
      * Like [[uncons]], but returns a segment of no more than `n` elements.
      *
      * The returned segment has a result tuple consisting of the remaining limit
      * (`n` minus the segment size, or 0, whichever is larger) and the remainder
      * of the source stream.
      *
      * `Pull.pure(None)` is returned if the end of the source stream is reached.
      */
    def unconsLimit(n: Long): Pull[F, Nothing, Option[(Segment[O, Unit], Stream[F, O])]] = {
      require(n > 0)
      uncons.flatMap {
        case Some((hd, tl)) =>
          hd.force.splitAt(n) match {
            case Left((_, chunks, rem)) =>
              Pull.pure(Some(Segment.catenated(chunks.map(Segment.chunk)) -> tl))
            case Right((chunks, tl2)) =>
              Pull.pure(Some(Segment.catenated(chunks.map(Segment.chunk)) -> tl.cons(tl2)))
          }
        case None => Pull.pure(None)
      }
    }

    /**
      * Like [[uncons]], but returns a segment of exactly `n` elements, splitting segments as necessary.
      *
      * `Pull.pure(None)` is returned if the end of the source stream is reached.
      */
    def unconsN(
        n: Long,
        allowFewer: Boolean = false): Pull[F, Nothing, Option[(Segment[O, Unit], Stream[F, O])]] = {
      def go(acc: Catenable[Segment[O, Unit]],
             n: Long,
             s: Stream[F, O]): Pull[F, Nothing, Option[(Segment[O, Unit], Stream[F, O])]] =
        s.pull.uncons.flatMap {
          case None =>
            if (allowFewer && acc.nonEmpty)
              Pull.pure(Some((Segment.catenated(acc), Stream.empty)))
            else Pull.pure(None)
          case Some((hd, tl)) =>
            hd.force.splitAt(n) match {
              case Left((_, chunks, rem)) =>
                if (rem > 0) go(acc ++ chunks.map(Segment.chunk), rem, tl)
                else
                  Pull.pure(Some(Segment.catenated(acc ++ chunks.map(Segment.chunk)) -> tl))
              case Right((chunks, tl2)) =>
                Pull.pure(
                  Some(Segment.catenated(acc ++ chunks.map(Segment.chunk)) -> tl
                    .cons(tl2)))
            }
        }
      if (n <= 0) Pull.pure(Some((Segment.empty, self)))
      else go(Catenable.empty, n, self)
    }

    /** Drops the first `n` elements of this `Stream`, and returns the new `Stream`. */
    def drop(n: Long): Pull[F, Nothing, Option[Stream[F, O]]] =
      if (n <= 0) Pull.pure(Some(self))
      else
        uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            hd.force.drop(n) match {
              case Left((_, rem)) =>
                if (rem > 0) tl.pull.drop(rem)
                else Pull.pure(Some(tl))
              case Right(tl2) =>
                Pull.pure(Some(tl.cons(tl2)))
            }
        }

    /** Like [[dropWhile]], but drops the first value which tests false. */
    def dropThrough(p: O => Boolean): Pull[F, Nothing, Option[Stream[F, O]]] =
      dropWhile_(p, true)

    /**
      * Drops elements of the this stream until the predicate `p` fails, and returns the new stream.
      * If defined, the first element of the returned stream will fail `p`.
      */
    def dropWhile(p: O => Boolean): Pull[F, Nothing, Option[Stream[F, O]]] =
      dropWhile_(p, false)

    private def dropWhile_(p: O => Boolean,
                           dropFailure: Boolean): Pull[F, Nothing, Option[Stream[F, O]]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.force.dropWhile(p, dropFailure) match {
            case Left(_)    => tl.pull.dropWhile_(p, dropFailure)
            case Right(tl2) => Pull.pure(Some(tl.cons(tl2)))
          }
      }

    /** Writes all inputs to the output of the returned `Pull`. */
    def echo: Pull[F, O, Unit] = Pull.fromFreeC(self.get)

    /** Reads a single element from the input and emits it to the output. Returns the new `Handle`. */
    def echo1: Pull[F, O, Option[Stream[F, O]]] =
      uncons1.flatMap {
        case None           => Pull.pure(None);
        case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
      }

    /** Reads the next available segment from the input and emits it to the output. Returns the new `Handle`. */
    def echoSegment: Pull[F, O, Option[Stream[F, O]]] =
      uncons.flatMap {
        case None           => Pull.pure(None);
        case Some((hd, tl)) => Pull.output(hd).as(Some(tl))
      }

    /** Like `[[unconsN]]`, but leaves the buffered input unconsumed. */
    def fetchN(n: Int): Pull[F, Nothing, Option[Stream[F, O]]] =
      unconsN(n).map { _.map { case (hd, tl) => tl.cons(hd) } }

    /** Awaits the next available element where the predicate returns true. */
    def find(f: O => Boolean): Pull[F, Nothing, Option[(O, Stream[F, O])]] =
      unconsChunk.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.indexWhere(f) match {
            case None => tl.pull.find(f)
            case Some(idx) if idx + 1 < hd.size =>
              Pull.pure(
                Some(
                  (hd(idx),
                   Segment
                     .chunk(hd)
                     .force
                     .drop(idx + 1)
                     .fold(_ => tl, hd => tl.cons(hd)))))
            case Some(idx) => Pull.pure(Some((hd(idx), tl)))
          }
      }

    /**
      * Folds all inputs using an initial value `z` and supplied binary operator, and writes the final
      * result to the output of the supplied `Pull` when the stream has no more values.
      */
    def fold[O2](z: O2)(f: (O2, O) => O2): Pull[F, Nothing, O2] =
      uncons.flatMap {
        case None => Pull.pure(z)
        case Some((hd, tl)) =>
          Pull.segment(hd.fold(z)(f).mapResult(_._2)).flatMap { z =>
            tl.pull.fold(z)(f)
          }
      }

    /**
      * Folds all inputs using the supplied binary operator, and writes the final result to the output of
      * the supplied `Pull` when the stream has no more values.
      */
    def fold1[O2 >: O](f: (O2, O2) => O2): Pull[F, Nothing, Option[O2]] =
      uncons1.flatMap {
        case None           => Pull.pure(None)
        case Some((hd, tl)) => tl.pull.fold(hd: O2)(f).map(Some(_))
      }

    /** Writes a single `true` value if all input matches the predicate, `false` otherwise. */
    def forall(p: O => Boolean): Pull[F, Nothing, Boolean] =
      uncons.flatMap {
        case None => Pull.pure(true)
        case Some((hd, tl)) =>
          Pull.segment(hd.takeWhile(p).drain).flatMap {
            case Left(()) => tl.pull.forall(p)
            case Right(_) => Pull.pure(false)
          }
      }

    /** Returns the last element of the input, if non-empty. */
    def last: Pull[F, Nothing, Option[O]] = {
      def go(prev: Option[O], s: Stream[F, O]): Pull[F, Nothing, Option[O]] =
        s.pull.uncons.flatMap {
          case None => Pull.pure(prev)
          case Some((hd, tl)) =>
            Pull
              .segment(hd.fold(prev)((_, o) => Some(o)).mapResult(_._2))
              .flatMap(go(_, tl))
        }
      go(None, self)
    }

    /** Like [[uncons]] but does not consume the segment (i.e., the segment is pushed back). */
    def peek: Pull[F, Nothing, Option[(Segment[O, Unit], Stream[F, O])]] =
      uncons.flatMap {
        case None           => Pull.pure(None);
        case Some((hd, tl)) => Pull.pure(Some((hd, tl.cons(hd))))
      }

    /** Like [[uncons1]] but does not consume the element (i.e., the element is pushed back). */
    def peek1: Pull[F, Nothing, Option[(O, Stream[F, O])]] =
      uncons1.flatMap {
        case None           => Pull.pure(None);
        case Some((hd, tl)) => Pull.pure(Some((hd, tl.cons1(hd))))
      }

    /**
      * Like `scan` but `f` is applied to each segment of the source stream.
      * The resulting segment is emitted and the result of the segment is used in the
      * next invocation of `f`. The final state value is returned as the result of the pull.
      */
    def scanSegments[S, O2](init: S)(f: (S, Segment[O, Unit]) => Segment[O2, S]): Pull[F, O2, S] =
      scanSegmentsOpt(init)(s => Some(seg => f(s, seg)))

    /**
      * More general version of `scanSegments` where the current state (i.e., `S`) can be inspected
      * to determine if another segment should be pulled or if the pull should terminate.
      * Termination is signaled by returning `None` from `f`. Otherwise, a function which consumes
      * the next segment is returned wrapped in `Some`. The final state value is returned as the
      * result of the pull.
      */
    def scanSegmentsOpt[S, O2](init: S)(
        f: S => Option[Segment[O, Unit] => Segment[O2, S]]): Pull[F, O2, S] = {
      def go(acc: S, s: Stream[F, O]): Pull[F, O2, S] =
        f(acc) match {
          case None => Pull.pure(acc)
          case Some(g) =>
            s.pull.uncons.flatMap {
              case Some((hd, tl)) =>
                Pull.segment(g(hd)).flatMap { acc =>
                  go(acc, tl)
                }
              case None =>
                Pull.pure(acc)
            }
        }
      go(init, self)
    }

    /**
      * Like `uncons`, but instead of performing normal `uncons`, this will
      * run the stream up to the first segment available.
      * Useful when zipping multiple streams (legs) into one stream.
      * Assures that scopes are correctly held for each stream `leg`
      * indepentently of scopes from other legs.
      *
      * If you are not pulling from mulitple streams, consider using `uncons`.
      */
    def stepLeg: Pull[F, Nothing, Option[StepLeg[F, O]]] =
      Pull
        .fromFreeC(Algebra.getScope[F, Nothing, O])
        .flatMap { scope =>
          new StepLeg[F, O](Segment.empty, scope.id, self.get).stepLeg
        }

    /** Emits the first `n` elements of the input. */
    def take(n: Long): Pull[F, O, Option[Stream[F, O]]] =
      if (n <= 0) Pull.pure(None)
      else
        uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            Pull.segment(hd.take(n)).flatMap {
              case Left((_, rem)) =>
                if (rem > 0) tl.pull.take(rem) else Pull.pure(None)
              case Right(tl2) => Pull.pure(Some(tl.cons(tl2)))
            }
        }

    /** Emits the last `n` elements of the input. */
    def takeRight(n: Long): Pull[F, Nothing, Chunk[O]] = {
      def go(acc: Vector[O], s: Stream[F, O]): Pull[F, Nothing, Chunk[O]] =
        s.pull.unconsN(n, true).flatMap {
          case None => Pull.pure(Chunk.vector(acc))
          case Some((hd, tl)) =>
            val vector = hd.force.toVector
            go(acc.drop(vector.length) ++ vector, tl)
        }
      if (n <= 0) Pull.pure(Chunk.empty)
      else go(Vector.empty, self)
    }

    /** Like [[takeWhile]], but emits the first value which tests false. */
    def takeThrough(p: O => Boolean): Pull[F, O, Option[Stream[F, O]]] =
      takeWhile_(p, true)

    /**
      * Emits the elements of the stream until the predicate `p` fails,
      * and returns the remaining `Stream`. If non-empty, the returned stream will have
      * a first element `i` for which `p(i)` is `false`.
      */
    def takeWhile(p: O => Boolean, takeFailure: Boolean = false): Pull[F, O, Option[Stream[F, O]]] =
      takeWhile_(p, takeFailure)

    private def takeWhile_(p: O => Boolean,
                           takeFailure: Boolean): Pull[F, O, Option[Stream[F, O]]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          Pull.segment(hd.takeWhile(p, takeFailure)).flatMap {
            case Left(_)    => tl.pull.takeWhile_(p, takeFailure)
            case Right(tl2) => Pull.pure(Some(tl.cons(tl2)))
          }
      }
  }

  /** Projection of a `Stream` providing various ways to compile a `Stream[F,O]` to an `F[...]`. */
  final class ToEffect[F[_], O] private[Stream] (
      private val free: FreeC[Algebra[Nothing, Nothing, ?], Unit])
      extends AnyVal {

    import scala.collection.generic.CanBuildFrom

    private def self: Stream[F, O] =
      Stream.fromFreeC(free.asInstanceOf[FreeC[Algebra[F, O, ?], Unit]])

    /**
      * Compiles this stream in to a value of the target effect type `F` and
      * discards any output values of the stream.
      *
      * To access the output values of the stream, use one of the other compilation methods --
      * e.g., [[fold]], [[toVector]], etc.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      */
    def drain(implicit F: Sync[F]): F[Unit] = fold(())((u, o) => u)

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output values together, starting with the provided `init` and combining the
      * current value with each output value.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      */
    def fold[B](init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
      Algebra.compile(self.get, init)(f)

    /**
      * Like [[fold]] but uses the implicitly available `Monoid[O]` to combine elements.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldMonoid.unsafeRunSync
      * res0: Int = 15
      * }}}
      */
    def foldMonoid(implicit F: Sync[F], O: Monoid[O]): F[O] =
      fold(O.empty)(O.combine)

    /**
      * Like [[fold]] but uses the implicitly available `Semigroup[O]` to combine elements.
      * If the stream emits no elements, `None` is returned.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldSemigroup.unsafeRunSync
      * res0: Option[Int] = Some(15)
      * scala> Stream.empty.covaryAll[IO,Int].compile.foldSemigroup.unsafeRunSync
      * res1: Option[Int] = None
      * }}}
      */
    def foldSemigroup(implicit F: Sync[F], O: Semigroup[O]): F[Option[O]] =
      fold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * returning `None` if the stream emitted no values and returning the
      * last value emitted wrapped in `Some` if values were emitted.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.last.unsafeRunSync
      * res0: Option[Int] = Some(4)
      * }}}
      */
    def last(implicit F: Sync[F]): F[Option[O]] =
      fold(Option.empty[O])((_, a) => Some(a))

    /**
      * Compiles this stream into a value of the target effect type `F` by logging
      * the output values to a `C`, given a `CanBuildFrom`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.to[List].unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def to[C[_]](implicit F: Sync[F], cbf: CanBuildFrom[Nothing, O, C[O]]): F[C[O]] =
      F.suspend(F.map(fold(cbf())(_ += _))(_.result))

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `List`. Equivalent to `to[List]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toList.unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def toList(implicit F: Sync[F]): F[List[O]] =
      to[List]

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Vector`. Equivalent to `to[Vector]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
      * }}}
      */
    def toVector(implicit F: Sync[F]): F[Vector[O]] =
      to[Vector]

  }

  /**
    * When merging multiple streams, this represents step of one leg.
    *
    * It is common to `uncons`, however unlike `uncons`, it keeps track
    * of stream scope independently of the main scope of the stream.
    *
    * This assures, that after each next `stepLeg` each Stream `leg` keeps its scope
    * when interpreting.
    *
    * Usual scenarios is to first invoke `stream.pull.stepLeg` and then consume whatever is
    * available in `leg.head`. If the next step is required `leg.stepLeg` will yield next `Leg`.
    *
    * Once the stream will stop to be interleaved (merged), then `stream` allows to return to normal stream
    * invocation.
    *
    */
  final class StepLeg[F[_], O](
      val head: Segment[O, Unit],
      private[fs2] val scopeId: Token,
      private[fs2] val next: FreeC[Algebra[F, O, ?], Unit]
  ) { self =>

    /**
      * Converts this leg back to regular stream. Scope is updated to the scope associated with this leg.
      * Note that when this is invoked, no more interleaving legs are allowed, and this must be very last
      * leg remaining.
      *
      * Note that resulting stream won't contain the `head` of this leg.
      */
    def stream: Stream[F, O] =
      Pull
        .loop[F, O, StepLeg[F, O]] { leg =>
          Pull.output(leg.head).flatMap(_ => leg.stepLeg)
        }(self.setHead(Segment.empty))
        .stream

    /** Replaces head of this leg. Useful when the head was not fully consumed. */
    def setHead(nextHead: Segment[O, Unit]): StepLeg[F, O] =
      new StepLeg[F, O](nextHead, scopeId, next)

    /** Provides an `uncons`-like operation on this leg of the stream. */
    def stepLeg: Pull[F, Nothing, Option[StepLeg[F, O]]] =
      Pull.fromFreeC(Algebra.stepLeg(self))
  }

  /** Provides operations on effectful pipes for syntactic convenience. */
  implicit class PipeOps[F[_], I, O](private val self: Pipe[F, I, O]) extends AnyVal {

    /** Transforms the left input of the given `Pipe2` using a `Pipe`. */
    def attachL[I1, O2](p: Pipe2[F, O, I1, O2]): Pipe2[F, I, I1, O2] =
      (l, r) => p(self(l), r)

    /** Transforms the right input of the given `Pipe2` using a `Pipe`. */
    def attachR[I0, O2](p: Pipe2[F, I0, O, O2]): Pipe2[F, I0, I, O2] =
      (l, r) => p(l, self(r))
  }

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit final class PurePipeOps[I, O](private val self: Pipe[Pure, I, O]) extends AnyVal {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe[F, I, O] = self.asInstanceOf[Pipe[F, I, O]]
  }

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit final class PurePipe2Ops[I, I2, O](private val self: Pipe2[Pure, I, I2, O])
      extends AnyVal {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe2[F, I, I2, O] = self.asInstanceOf[Pipe2[F, I, I2, O]]
  }

  /** Implicitly covaries a stream. */
  implicit def covaryPure[F[_], O, O2 >: O](s: Stream[Pure, O]): Stream[F, O2] =
    s.covaryAll[F, O2]

  /** Implicitly covaries a pipe. */
  implicit def covaryPurePipe[F[_], I, O](p: Pipe[Pure, I, O]): Pipe[F, I, O] =
    p.covary[F]

  /** Implicitly covaries a `Pipe2`. */
  implicit def covaryPurePipe2[F[_], I, I2, O](p: Pipe2[Pure, I, I2, O]): Pipe2[F, I, I2, O] =
    p.covary[F]

  /**
    * `Sync` instance for `Stream`.
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> import cats.effect.Sync
    * scala> implicit def si: Sync[Stream[Pure, ?]] = Stream.syncInstance[Pure]
    * scala> Stream(1, -2, 3).fproduct(_.abs).toList
    * res0: List[(Int, Int)] = List((1,1), (-2,2), (3,3))
    * }}}
    */
  implicit def syncInstance[F[_]]: Sync[Stream[F, ?]] = new Sync[Stream[F, ?]] {
    def pure[A](a: A) = Stream(a)
    def handleErrorWith[A](s: Stream[F, A])(h: Throwable => Stream[F, A]) =
      s.handleErrorWith(h)
    def raiseError[A](t: Throwable) = Stream.raiseError(t)
    def flatMap[A, B](s: Stream[F, A])(f: A => Stream[F, B]) = s.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => Stream[F, Either[A, B]]) = f(a).flatMap {
      case Left(a)  => tailRecM(a)(f)
      case Right(b) => Stream(b)
    }
    def suspend[R](s: => Stream[F, R]) = Stream.suspend(s)
  }

  /** `Monoid` instance for `Stream`. */
  implicit def monoidInstance[F[_], O]: Monoid[Stream[F, O]] =
    new Monoid[Stream[F, O]] {
      def empty = Stream.empty
      def combine(x: Stream[F, O], y: Stream[F, O]) = x ++ y
    }
}
