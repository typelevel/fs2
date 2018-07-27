package fs2

import cats.{Applicative, Eq, Functor, Id, MonadError, Monoid, Semigroup, ~>}
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits.{catsSyntaxEither => _, _}
import fs2.internal.{Algebra, Canceled, FreeC, Token}
import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration._

/**
  * A stream producing output of type `O` and which may evaluate `F`
  * effects. If `F` is [[Pure]], the stream evaluates no effects.
  *
  * Laws (using infix syntax):
  *
  * `append` forms a monoid in conjunction with `empty`:
  *   - `empty append s == s` and `s append empty == s`.
  *   - `(s1 append s2) append s3 == s1 append (s2 append s3)`
  *
  * And `cons` is consistent with using `++` to prepend a single chunk:
  *   - `s.cons(c) == Stream.chunk(c) ++ s`
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
  *  where `Stream.emit(a)` is defined as `chunk(Chunk.singleton(a)) and
  *  `f >=> g` is defined as `a => a flatMap f flatMap g`
  *
  * The monad is the list-style sequencing monad:
  *   - `(a ++ b) flatMap f == (a flatMap f) ++ (b flatMap f)`
  *   - `Stream.empty flatMap f == Stream.empty`
  *
  * '''Technical notes'''
  *
  * ''Note:'' since the chunk structure of the stream is observable, and
  * `s flatMap Stream.emit` produces a stream of singleton chunks,
  * the right identity law uses a weaker notion of equality, `===` which
  * normalizes both sides with respect to chunk structure:
  *
  *   `(s1 === s2) = normalize(s1) == normalize(s2)`
  *   where `==` is full equality
  *   (`a == b` iff `f(a)` is identical to `f(b)` for all `f`)
  *
  * `normalize(s)` can be defined as `s.flatMap(Stream.emit)`, which just
  * produces a singly-chunked stream from any input stream `s`.
  *
  * ''Note:'' For efficiency `[[Stream.map]]` function operates on an entire
  * chunk at a time and preserves chunk structure, which differs from
  * the `map` derived from the monad (`s map f == s flatMap (f andThen Stream.emit)`)
  * which would produce singleton chunk. In particular, if `f` throws errors, the
  * chunked version will fail on the first ''chunk'' with an error, while
  * the unchunked version will fail on the first ''element'' with an error.
  * Exceptions in pure code like this are strongly discouraged.
  *
  * @hideImplicitConversion PureOps
  * @hideImplicitConversion IdOps
  */
final class Stream[+F[_], +O] private (private val free: FreeC[Algebra[Nothing, Nothing, ?], Unit])
    extends AnyVal {

  private[fs2] def get[F2[x] >: F[x], O2 >: O]: FreeC[Algebra[F2, O2, ?], Unit] =
    free.asInstanceOf[FreeC[Algebra[F2, O2, ?], Unit]]

  /** Appends `s2` to the end of this stream. */
  def ++[F2[x] >: F[x], O2 >: O](s2: => Stream[F2, O2]): Stream[F2, O2] = append(s2)

  /** Appends `s2` to the end of this stream. Alias for `s1 ++ s2`. */
  def append[F2[x] >: F[x], O2 >: O](s2: => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromFreeC(get[F2, O2].flatMap(_ => s2.get))

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

  /*
   * Retries on failure, returning a stream of attempts that can
   * be manipulated with standard stream operations such as `take`,
   * `collectFirst` and `interruptWhen`.
   *
   * Note: The resulting stream does *not* automatically halt at the
   * first successful attempt. Also see `retry`.
   */
  def attempts[F2[x] >: F[x]: Timer](
      delays: Stream[F2, FiniteDuration]): Stream[F2, Either[Throwable, O]] =
    attempt ++ delays.flatMap(delay => Stream.sleep_(delay) ++ attempt)

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
    def go(buffer: List[Chunk[O]], last: Boolean, s: Stream[F, O]): Pull[F, O, Unit] =
      s.pull.unconsChunk.flatMap {
        case Some((hd, tl)) =>
          val (out, buf, newLast) = {
            hd.foldLeft((Nil: List[Chunk[O]], Vector.empty[O], last)) {
              case ((out, buf, last), i) =>
                val cur = f(i)
                if (!cur && last)
                  (Chunk.vector(buf :+ i) :: out, Vector.empty, cur)
                else (out, buf :+ i, cur)
            }
          }
          if (out.isEmpty) {
            go(Chunk.vector(buf) :: buffer, newLast, tl)
          } else {
            val outBuffer =
              buffer.reverse.foldLeft(Pull.pure(()).covaryOutput[O])((acc, c) =>
                acc >> Pull.output(c))
            val outAll = out.reverse.foldLeft(outBuffer)((acc, c) => acc >> Pull.output(c))
            outAll >> go(List(Chunk.vector(buf)), newLast, tl)
          }
        case None =>
          buffer.reverse.foldLeft(Pull.pure(()).covaryOutput[O])((acc, c) => acc >> Pull.output(c))
      }
    go(Nil, false, this).stream
  }

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
  def changes[O2 >: O](implicit eq: Eq[O2]): Stream[F, O2] =
    filterWithPrevious(eq.neqv)

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
    this.repeatPull(_.uncons.flatMap {
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
        case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
      }
    }

  /**
    * Outputs chunks of size `n`.
    *
    * Chunks from the source stream are split as necessary.
    * If `allowFewer` is true, the last chunk that is emitted may have less than `n` elements.
    *
    * @example {{{
    * scala> Stream(1,2,3).repeat.chunkN(2).take(5).toList
    * res0: List[Chunk[Int]] = List(Chunk(1, 2), Chunk(3, 1), Chunk(2, 3), Chunk(1, 2), Chunk(3, 1))
    * }}}
    */
  def chunkN(n: Int, allowFewer: Boolean = true): Stream[F, Chunk[O]] =
    this.repeatPull {
      _.unconsN(n, allowFewer).flatMap {
        case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
        case None           => Pull.pure(None)
      }
    }

  /**
    * Filters and maps simultaneously. Calls `collect` on each chunk in the stream.
    *
    * @example {{{
    * scala> Stream(Some(1), Some(2), None, Some(3), None, Some(4)).collect { case Some(i) => i }.toList
    * res0: List[Int] = List(1, 2, 3, 4)
    * }}}
    */
  def collect[O2](pf: PartialFunction[O, O2]): Stream[F, O2] =
    mapChunks(_.collect(pf))

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
    * Gets a projection of this stream that allows converting it to an `F[..]` in a number of ways.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> val prg: IO[Vector[Int]] = Stream.eval(IO(1)).append(Stream(2,3,4)).compile.toVector
    * scala> prg.unsafeRunSync
    * res2: Vector[Int] = Vector(1, 2, 3, 4)
    * }}}
    */
  def compile[F2[x] >: F[x], O2 >: O]: Stream.ToEffect[F2, O2] =
    new Stream.ToEffect[F2, O2](free)

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
    * This method is also similar to `Stream(this,that).parJoin(2)` but terminates `that` upon termination of
    * `this`.
    *
    * @example {{{
    * scala> import cats.effect.IO, scala.concurrent.ExecutionContext.Implicits.global
    * scala> val data: Stream[IO,Int] = Stream.range(1, 10).covary[IO]
    * scala> Stream.eval(async.signalOf[IO,Int](0)).flatMap(s => Stream(s).concurrently(data.evalMap(s.set))).flatMap(_.discrete).takeWhile(_ < 9, true).compile.last.unsafeRunSync
    * res0: Option[Int] = Some(9)
    * }}}
    */
  def concurrently[F2[x] >: F[x], O2](that: Stream[F2, O2])(
      implicit F: Concurrent[F2]): Stream[F2, O] =
    Stream.eval(Deferred[F2, Unit]).flatMap { interruptR =>
      Stream.eval(Deferred[F2, Option[Throwable]]).flatMap { doneR =>
        Stream.eval(Deferred[F2, Throwable]).flatMap { interruptL =>
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
          Stream.eval(F.start(runR)) >>
            this
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
    * Prepends a chunk onto the front of this stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).cons(Chunk(-1, 0)).toList
    * res0: List[Int] = List(-1, 0, 1, 2, 3)
    * }}}
    */
  def cons[O2 >: O](c: Chunk[O2]): Stream[F, O2] =
    if (c.isEmpty) this else Stream.chunk(c) ++ this

  /**
    * Prepends a chunk onto the front of this stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).consChunk(Chunk.vector(Vector(-1, 0))).toList
    * res0: List[Int] = List(-1, 0, 1, 2, 3)
    * }}}
    */
  def consChunk[O2 >: O](c: Chunk[O2]): Stream[F, O2] =
    cons(c)

  /**
    * Prepends a single value onto the front of this stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).cons1(0).toList
    * res0: List[Int] = List(0, 1, 2, 3)
    * }}}
    */
  def cons1[O2 >: O](o: O2): Stream[F, O2] =
    cons(Chunk.singleton(o))

  /**
    * Lifts this stream to the specified effect and output types.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.empty.covaryAll[IO,Int]
    * res0: Stream[IO,Int] = Stream(..)
    * }}}
    */
  def covaryAll[F2[x] >: F[x], O2 >: O]: Stream[F2, O2] = this

  /**
    * Lifts this stream to the specified output type.
    *
    * @example {{{
    * scala> Stream(Some(1), Some(2), Some(3)).covaryOutput[Option[Int]]
    * res0: Stream[Pure,Option[Int]] = Stream(..)
    * }}}
    */
  def covaryOutput[O2 >: O]: Stream[F, O2] = this

  /**
    * Debounce the stream with a minimum period of `d` between each element.
    *
    * @example {{{
    * scala> import scala.concurrent.duration._, scala.concurrent.ExecutionContext.Implicits.global, cats.effect.IO
    * scala> val s = Stream(1, 2, 3) ++ Stream.sleep_[IO](500.millis) ++ Stream(4, 5) ++ Stream.sleep_[IO](10.millis) ++ Stream(6)
    * scala> val s2 = s.debounce(100.milliseconds)
    * scala> s2.compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(3, 6)
    * }}}
    */
  def debounce[F2[x] >: F[x]](d: FiniteDuration)(implicit F: Concurrent[F2],
                                                 timer: Timer[F2]): Stream[F2, O] = {
    val atemporal: Stream[F2, O] = chunks
      .flatMap { c =>
        val lastOpt = if (c.isEmpty) None else Some(c(c.size - 1))
        lastOpt.map(Pull.output1(_)).getOrElse(Pull.done).stream
      }

    Stream.eval(async.boundedQueue[F2, Option[O]](1)).flatMap { queue =>
      Stream.eval(Ref.of[F2, Option[O]](None)).flatMap { ref =>
        def enqueueLatest: F2[Unit] =
          ref.modify(s => None -> s).flatMap {
            case v @ Some(_) => queue.enqueue1(v)
            case None        => F.unit
          }

        val in: Stream[F2, Unit] = atemporal.evalMap { o =>
          ref.modify(s => o.some -> s).flatMap {
            case None    => F.start(timer.sleep(d) >> enqueueLatest).void
            case Some(_) => F.unit
          }
        } ++ Stream.eval_(enqueueLatest *> queue.enqueue1(None))

        val out: Stream[F2, O] = queue.dequeue.unNoneTerminate

        out.concurrently(in)
      }
    }
  }

  /**
    * Returns a stream that when run, sleeps for duration `d` and then pulls from this stream.
    *
    * Alias for `sleep_[F](d) ++ this`.
    */
  def delayBy[F2[x] >: F[x]: Timer](d: FiniteDuration): Stream[F2, O] =
    Stream.sleep_[F2](d) ++ this

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
  def drain: Stream[F, Nothing] = this.mapChunks(_ => Chunk.empty)

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
          if (hd.nonEmpty) Pull.output(last) >> go(hd, tl)
          else go(last, tl)
        case None =>
          val o = last(last.size - 1)
          if (p(o)) {
            val (prefix, _) = last.splitAt(last.size - 1)
            Pull.output(prefix)
          } else Pull.output(last)
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
      def go(acc: collection.immutable.Queue[O], s: Stream[F, O]): Pull[F, O, Option[Unit]] =
        s.pull.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            val all = acc ++ hd.toVector
            Pull.output(Chunk.seq(all.dropRight(n))) >> go(all.takeRight(n), tl)
        }
      go(collection.immutable.Queue.empty, this).stream
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
    * Like `[[merge]]`, but tags each output with the branch it came from.
    *
    * @example {{{
    * scala> import scala.concurrent.duration._, scala.concurrent.ExecutionContext.Implicits.global, cats.effect.IO
    * scala> val s1 = Stream.awakeEvery[IO](1000.millis).scan(0)((acc, i) => acc + 1)
    * scala> val s = s1.either(Stream.sleep_[IO](500.millis) ++ s1).take(10)
    * scala> s.take(10).compile.toVector.unsafeRunSync
    * res0: Vector[Either[Int,Int]] = Vector(Left(0), Right(0), Left(1), Right(1), Left(2), Right(2), Left(3), Right(3), Left(4), Right(4))
    * }}}
    */
  def either[F2[x] >: F[x]: Concurrent, O2](that: Stream[F2, O2]): Stream[F2, Either[O, O2]] =
    map(Left(_)).merge(that.map(Right(_)))

  /**
    * Alias for `flatMap(o => Stream.eval(f(o)))`.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream(1,2,3,4).evalMap(i => IO(println(i))).compile.drain.unsafeRunSync
    * res0: Unit = ()
    * }}}
    */
  def evalMap[F2[x] >: F[x], O2](f: O => F2[O2]): Stream[F2, O2] =
    flatMap(o => Stream.eval(f(o)))

  /**
    * Like `[[Stream#mapAccumulate]]`, but accepts a function returning an `F[_]`.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream(1,2,3,4).covary[IO].evalMapAccumulate(0)((acc,i) => IO((i, acc + i))).compile.toVector.unsafeRunSync
    * res0: Vector[(Int, Int)] = Vector((1,1), (2,3), (3,5), (4,7))
    * }}}
    */
  def evalMapAccumulate[F2[x] >: F[x], S, O2](s: S)(
      f: (S, O) => F2[(S, O2)]): Stream[F2, (S, O2)] = {
    def go(s: S, in: Stream[F2, O]): Pull[F2, (S, O2), Unit] =
      in.pull.uncons1.flatMap {
        case None => Pull.done
        case Some((hd, tl)) =>
          Pull.eval(f(s, hd)).flatMap {
            case (ns, o) =>
              Pull.output1((ns, o)) >> go(ns, tl)
          }
      }

    go(s, this).stream
  }

  /**
    * Like `[[Stream#scan]]`, but accepts a function returning an `F[_]`.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream(1,2,3,4).covary[IO].evalScan(0)((acc,i) => IO(acc + i)).compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 3, 6, 10)
    * }}}
    */
  def evalScan[F2[x] >: F[x], O2](z: O2)(f: (O2, O) => F2[O2]): Stream[F2, O2] = {
    def go(z: O2, s: Stream[F2, O]): Pull[F2, O2, Option[Stream[F2, O2]]] =
      s.pull.uncons1.flatMap {
        case Some((hd, tl)) =>
          Pull.eval(f(z, hd)).flatMap { o =>
            Pull.output1(o) >> go(o, tl)
          }
        case None => Pull.pure(None)
      }
    this.pull.uncons1.flatMap {
      case Some((hd, tl)) =>
        Pull.eval(f(z, hd)).flatMap { o =>
          Pull.output(Chunk.seq(List(z, o))) >> go(o, tl)
        }
      case None => Pull.output1(z) >> Pull.pure(None)
    }.stream
  }

  /**
    * Like `observe` but observes with a function `O => F[Unit]` instead of a sink.
    * Not as powerful as `observe` since not all sinks can be represented by `O => F[Unit]`, but much faster.
    * Alias for `evalMap(o => f(o).as(o))`.
    */
  def evalTap[F2[x] >: F[x]: Functor](f: O => F2[Unit]): Stream[F2, O] =
    evalMap(o => f(o).as(o))

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
  def filter(p: O => Boolean): Stream[F, O] = mapChunks(_.filter(p))

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
          val (allPass, newLast) = hd.foldLeft((true, last)) {
            case ((acc, last), o) => (acc && f(last, o), o)
          }
          if (allPass) {
            Pull.output(hd) >> go(newLast, tl)
          } else {
            val (acc, newLast) = hd.foldLeft((Vector.empty[O], last)) {
              case ((acc, last), o) =>
                if (f(last, o)) (acc :+ o, o)
                else (acc, last)
            }
            Pull.output(Chunk.vector(acc)) >> go(newLast, tl)
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
    * Creates a stream whose elements are generated by applying `f` to each output of
    * the source stream and concatenated all of the results.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).flatMap { i => Stream.chunk(Chunk.seq(List.fill(i)(i))) }.toList
    * res0: List[Int] = List(1, 2, 2, 3, 3, 3)
    * }}}
    */
  def flatMap[F2[x] >: F[x], O2](f: O => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromFreeC[F2, O2](Algebra.uncons(get[F2, O]).flatMap {
      case Some((hd, tl)) =>
        // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
        // check if hd has only a single element, and if so, process it directly instead of folding.
        // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
        val only: Option[O] = tl match {
          case FreeC.Pure(_) => hd.head
          case _             => None
        }
        only match {
          case None =>
            hd.map(f)
              .foldRightLazy(Stream.fromFreeC(tl).flatMap(f))(_ ++ _)
              .get

          case Some(o) =>
            f(o).get

        }
      case None => Stream.empty.get
    })

  /** Alias for `flatMap(_ => s2)`. */
  def >>[F2[x] >: F[x], O2](s2: => Stream[F2, O2]): Stream[F2, O2] =
    flatMap(_ => s2)

  /** Flattens a stream of streams in to a single stream by concatenating each stream.
    * See [[parJoin]] and [[parJoinUnbounded]] for concurrent flattening of 'n' streams.
    */
  def flatten[F2[x] >: F[x], O2](implicit ev: O <:< Stream[F2, O2]): Stream[F2, O2] =
    flatMap(i => ev(i))

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
    * Folds this stream with the monoid for `O`.
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> Stream(1, 2, 3, 4, 5).foldMonoid.toList
    * res0: List[Int] = List(15)
    * }}}
    */
  def foldMonoid[O2 >: O](implicit O: Monoid[O2]): Stream[F, O2] =
    fold(O.empty)(O.combine)

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
    * Partitions the input into a stream of chunks according to a discriminator function.
    *
    * Each chunk in the source stream is grouped using the supplied discriminator function
    * and the results of the grouping are emitted each time the discriminator function changes
    * values.
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> Stream("Hello", "Hi", "Greetings", "Hey").groupAdjacentBy(_.head).toList.map { case (k,vs) => k -> vs.toList }
    * res0: List[(Char,List[String])] = List((H,List(Hello, Hi)), (G,List(Greetings)), (H,List(Hey)))
    * }}}
    */
  def groupAdjacentBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, (O2, Chunk[O])] = {
    def go(current: Option[(O2, Chunk[O])], s: Stream[F, O]): Pull[F, (O2, Chunk[O]), Unit] =
      s.pull.unconsChunk.flatMap {
        case Some((hd, tl)) =>
          if (hd.nonEmpty) {
            val (k1, out) = current.getOrElse((f(hd(0)), Chunk.empty[O]))
            doChunk(hd, tl, k1, List(out), None)
          } else {
            go(current, tl)
          }
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
                out: List[Chunk[O]],
                acc: Option[Chunk[(O2, Chunk[O])]]): Pull[F, (O2, Chunk[O]), Unit] = {
      val differsAt = chunk.indexWhere(v => eq.neqv(f(v), k1)).getOrElse(-1)
      if (differsAt == -1) {
        // whole chunk matches the current key, add this chunk to the accumulated output
        val newOut: List[Chunk[O]] = chunk :: out
        acc match {
          case None      => go(Some((k1, Chunk.concat(newOut.reverse))), s)
          case Some(acc) =>
            // potentially outputs one additional chunk (by splitting the last one in two)
            Pull.output(acc) >> go(Some((k1, Chunk.concat(newOut.reverse))), s)
        }
      } else {
        // at least part of this chunk does not match the current key, need to group and retain chunkiness
        // split the chunk into the bit where the keys match and the bit where they don't
        val matching = chunk.take(differsAt)
        val newOut: List[Chunk[O]] = matching :: out
        val nonMatching = chunk.drop(differsAt)
        // nonMatching is guaranteed to be non-empty here, because we know the last element of the chunk doesn't have
        // the same key as the first
        val k2 = f(nonMatching(0))
        doChunk(nonMatching,
                s,
                k2,
                Nil,
                Some(Chunk.concat(acc.toList ::: List(Chunk((k1, Chunk.concat(newOut.reverse)))))))
      }
    }

    go(None, this).stream
  }

  /**
    * If `this` terminates with `Stream.raiseError(e)`, invoke `h(e)`.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).append(Stream.raiseError(new RuntimeException)).handleErrorWith(t => Stream(0)).toList
    * res0: List[Int] = List(1, 2, 3, 0)
    * }}}
    */
  def handleErrorWith[F2[x] >: F[x], O2 >: O](h: Throwable => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromFreeC(Algebra.scope(get[F2, O2]).handleErrorWith(e => h(e).get[F2, O2]))

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
    * Determinsitically interleaves elements, starting on the left, terminating when the end of either branch is reached naturally.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).interleave(Stream(4, 5, 6, 7)).toList
    * res0: List[Int] = List(1, 4, 2, 5, 3, 6)
    * }}}
    */
  def interleave[F2[x] >: F[x], O2 >: O](that: Stream[F2, O2]): Stream[F2, O2] =
    zip(that).flatMap { case (o1, o2) => Stream(o1, o2) }

  /**
    * Determinsitically interleaves elements, starting on the left, terminating when the ends of both branches are reached naturally.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).interleaveAll(Stream(4, 5, 6, 7)).toList
    * res0: List[Int] = List(1, 4, 2, 5, 3, 6, 7)
    * }}}
    */
  def interleaveAll[F2[x] >: F[x], O2 >: O](that: Stream[F2, O2]): Stream[F2, O2] =
    map(Some(_): Option[O2])
      .zipAll(that.map(Some(_): Option[O2]))(None, None)
      .flatMap {
        case (o1Opt, o2Opt) =>
          Stream(o1Opt.toSeq: _*) ++ Stream(o2Opt.toSeq: _*)
      }

  /**
    * Let through the `s2` branch as long as the `s1` branch is `false`,
    * listening asynchronously for the left branch to become `true`.
    * This halts as soon as either branch halts.
    *
    * Consider using the overload that takes a `Signal`.
    *
    * Caution: interruption is checked as elements are pulled from the returned stream. As a result,
    * streams which stop pulling from the returned stream end up uninterruptible. For example,
    * `s.interruptWhen(s2).flatMap(_ => infiniteStream)` will not be interrupted when `s2` is true
    * because `s1.interruptWhen(s2)` is never pulled for another element after the first element has been
    * emitted. To fix, consider `s.flatMap(_ => infiniteStream).interruptWhen(s2)`.
    */
  def interruptWhen[F2[x] >: F[x]](haltWhenTrue: Stream[F2, Boolean])(
      implicit F2: Concurrent[F2]): Stream[F2, O] =
    Stream.eval(Deferred[F2, Either[Throwable, Unit]]).flatMap { interruptL =>
      Stream.eval(Deferred[F2, Unit]).flatMap { doneR =>
        Stream.eval(Deferred[F2, Unit]).flatMap { interruptR =>
          def runR =
            F2.guaranteeCase(
              haltWhenTrue
                .evalMap {
                  case false => F2.pure(false)
                  case true  => interruptL.complete(Right(())).as(true)
                }
                .takeWhile(!_)
                .interruptWhen(interruptR.get.attempt)
                .compile
                .drain) { c =>
              val r = c match {
                case ExitCase.Completed => Right(())
                case ExitCase.Error(t)  => Left(t)
                case ExitCase.Canceled  => Left(Canceled)
              }
              interruptL.complete(r).attempt *> doneR.complete(())
            }

          Stream.eval(F2.start(runR)) >>
            interruptWhen(interruptL.get).onFinalize(interruptR.complete(()) *> doneR.get)

        }
      }
    }

  /** Alias for `interruptWhen(haltWhenTrue.discrete)`. */
  def interruptWhen[F2[x] >: F[x]: Concurrent](
      haltWhenTrue: async.immutable.Signal[F2, Boolean]): Stream[F2, O] =
    interruptWhen(haltWhenTrue.discrete)

  /**
    * Interrupts the stream, when `haltOnSignal` finishes its evaluation.
    */
  def interruptWhen[F2[x] >: F[x]](haltOnSignal: F2[Either[Throwable, Unit]])(
      implicit F2: Concurrent[F2]): Stream[F2, O] =
    Stream
      .getScope[F2]
      .flatMap { scope =>
        Stream.eval(F2.start(haltOnSignal.flatMap(scope.interrupt))).flatMap(_ => this)
      }
      .interruptScope

  /**
    * Creates a scope that may be interrupted by calling scope#interrupt.
    */
  def interruptScope[F2[x] >: F[x]: Concurrent]: Stream[F2, O] =
    Stream.fromFreeC(Algebra.interruptScope(get[F2, O]))

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
                  hd.foreach { o =>
                    bldr += separator
                    bldr += o
                  }
                  Chunk.vector(bldr.result)
                }
                Pull.output(interspersed) >> Pull.pure(Some(tl))
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
    * Applies the specified pure function to each input and emits the result.
    *
    * @example {{{
    * scala> Stream("Hello", "World!").map(_.size).toList
    * res0: List[Int] = List(5, 6)
    * }}}
    */
  def map[O2](f: O => O2): Stream[F, O2] =
    this.pull.echo.mapOutput(f).streamNoScope

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
    this.scanChunks(init)((acc, c) => c.mapAccumulate(acc)(f2))
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
    * scala> Stream(1,2,3,4).covary[IO].mapAsync(2)(i => IO(println(i))).compile.drain.unsafeRunSync
    * res0: Unit = ()
    * }}}
    */
  def mapAsync[F2[x] >: F[x]: Concurrent, O2](parallelism: Int)(f: O => F2[O2]): Stream[F2, O2] =
    Stream
      .eval(async.mutable.Queue.bounded[F2, Option[F2[Either[Throwable, O2]]]](parallelism))
      .flatMap { queue =>
        queue.dequeue.unNoneTerminate
          .evalMap(identity)
          .rethrow
          .concurrently {
            evalMap { o =>
              Deferred[F2, Either[Throwable, O2]].flatMap { value =>
                queue.enqueue1(Some(value.get)).as {
                  Stream.eval(f(o).attempt).evalMap(value.complete)
                }
              }
            }.parJoin(parallelism)
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
    * scala> Stream(1,2,3,4).covary[IO].mapAsyncUnordered(2)(i => IO(println(i))).compile.drain.unsafeRunSync
    * res0: Unit = ()
    * }}}
    */
  def mapAsyncUnordered[F2[x] >: F[x]: Concurrent, O2](parallelism: Int)(
      f: O => F2[O2]): Stream[F2, O2] =
    map(o => Stream.eval(f(o))).parJoin(parallelism)

  /**
    * Applies the specified pure function to each chunk in this stream.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).append(Stream(4, 5, 6)).mapChunks { c => val ints = c.toInts; for (i <- 0 until ints.values.size) ints.values(i) = 0; ints }.toList
    * res0: List[Int] = List(0, 0, 0, 0, 0, 0)
    * }}}
    */
  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream[F, O2] =
    this.repeatPull {
      _.unconsChunk.flatMap {
        case None           => Pull.pure(None)
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
    * scala> val s1 = Stream.awakeEvery[IO](500.millis).scan(0)((acc, i) => acc + 1)
    * scala> val s = s1.merge(Stream.sleep_[IO](250.millis) ++ s1)
    * scala> s.take(6).compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 0, 1, 1, 2, 2)
    * }}}
    */
  def merge[F2[x] >: F[x], O2 >: O](that: Stream[F2, O2])(
      implicit F2: Concurrent[F2]): Stream[F2, O2] =
    Stream.eval(Semaphore(0)).flatMap { doneSem =>
      Stream.eval(Deferred[F2, Unit]).flatMap { interruptL =>
        Stream.eval(Deferred[F2, Unit]).flatMap { interruptR =>
          Stream.eval(Deferred[F2, Throwable]).flatMap { interruptY =>
            Stream
              .eval(async.unboundedQueue[F2, Option[(F2[Unit], Chunk[O2])]])
              .flatMap { outQ => // note that the queue actually contains up to 2 max elements thanks to semaphores guarding each side.
                def runUpstream(s: Stream[F2, O2], interrupt: Deferred[F2, Unit]): F2[Unit] =
                  Semaphore(1).flatMap { guard =>
                    s.chunks
                      .interruptWhen(interrupt.get.attempt)
                      .evalMap { chunk =>
                        guard.acquire >>
                          outQ.enqueue1(Some((guard.release, chunk)))
                      }
                      .compile
                      .drain
                      .attempt
                      .flatMap {
                        case Right(_) =>
                          doneSem.release >>
                            doneSem.acquireN(2) >>
                            F2.start(outQ.enqueue1(None)).void
                        case Left(err) =>
                          interruptY.complete(err) >>
                            doneSem.release
                      }
                  }

                Stream.eval(F2.start(runUpstream(this, interruptL))) >>
                  Stream.eval(F2.start(runUpstream(that, interruptR))) >>
                  outQ.dequeue.unNoneTerminate
                    .flatMap {
                      case (signal, chunk) =>
                        Stream.eval(signal) >>
                          Stream.chunk(chunk)
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
  def mergeHaltBoth[F2[x] >: F[x]: Concurrent, O2 >: O](that: Stream[F2, O2]): Stream[F2, O2] =
    noneTerminate.merge(that.noneTerminate).unNoneTerminate

  /** Like `merge`, but halts as soon as the `s1` branch halts. */
  def mergeHaltL[F2[x] >: F[x]: Concurrent, O2 >: O](that: Stream[F2, O2]): Stream[F2, O2] =
    noneTerminate.merge(that.map(Some(_))).unNoneTerminate

  /** Like `merge`, but halts as soon as the `s2` branch halts. */
  def mergeHaltR[F2[x] >: F[x]: Concurrent, O2 >: O](that: Stream[F2, O2]): Stream[F2, O2] =
    that.mergeHaltL(this)

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
    * Run `s2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`.
    *
    * Note: this should *not* be used for resource cleanup! Use `bracket` or `onFinalize` instead.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).onComplete(Stream(4, 5)).toList
    * res0: List[Int] = List(1, 2, 3, 4, 5)
    * }}}
    */
  def onComplete[F2[x] >: F[x], O2 >: O](s2: => Stream[F2, O2]): Stream[F2, O2] =
    handleErrorWith(e => s2 ++ Stream.raiseError(e)) ++ s2

  /**
    * Run the supplied effectful action at the end of this stream, regardless of how the stream terminates.
    */
  def onFinalize[F2[x] >: F[x]](f: F2[Unit])(implicit F2: Applicative[F2]): Stream[F2, O] =
    Stream.bracket(F2.unit)(_ => f) >> this

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
  def parJoin[F2[_], O2](maxOpen: Int)(implicit ev: O <:< Stream[F2, O2],
                                       ev2: F[_] <:< F2[_],
                                       F2: Concurrent[F2]): Stream[F2, O2] = {
    assert(maxOpen > 0, "maxOpen must be > 0, was: " + maxOpen)
    val _ = (ev, ev2)
    val outer = this.asInstanceOf[Stream[F2, Stream[F2, O2]]]
    Stream.eval(async.signalOf(None: Option[Option[Throwable]])).flatMap { done =>
      Stream.eval(Semaphore(maxOpen)).flatMap { available =>
        Stream
          .eval(async.signalOf(1l))
          .flatMap { running => // starts with 1 because outer stream is running by default
            Stream
              .eval(async.mutable.Queue
                .synchronousNoneTerminated[F2, Chunk[O2]])
              .flatMap { outputQ => // sync queue assures we won't overload heap when resulting stream is not able to catchup with inner streams
                // stops the join evaluation
                // all the streams will be terminated. If err is supplied, that will get attached to any error currently present
                def stop(rslt: Option[Throwable]): F2[Unit] =
                  done.update {
                    case rslt0 @ Some(Some(err0)) =>
                      rslt.fold[Option[Option[Throwable]]](rslt0) { err =>
                        Some(Some(new CompositeFailure(err0, NonEmptyList.of(err))))
                      }
                    case _ => Some(rslt)
                  } *> outputQ.enqueue1(None)

                val incrementRunning: F2[Unit] = running.update(_ + 1)
                val decrementRunning: F2[Unit] =
                  running.modify { n =>
                    val now = n - 1
                    now -> (if (now == 0) stop(None) else F2.unit)
                  }.flatten

                // runs inner stream
                // each stream is forked.
                // terminates when killSignal is true
                // if fails will enq in queue failure
                // note that supplied scope's resources must be leased before the inner stream forks the execution to another thread
                // and that it must be released once the inner stream terminates or fails.
                def runInner(inner: Stream[F2, O2], outerScope: Scope[F2]): F2[Unit] =
                  outerScope.lease.flatMap {
                    case Some(lease) =>
                      available.acquire *>
                        incrementRunning *>
                        F2.start {
                          F2.guaranteeCase(
                            inner.chunks
                              .evalMap { s =>
                                outputQ.enqueue1(Some(s))
                              }
                              .interruptWhen(done.map(_.nonEmpty))
                              . // must be AFTER enqueue to the sync queue, otherwise the process may hang to enq last item while being interrupted
                              compile
                              .drain) { c =>
                            val r = c match {
                              case ExitCase.Completed => F2.unit
                              case ExitCase.Error(t)  => stop(Some(t))
                              case ExitCase.Canceled  => stop(Some(Canceled))
                            }
                            r *>
                              lease.cancel *> //todo: propagate failure here on exception ???
                              available.release *>
                              decrementRunning
                          }
                        }.void

                    case None =>
                      F2.raiseError(
                        new Throwable("Outer scope is closed during inner stream startup"))
                  }

                // runs the outer stream, interrupts when kill == true, and then decrements the `running`
                def runOuter: F2[Unit] =
                  F2.guaranteeCase(
                    outer
                      .flatMap { inner =>
                        Stream.getScope[F2].evalMap { outerScope =>
                          runInner(inner, outerScope)
                        }
                      }
                      .interruptWhen(done.map(_.nonEmpty))
                      .compile
                      .drain) { c =>
                    val r = c match {
                      case ExitCase.Completed => F2.unit
                      case ExitCase.Error(t)  => stop(Some(t))
                      case ExitCase.Canceled  => stop(Some(Canceled))
                    }
                    r *> decrementRunning
                  }

                // awaits when all streams (outer + inner) finished,
                // and then collects result of the stream (outer + inner) execution
                def signalResult: Stream[F2, O2] =
                  done.discrete.take(1).flatMap {
                    _.flatten
                      .fold[Stream[Pure, O2]](Stream.empty)(Stream.raiseError)
                  }

                Stream.eval(F2.start(runOuter)) >>
                  outputQ.dequeue.unNoneTerminate
                    .flatMap(Stream.chunk(_).covary[F2])
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

  /** Like [[parJoin]] but races all inner streams simultaneously. */
  def parJoinUnbounded[F2[_], O2](implicit ev: O <:< Stream[F2, O2],
                                  ev2: F[_] <:< F2[_],
                                  F2: Concurrent[F2]): Stream[F2, O2] =
    parJoin(Int.MaxValue)

  /** Like `interrupt` but resumes the stream when left branch goes to true. */
  def pauseWhen[F2[x] >: F[x]](pauseWhenTrue: Stream[F2, Boolean])(
      implicit F2: Concurrent[F2]): Stream[F2, O] =
    async.hold[F2, Option[Boolean]](Some(false), pauseWhenTrue.noneTerminate).flatMap {
      pauseSignal =>
        def pauseIfNeeded: F2[Unit] =
          pauseSignal.get.flatMap {
            case Some(false) => F2.pure(())
            case _           => pauseSignal.discrete.dropWhile(_.getOrElse(true)).take(1).compile.drain
          }

        chunks
          .flatMap { chunk =>
            Stream.eval(pauseIfNeeded) >>
              Stream.chunk(chunk)
          }
          .interruptWhen(pauseSignal.discrete.map(_.isEmpty))
    }

  /** Alias for `pauseWhen(pauseWhenTrue.discrete)`. */
  def pauseWhen[F2[x] >: F[x]: Concurrent](
      pauseWhenTrue: async.immutable.Signal[F2, Boolean]): Stream[F2, O] =
    pauseWhen(pauseWhenTrue.discrete)

  /** Alias for `prefetchN(1)`. */
  def prefetch[F2[x] >: F[x]: Concurrent]: Stream[F2, O] = prefetchN[F2](1)

  /**
    * Behaves like `identity`, but starts fetches up to `n` chunks in parallel with downstream
    * consumption, enabling processing on either side of the `prefetchN` to run in parallel.
    */
  def prefetchN[F2[x] >: F[x]: Concurrent](n: Int): Stream[F2, O] =
    Stream.eval(async.boundedQueue[F2, Option[Chunk[O]]](n)).flatMap { queue =>
      queue.dequeue.unNoneTerminate
        .flatMap(Stream.chunk(_))
        .concurrently(chunks.noneTerminate.covary[F2].to(queue.enqueue))
    }

  /** Alias for [[fold1]]. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F, O2] = fold1(f)

  /**
    * Reduces this stream with the Semigroup for `O`.
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> Stream("The", "quick", "brown", "fox").intersperse(" ").reduceSemigroup.toList
    * res0: List[String] = List(The quick brown fox)
    * }}}
    */
  def reduceSemigroup[O2 >: O](implicit S: Semigroup[O2]): Stream[F, O2] =
    reduce[O2](S.combine(_, _))

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
  def repartition[O2 >: O](f: O2 => Chunk[O2])(implicit S: Semigroup[O2]): Stream[F, O2] =
    this.pull
      .scanChunks(Option.empty[O2]) { (carry, chunk) =>
        val (out, (_, c2)) = chunk
          .scanLeftCarry((Chunk.empty[O2], carry)) {
            case ((_, carry), o) =>
              val o2: O2 = carry.fold(o: O2)(S.combine(_, o))
              val partitions: Chunk[O2] = f(o2)
              if (partitions.isEmpty) partitions -> None
              else if (partitions.size == 1) Chunk.empty -> partitions.last
              else
                partitions.take(partitions.size - 1) -> partitions.last
          }
        (c2, out.flatMap { case (o, _) => o })
      }
      .flatMap {
        case Some(carry) => Pull.output1(carry)
        case None        => Pull.done
      }
      .stream

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
    this.asInstanceOf[Stream[F, Either[Throwable, O2]]].chunks.flatMap { c =>
      val firstError = c.find(_.isLeft)
      firstError match {
        case None    => Stream.chunk(c.collect { case Right(i) => i })
        case Some(h) => Stream.raiseError(h.swap.toOption.get)
      }
    }
  }

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
        val (out, carry) = hd.scanLeftCarry(z)(f)
        Pull.output(out) >> tl.scan_(carry)(f)
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
    * Like `scan` but `f` is applied to each chunk of the source stream.
    * The resulting chunk is emitted and the result of the chunk is used in the
    * next invocation of `f`.
    *
    * Many stateful pipes can be implemented efficiently (i.e., supporting fusion) with this method.
    */
  def scanChunks[S, O2 >: O, O3](init: S)(f: (S, Chunk[O2]) => (S, Chunk[O3])): Stream[F, O3] =
    scanChunksOpt(init)(s => Some(c => f(s, c)))

  /**
    * More general version of `scanChunks` where the current state (i.e., `S`) can be inspected
    * to determine if another chunk should be pulled or if the stream should terminate.
    * Termination is signaled by returning `None` from `f`. Otherwise, a function which consumes
    * the next chunk is returned wrapped in `Some`.
    *
    * @example {{{
    * scala> def take[F[_],O](s: Stream[F,O], n: Int): Stream[F,O] =
    *      |   s.scanChunksOpt(n) { n => if (n <= 0) None else Some(c => if (c.size < n) (n - c.size, c) else (0, c.take(n))) }
    * scala> take(Stream.range(0,100), 5).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def scanChunksOpt[S, O2 >: O, O3](init: S)(
      f: S => Option[Chunk[O2] => (S, Chunk[O3])]): Stream[F, O3] =
    this.pull.scanChunksOpt(init)(f).stream

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
          val (out, carry) = hd.scanLeftCarry(window)((w, i) => w.dequeue._2.enqueue(i))
          Pull.output(out) >> go(carry, tl)
      }
    this.pull
      .unconsN(n, true)
      .flatMap {
        case None => Pull.done
        case Some((hd, tl)) =>
          val window = hd.foldLeft(collection.immutable.Queue.empty[O])(_.enqueue(_))
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
    * res0: List[Chunk[Int]] = List(Chunk(), Chunk(1, 2, 3), Chunk(5, 6, 7), Chunk(9))
    * }}}
    */
  def split(f: O => Boolean): Stream[F, Chunk[O]] = {
    def go(buffer: List[Chunk[O]], s: Stream[F, O]): Pull[F, Chunk[O], Unit] =
      s.pull.uncons.flatMap {
        case Some((hd, tl)) =>
          hd.indexWhere(f) match {
            case None => go(hd :: buffer, tl)
            case Some(idx) =>
              val pfx = hd.take(idx)
              val b2 = pfx :: buffer
              Pull.output1(Chunk.concat(b2.reverse)) >> go(Nil, tl.cons(hd.drop(idx + 1)))
          }
        case None =>
          if (buffer.nonEmpty) Pull.output1(Chunk.concat(buffer.reverse))
          else Pull.done
      }
    go(Nil, this).stream
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
  def takeRight(n: Int): Stream[F, O] =
    this.pull.takeRight(n).flatMap(c => Pull.output(c)).stream

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
    * Transforms this stream using the given `Pipe`.
    *
    * @example {{{
    * scala> Stream("Hello", "world").through(text.utf8Encode).toVector.toArray
    * res0: Array[Byte] = Array(72, 101, 108, 108, 111, 119, 111, 114, 108, 100)
    * }}}
    */
  def through[F2[x] >: F[x], O2](f: Stream[F, O] => Stream[F2, O2]): Stream[F2, O2] = f(this)

  /** Transforms this stream and `s2` using the given `Pipe2`. */
  def through2[F2[x] >: F[x], O2, O3](s2: Stream[F2, O2])(
      f: (Stream[F, O], Stream[F2, O2]) => Stream[F2, O3]): Stream[F2, O3] =
    f(this, s2)

  /**
    * Applies the given sink to this stream.
    *
    * @example {{{
    * scala> import cats.effect.IO, cats.implicits._
    * scala> Stream(1,2,3).covary[IO].to(Sink.showLinesStdOut).compile.drain.unsafeRunSync
    * res0: Unit = ()
    * }}}
    */
  def to[F2[x] >: F[x]](f: Stream[F, O] => Stream[F2, Unit]): Stream[F2, Unit] = f(this)

  /**
    * Translates effect type from `F` to `G` using the supplied `FunctionK`.
    */
  def translate[F2[x] >: F[x], G[_]](u: F2 ~> G): Stream[G, O] =
    Stream.fromFreeC[G, O](Algebra.translate[F2, G, O](get[F2, O], u))

  /**
    * Converts the input to a stream of 1-element chunks.
    *
    * @example {{{
    * scala> (Stream(1,2,3) ++ Stream(4,5,6)).unchunk.chunks.toList
    * res0: List[Chunk[Int]] = List(Chunk(1), Chunk(2), Chunk(3), Chunk(4), Chunk(5), Chunk(6))
    * }}}
    */
  def unchunk: Stream[F, O] =
    this.repeatPull {
      _.uncons1.flatMap {
        case None           => Pull.pure(None)
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
          hd.indexWhere(_.isEmpty) match {
            case Some(0)   => Pull.pure(None)
            case Some(idx) => Pull.output(hd.take(idx).map(_.get)).as(None)
            case None      => Pull.output(hd.map(_.get)).as(Some(tl))
          }
      }
    }

  private type ZipWithCont[G[_], I, O2, R] =
    Either[(Chunk[I], Stream[G, I]), Stream[G, I]] => Pull[G, O2, Option[R]]

  private def zipWith_[F2[x] >: F[x], O2 >: O, O3, O4](that: Stream[F2, O3])(
      k1: ZipWithCont[F2, O2, O4, Nothing],
      k2: ZipWithCont[F2, O3, O4, Nothing])(f: (O2, O3) => O4): Stream[F2, O4] = {
    def go(leg1: Stream.StepLeg[F2, O2],
           leg2: Stream.StepLeg[F2, O3]): Pull[F2, O4, Option[Nothing]] = {
      val l1h = leg1.head
      val l2h = leg2.head
      val out = l1h.zipWith(l2h)(f)
      Pull.output(out) >> {
        if (l1h.size > l2h.size) {
          val extra1 = l1h.drop(l2h.size)
          leg2.stepLeg.flatMap {
            case None      => k1(Left((extra1, leg1.stream)))
            case Some(tl2) => go(leg1.setHead(extra1), tl2)
          }
        } else {
          val extra2 = l2h.drop(l1h.size)
          leg1.stepLeg.flatMap {
            case None      => k2(Left((extra2, leg2.stream)))
            case Some(tl1) => go(tl1, leg2.setHead(extra2))
          }
        }
      }
    }

    covaryAll[F2, O2].pull.stepLeg.flatMap {
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
  def zipAll[F2[x] >: F[x], O2 >: O, O3](that: Stream[F2, O3])(pad1: O2,
                                                               pad2: O3): Stream[F2, (O2, O3)] =
    zipAllWith[F2, O2, O3, (O2, O3)](that)(pad1, pad2)(Tuple2.apply)

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
  def zipAllWith[F2[x] >: F[x], O2 >: O, O3, O4](that: Stream[F2, O3])(pad1: O2, pad2: O3)(
      f: (O2, O3) => O4): Stream[F2, O4] = {
    def cont1(
        z: Either[(Chunk[O2], Stream[F2, O2]), Stream[F2, O2]]): Pull[F2, O4, Option[Nothing]] = {
      def contLeft(s: Stream[F2, O2]): Pull[F2, O4, Option[Nothing]] =
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
    def cont2(
        z: Either[(Chunk[O3], Stream[F2, O3]), Stream[F2, O3]]): Pull[F2, O4, Option[Nothing]] = {
      def contRight(s: Stream[F2, O3]): Pull[F2, O4, Option[Nothing]] =
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
    zipWith_[F2, O2, O3, O4](that)(cont1, cont2)(f)
  }

  /**
    * Determinsitically zips elements, terminating when the end of either branch is reached naturally.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).zip(Stream(4, 5, 6, 7)).toList
    * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6))
    * }}}
    */
  def zip[F2[x] >: F[x], O2](that: Stream[F2, O2]): Stream[F2, (O, O2)] =
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
  def zipWith[F2[x] >: F[x], O2 >: O, O3, O4](that: Stream[F2, O3])(
      f: (O2, O3) => O4): Stream[F2, O4] =
    zipWith_[F2, O2, O3, O4](that)(sh => Pull.pure(None), h => Pull.pure(None))(f)

  /**
    * Zips the elements of the input stream with its indices, and returns the new stream.
    *
    * @example {{{
    * scala> Stream("The", "quick", "brown", "fox").zipWithIndex.toList
    * res0: List[(String,Long)] = List((The,0), (quick,1), (brown,2), (fox,3))
    * }}}
    */
  def zipWithIndex: Stream[F, (O, Long)] =
    this.scanChunks(0L) { (index, c) =>
      var idx = index
      val out = c.map { o =>
        val r = (o, idx)
        idx += 1
        r
      }
      (idx, out)
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
          val (newLast, out) = hd.mapAccumulate(last) {
            case (prev, next) => (next, (prev, Some(next)))
          }
          Pull.output(out) >> go(newLast, tl)
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
        val s2 = f(s, o)
        (s2, (o, s))
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
        val s2 = f(s, o)
        (s2, (o, s2))
      }
      .map(_._2)

  override def toString: String = "Stream(..)"
}

object Stream {
  @inline private[fs2] def fromFreeC[F[_], O](free: FreeC[Algebra[F, O, ?], Unit]): Stream[F, O] =
    new Stream(free.asInstanceOf[FreeC[Algebra[Nothing, Nothing, ?], Unit]])

  /** Creates a pure stream that emits the supplied values. To convert to an effectful stream, use [[covary]]. */
  def apply[F[x] >: Pure[x], O](os: O*): Stream[F, O] = emits(os)

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
  def attemptEval[F[x] >: Pure[x], O](fo: F[O]): Stream[F, Either[Throwable, O]] =
    fromFreeC(Pull.attemptEval(fo).flatMap(Pull.output1).get)

  /**
    * Light weight alternative to `awakeEvery` that sleeps for duration `d` before each pulled element.
    */
  def awakeDelay[F[x] >: Pure[x]](d: FiniteDuration)(implicit timer: Timer[F],
                                                     F: Functor[F]): Stream[F, FiniteDuration] =
    Stream.eval(timer.clockMonotonic(NANOSECONDS)).flatMap { start =>
      fixedDelay[F](d) *> Stream.eval(
        timer.clockMonotonic(NANOSECONDS).map(now => (now - start).nanos))
    }

  /**
    * Discrete stream that every `d` emits elapsed duration
    * since the start time of stream consumption.
    *
    * For example: `awakeEvery[IO](5 seconds)` will
    * return (approximately) `5s, 10s, 15s`, and will lie dormant
    * between emitted values.
    *
    * @param d FiniteDuration between emits of the resulting stream
    */
  def awakeEvery[F[x] >: Pure[x]](d: FiniteDuration)(implicit timer: Timer[F],
                                                     F: Functor[F]): Stream[F, FiniteDuration] =
    Stream.eval(timer.clockMonotonic(NANOSECONDS)).flatMap { start =>
      fixedRate[F](d) *> Stream.eval(
        timer.clockMonotonic(NANOSECONDS).map(now => (now - start).nanos))
    }

  /**
    * Creates a stream that emits a resource allocated by an effect, ensuring the resource is
    * eventually released regardless of how the stream is used.
    *
    * @param acquire resource to acquire at start of stream
    * @param release function which returns an effect that releases the resource
    *
    * A typical use case for bracket is working with files or network sockets. The resource effect
    * opens a file and returns a reference to it. One can then flatMap on the returned Stream to access
    *  the file, e.g to read bytes and transform them in to some stream of elements
    * (e.g., bytes, strings, lines, etc.).
    * The `release` action then closes the file once the result Stream terminates, even in case of interruption
    * or errors.
    */
  def bracket[F[x] >: Pure[x], R](acquire: F[R])(release: R => F[Unit]): Stream[F, R] =
    fromFreeC(Algebra.acquire[F, R, R](acquire, release).flatMap {
      case (r, token) =>
        Stream.emit(r).covary[F].get[F, R].transformWith {
          case Left(err) => Algebra.release(token).flatMap(_ => FreeC.Fail(err))
          case Right(_)  => Algebra.release(token)
        }
    })

  private[fs2] def bracketWithToken[F[x] >: Pure[x], R](acquire: F[R])(
      release: R => F[Unit]): Stream[F, (Token, R)] =
    fromFreeC(Algebra.acquire[F, (Token, R), R](acquire, release).flatMap {
      case (r, token) =>
        Stream
          .emit(r)
          .covary[F]
          .map(o => (token, o))
          .get[F, (Token, R)]
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
  def chunk[F[x] >: Pure[x], O](os: Chunk[O]): Stream[F, O] =
    Stream.fromFreeC(Algebra.output[F, O](os))

  /**
    * Creates an infinite pure stream that always returns the supplied value.
    *
    * Elements are emitted in finite chunks with `chunkSize` number of elements.
    *
    * @example {{{
    * scala> Stream.constant(0).take(5).toList
    * res0: List[Int] = List(0, 0, 0, 0, 0)
    * }}}
    */
  def constant[F[x] >: Pure[x], O](o: O, chunkSize: Int = 256): Stream[F, O] =
    chunk(Chunk.seq(List.fill(chunkSize)(o))).repeat

  /**
    * A continuous stream of the elapsed time, computed using `System.nanoTime`.
    * Note that the actual granularity of these elapsed times depends on the OS, for instance
    * the OS may only update the current time every ten milliseconds or so.
    */
  def duration[F[x] >: Pure[x]](implicit F: Sync[F]): Stream[F, FiniteDuration] =
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
  def emit[F[x] >: Pure[x], O](o: O): Stream[F, O] = fromFreeC(Algebra.output1[F, O](o))

  /**
    * Creates a pure stream that emits the supplied values.
    *
    * @example {{{
    * scala> Stream.emits(List(1, 2, 3)).toList
    * res0: List[Int] = List(1, 2, 3)
    * }}}
    */
  def emits[F[x] >: Pure[x], O](os: Seq[O]): Stream[F, O] =
    if (os.isEmpty) empty
    else if (os.size == 1) emit(os.head)
    else fromFreeC(Algebra.output[F, O](Chunk.seq(os)))

  /** Empty pure stream. */
  val empty: Stream[Pure, Nothing] =
    fromFreeC[Pure, Nothing](Algebra.pure[Pure, Nothing, Unit](())): Stream[Pure, Nothing]

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
    * use `awakeEvery` instead.
    */
  def every[F[x] >: Pure[x]](d: FiniteDuration)(implicit timer: Timer[F]): Stream[F, Boolean] = {
    def go(lastSpikeNanos: Long): Stream[F, Boolean] =
      Stream.eval(timer.clockMonotonic(NANOSECONDS)).flatMap { now =>
        if ((now - lastSpikeNanos) > d.toNanos) Stream.emit(true) ++ go(now)
        else Stream.emit(false) ++ go(lastSpikeNanos)
      }
    go(0)
  }

  /**
    * Light weight alternative to [[fixedRate]] that sleeps for duration `d` before each pulled element.
    *
    * Behavior differs from `fixedRate` because the sleep between elements occurs after the next element
    * is pulled whereas `fixedRate` accounts for the time it takes to process the emitted unit.
    * This difference can roughly be thought of as the difference between `scheduleWithFixedDelay` and
    * `scheduleAtFixedRate` in `java.util.concurrent.Scheduler`.
    *
    * Alias for `sleep(d).repeat`.
    */
  def fixedDelay[F[_]](d: FiniteDuration)(implicit timer: Timer[F]): Stream[F, Unit] =
    sleep(d).repeat

  /**
    * Discrete stream that emits a unit every `d`.
    *
    * See [[fixedDelay]] for an alternative that sleeps `d` between elements.
    *
    * @param d FiniteDuration between emits of the resulting stream
    */
  def fixedRate[F[_]](d: FiniteDuration)(implicit timer: Timer[F]): Stream[F, Unit] = {
    def now: Stream[F, Long] = Stream.eval(timer.clockMonotonic(NANOSECONDS))
    def loop(started: Long): Stream[F, Unit] =
      now.flatMap { finished =>
        val elapsed = finished - started
        Stream.sleep_(d - elapsed.nanos) ++ now.flatMap { started =>
          Stream.emit(()) ++ loop(started)
        }
      }
    now.flatMap(loop)
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
  def iterate[F[x] >: Pure[x], A](start: A)(f: A => A): Stream[F, A] =
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
  def getScope[F[x] >: Pure[x]]: Stream[F, Scope[F]] =
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
  def raiseError[F[x] >: Pure[x], O](e: Throwable): Stream[F, O] =
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
  def range[F[x] >: Pure[x]](start: Int, stopExclusive: Int, by: Int = 1): Stream[F, Int] =
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
  def ranges[F[x] >: Pure[x]](start: Int, stopExclusive: Int, size: Int): Stream[F, (Int, Int)] = {
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

  /** Converts the supplied resource in to a singleton stream. */
  def resource[F[_], O](r: Resource[F, O]): Stream[F, O] = r match {
    case Resource.Allocate(a) =>
      Stream.bracket(a) { case (_, release) => release(ExitCase.Completed) }.map(_._1)
    case Resource.Bind(r, f) => resource(r).flatMap(o => resource(f(o)))
    case Resource.Suspend(r) => Stream.eval(r).flatMap(resource)
  }

  /**
    * Retries `fo` on failure, returning a singleton stream with the
    * result of `fo` as soon as it succeeds.
    *
    * @param delay Duration of delay before the first retry
    *
    * @param nextDelay Applied to the previous delay to compute the
    *                  next, e.g. to implement exponential backoff
    *
    * @param maxRetries Number of attempts before failing with the
    *                   latest error, if `fo` never succeeds
    *
    * @param retriable Function to determine whether a failure is
    *                  retriable or not, defaults to retry every
    *                  `NonFatal`. A failed stream is immediately
    *                  returned when a non-retriable failure is
    *                  encountered
    */
  def retry[F[_], O](fo: F[O],
                     delay: FiniteDuration,
                     nextDelay: FiniteDuration => FiniteDuration,
                     maxRetries: Int,
                     retriable: Throwable => Boolean = scala.util.control.NonFatal.apply)(
      implicit F: Timer[F]): Stream[F, O] = {
    val delays = Stream.unfold(delay)(d => Some(d -> nextDelay(d))).covary[F]

    Stream
      .eval(fo)
      .attempts(delays)
      .take(maxRetries)
      .takeThrough(_.fold(err => retriable(err), _ => false))
      .last
      .map(_.getOrElse(sys.error("[fs2] impossible: empty stream in retry")))
      .rethrow
  }

  /**
    * Creates a stream from the supplied segment.
    *
    * The segment is unfolded in chunks of up to the specified size.
    *
    * @param s segment to lift
    * @param chunkSize max chunk size to emit before yielding execution downstream
    * @param maxSteps max number of times to step the segment while waiting for a chunk to be output; upon reaching this limit, an empty chunk is emitted and execution is yielded downstream
    */
  def segment[F[x] >: Pure[x], O, R](s: Segment[O, R],
                                     chunkSize: Int = 1024,
                                     maxSteps: Long = 1024): Stream[F, O] =
    Pull.segment(s, chunkSize, maxSteps).streamNoScope

  /**
    * A single-element `Stream` that waits for the duration `d` before emitting unit. This uses the implicit
    * `Timer` to avoid blocking a thread.
    */
  def sleep[F[_]](d: FiniteDuration)(implicit timer: Timer[F]): Stream[F, Unit] =
    Stream.eval(timer.sleep(d))

  /**
    * Alias for `sleep(d).drain`. Often used in conjunction with `++` (i.e., `sleep_(..) ++ s`) as a more
    * performant version of `sleep(..) >> s`.
    */
  def sleep_[F[_]](d: FiniteDuration)(implicit timer: Timer[F]): Stream[F, Nothing] =
    sleep(d).drain

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
  def unfold[F[x] >: Pure[x], S, O](s: S)(f: S => Option[(O, S)]): Stream[F, O] = {
    def loop(s: S): Stream[F, O] =
      f(s) match {
        case Some((o, s)) => emit(o) ++ loop(s)
        case None         => empty
      }
    suspend(loop(s))
  }

  /**
    * Like [[unfold]] but each invocation of `f` provides a chunk of output.
    *
    * @example {{{
    * scala> Stream.unfoldChunk(0)(i => if (i < 5) Some(Chunk.seq(List.fill(i)(i)) -> (i+1)) else None).toList
    * res0: List[Int] = List(1, 2, 2, 3, 3, 3, 4, 4, 4, 4)
    * }}}
    */
  def unfoldChunk[F[x] >: Pure[x], S, O](s: S)(f: S => Option[(Chunk[O], S)]): Stream[F, O] =
    unfold(s)(f).flatMap(chunk)

  /** Like [[unfold]], but takes an effectful function. */
  def unfoldEval[F[_], S, O](s: S)(f: S => F[Option[(O, S)]]): Stream[F, O] = {
    def loop(s: S): Stream[F, O] =
      eval(f(s)).flatMap {
        case Some((o, s)) => emit(o) ++ loop(s)
        case None         => empty
      }
    suspend(loop(s))
  }

  /** Like [[unfoldChunk]], but takes an effectful function. */
  def unfoldChunkEval[F[_], S, O](s: S)(f: S => F[Option[(Chunk[O], S)]]): Stream[F, O] = {
    def loop(s: S): Stream[F, O] =
      eval(f(s)).flatMap {
        case Some((c, s)) => chunk(c) ++ loop(s)
        case None         => empty
      }
    suspend(loop(s))
  }

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  implicit def InvariantOps[F[_], O](s: Stream[F, O]): InvariantOps[F, O] =
    new InvariantOps(s.get)

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  final class InvariantOps[F[_], O] private[Stream] (
      private val free: FreeC[Algebra[F, O, ?], Unit])
      extends AnyVal {
    private def self: Stream[F, O] = Stream.fromFreeC(free)

    /**
      * Lifts this stream to the specified effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream(1, 2, 3).covary[IO]
      * res0: Stream[IO,Int] = Stream(..)
      * }}}
      */
    def covary[F2[x] >: F[x]]: Stream[F2, O] = self

    /**
      * Synchronously sends values through `sink`.
      *
      * If `sink` fails, then resulting stream will fail. If sink `halts` the evaluation will halt too.
      *
      * Note that observe will only output full chunks of `O` that are known to be successfully processed
      * by `sink`. So if Sink terminates/fail in middle of chunk processing, the chunk will not be available
      * in resulting stream.
      *
      * Note that if your sink can be represented by an `O => F[Unit]`, `evalTap` will provide much greater performance.
      *
      * @example {{{
      * scala> import scala.concurrent.ExecutionContext.Implicits.global, cats.effect.IO, cats.implicits._
      * scala> Stream(1, 2, 3).covary[IO].observe(Sink.showLinesStdOut).map(_ + 1).compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(2, 3, 4)
      * }}}
      */
    def observe(sink: Sink[F, O])(implicit F: Concurrent[F]): Stream[F, O] =
      observeAsync(1)(sink)

    /** Send chunks through `sink`, allowing up to `maxQueued` pending _chunks_ before blocking `s`. */
    def observeAsync(maxQueued: Int)(sink: Sink[F, O])(implicit F: Concurrent[F]): Stream[F, O] =
      Stream.eval(Semaphore[F](maxQueued - 1)).flatMap { guard =>
        Stream.eval(async.unboundedQueue[F, Option[Chunk[O]]]).flatMap { outQ =>
          Stream.eval(async.unboundedQueue[F, Option[Chunk[O]]]).flatMap { sinkQ =>
            val inputStream =
              self.chunks.noneTerminate.evalMap {
                case Some(chunk) =>
                  sinkQ.enqueue1(Some(chunk)) >>
                    guard.acquire

                case None =>
                  sinkQ.enqueue1(None)
              }

            val sinkStream =
              sinkQ.dequeue.unNoneTerminate
                .flatMap { chunk =>
                  Stream.chunk(chunk) ++
                    Stream.eval_(outQ.enqueue1(Some(chunk)))
                }
                .to(sink) ++
                Stream.eval_(outQ.enqueue1(None))

            val runner =
              sinkStream.concurrently(inputStream) ++
                Stream.eval_(outQ.enqueue1(None))

            val outputStream =
              outQ.dequeue.unNoneTerminate
                .flatMap { chunk =>
                  Stream.chunk(chunk) ++
                    Stream.eval_(guard.release)
                }

            outputStream.concurrently(runner)
          }
        }
      }

    /** Gets a projection of this stream that allows converting it to a `Pull` in a number of ways. */
    def pull: ToPull[F, O] =
      new ToPull[F, O](free.asInstanceOf[FreeC[Algebra[Nothing, Nothing, ?], Unit]])

    /**
      * Repeatedly invokes `using`, running the resultant `Pull` each time, halting when a pull
      * returns `None` instead of `Some(nextStream)`.
      */
    def repeatPull[O2](
        using: Stream.ToPull[F, O] => Pull[F, O2, Option[Stream[F, O]]]): Stream[F, O2] =
      Pull.loop(using.andThen(_.map(_.map(_.pull))))(pull).stream

  }

  /** Provides syntax for pure pipes. */
  implicit def PureOps[O](s: Stream[Pure, O]): PureOps[O] =
    new PureOps(s.get[Pure, O])

  /** Provides syntax for pure pipes. */
  final class PureOps[O] private[Stream] (private val free: FreeC[Algebra[Pure, O, ?], Unit])
      extends AnyVal {
    private def self: Stream[Pure, O] = Stream.fromFreeC[Pure, O](free)

    /** Alias for covary, to be able to write `Stream.empty[X]`. */
    def apply[F[_]]: Stream[F, O] = covary

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F, O] = self

    /** Runs this pure stream and returns the emitted elements in a collection of the specified type. Note: this method is only available on pure streams. */
    def to[C[_]](implicit cbf: CanBuildFrom[Nothing, O, C[O]]): C[O] =
      self.covary[IO].compile.to[C].unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a list. Note: this method is only available on pure streams. */
    def toList: List[O] = self.covary[IO].compile.toList.unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a vector. Note: this method is only available on pure streams. */
    def toVector: Vector[O] = self.covary[IO].compile.toVector.unsafeRunSync
  }

  /** Provides syntax for pure pipes based on `cats.Id`. */
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
      * Waits for a chunk of elements to be available in the source stream.
      * The chunk of elements along with a new stream are provided as the resource of the returned pull.
      * The new stream can be used for subsequent operations, like awaiting again.
      * A `None` is returned as the resource of the pull upon reaching the end of the stream.
      */
    def uncons: Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] =
      Pull.fromFreeC(Algebra.uncons(self.get)).map {
        _.map { case (hd, tl) => (hd, Stream.fromFreeC(tl)) }
      }

    /** Like [[uncons]] but waits for a chunk instead of an entire chunk. */
    def unconsChunk: Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] = uncons

    /** Like [[uncons]] but waits for a single element instead of an entire chunk. */
    def uncons1: Pull[F, Nothing, Option[(O, Stream[F, O])]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.size match {
            case 0 => tl.pull.uncons1
            case 1 => Pull.pure(Some(hd(0) -> tl))
            case n => Pull.pure(Some(hd(0) -> tl.cons(hd.drop(1))))
          }
      }

    /**
      * Like [[uncons]], but returns a chunk of no more than `n` elements.
      *
      * `Pull.pure(None)` is returned if the end of the source stream is reached.
      */
    def unconsLimit(n: Int): Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] = {
      require(n > 0)
      uncons.flatMap {
        case Some((hd, tl)) =>
          if (hd.size < n) Pull.pure(Some(hd -> tl))
          else {
            val (out, rem) = hd.splitAt(n)
            Pull.pure(Some(out -> tl.cons(rem)))
          }
        case None => Pull.pure(None)
      }
    }

    /**
      * Like [[uncons]], but returns a chunk of exactly `n` elements, splitting chunk as necessary.
      *
      * `Pull.pure(None)` is returned if the end of the source stream is reached.
      */
    def unconsN(n: Int,
                allowFewer: Boolean = false): Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] = {
      def go(acc: List[Chunk[O]],
             n: Int,
             s: Stream[F, O]): Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] =
        s.pull.uncons.flatMap {
          case None =>
            if (allowFewer && acc.nonEmpty)
              Pull.pure(Some((Chunk.concat(acc.reverse), Stream.empty)))
            else Pull.pure(None)
          case Some((hd, tl)) =>
            if (hd.size < n) go(hd :: acc, n - hd.size, tl)
            else if (hd.size == n) Pull.pure(Some(Chunk.concat((hd :: acc).reverse) -> tl))
            else {
              val (pfx, sfx) = hd.splitAt(n)
              Pull.pure(Some(Chunk.concat((pfx :: acc).reverse) -> tl.cons(sfx)))
            }
        }
      if (n <= 0) Pull.pure(Some((Chunk.empty, self)))
      else go(Nil, n, self)
    }

    /** Drops the first `n` elements of this `Stream`, and returns the new `Stream`. */
    def drop(n: Long): Pull[F, Nothing, Option[Stream[F, O]]] =
      if (n <= 0) Pull.pure(Some(self))
      else
        uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            hd.size.toLong match {
              case m if m < n  => tl.pull.drop(n - m)
              case m if m == n => Pull.pure(Some(tl))
              case m           => Pull.pure(Some(tl.cons(hd.drop(n.toInt))))
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
          hd.indexWhere(o => !p(o)) match {
            case None => tl.pull.dropWhile_(p, dropFailure)
            case Some(idx) =>
              val toDrop = if (dropFailure) idx + 1 else idx
              Pull.pure(Some(tl.cons(hd.drop(toDrop))))
          }
      }

    /** Writes all inputs to the output of the returned `Pull`. */
    def echo: Pull[F, O, Unit] = Pull.fromFreeC(self.get)

    /** Reads a single element from the input and emits it to the output. */
    def echo1: Pull[F, O, Option[Stream[F, O]]] =
      uncons1.flatMap {
        case None           => Pull.pure(None)
        case Some((hd, tl)) => Pull.output1(hd).as(Some(tl))
      }

    /** Reads the next available chunk from the input and emits it to the output. */
    def echoChunk: Pull[F, O, Option[Stream[F, O]]] =
      uncons.flatMap {
        case None           => Pull.pure(None)
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
              val rem = hd.drop(idx + 1)
              Pull.pure(Some((hd(idx), tl.cons(rem))))
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
          val acc = hd.foldLeft(z)(f)
          tl.pull.fold(acc)(f)
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
          hd.indexWhere(o => !p(o)) match {
            case Some(_) => Pull.pure(false)
            case None    => tl.pull.forall(p)
          }
      }

    /** Returns the last element of the input, if non-empty. */
    def last: Pull[F, Nothing, Option[O]] = {
      def go(prev: Option[O], s: Stream[F, O]): Pull[F, Nothing, Option[O]] =
        s.pull.uncons.flatMap {
          case None => Pull.pure(prev)
          case Some((hd, tl)) =>
            val last = hd.foldLeft(prev)((_, o) => Some(o))
            go(last, tl)
        }
      go(None, self)
    }

    /** Like [[uncons]] but does not consume the chunk (i.e., the chunk is pushed back). */
    def peek: Pull[F, Nothing, Option[(Chunk[O], Stream[F, O])]] =
      uncons.flatMap {
        case None           => Pull.pure(None)
        case Some((hd, tl)) => Pull.pure(Some((hd, tl.cons(hd))))
      }

    /** Like [[uncons1]] but does not consume the element (i.e., the element is pushed back). */
    def peek1: Pull[F, Nothing, Option[(O, Stream[F, O])]] =
      uncons1.flatMap {
        case None           => Pull.pure(None)
        case Some((hd, tl)) => Pull.pure(Some((hd, tl.cons1(hd))))
      }

    /**
      * Like `scan` but `f` is applied to each chunk of the source stream.
      * The resulting chunk is emitted and the result of the chunk is used in the
      * next invocation of `f`. The final state value is returned as the result of the pull.
      */
    def scanChunks[S, O2](init: S)(f: (S, Chunk[O]) => (S, Chunk[O2])): Pull[F, O2, S] =
      scanChunksOpt(init)(s => Some(c => f(s, c)))

    /**
      * More general version of `scanChunks` where the current state (i.e., `S`) can be inspected
      * to determine if another chunk should be pulled or if the pull should terminate.
      * Termination is signaled by returning `None` from `f`. Otherwise, a function which consumes
      * the next chunk is returned wrapped in `Some`. The final state value is returned as the
      * result of the pull.
      */
    def scanChunksOpt[S, O2](init: S)(
        f: S => Option[Chunk[O] => (S, Chunk[O2])]): Pull[F, O2, S] = {
      def go(acc: S, s: Stream[F, O]): Pull[F, O2, S] =
        f(acc) match {
          case None => Pull.pure(acc)
          case Some(g) =>
            s.pull.uncons.flatMap {
              case Some((hd, tl)) =>
                val (s2, c) = g(hd)
                Pull.output(c) >> go(s2, tl)
              case None =>
                Pull.pure(acc)
            }
        }
      go(init, self)
    }

    /**
      * Like `uncons`, but instead of performing normal `uncons`, this will
      * run the stream up to the first chunk available.
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
          new StepLeg[F, O](Chunk.empty, scope.id, self.get).stepLeg
        }

    /** Emits the first `n` elements of the input. */
    def take(n: Long): Pull[F, O, Option[Stream[F, O]]] =
      if (n <= 0) Pull.pure(None)
      else
        uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            hd.size.toLong match {
              case m if m <= n => Pull.output(hd) >> tl.pull.take(n - m)
              case m if m == n => Pull.output(hd).as(Some(tl))
              case m =>
                val (pfx, sfx) = hd.splitAt(n.toInt)
                Pull.output(pfx).as(Some(tl.cons(sfx)))
            }
        }

    /** Emits the last `n` elements of the input. */
    def takeRight(n: Int): Pull[F, Nothing, Chunk[O]] = {
      def go(acc: Vector[O], s: Stream[F, O]): Pull[F, Nothing, Chunk[O]] =
        s.pull.unconsN(n, true).flatMap {
          case None => Pull.pure(Chunk.vector(acc))
          case Some((hd, tl)) =>
            val vector = hd.toVector
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
          hd.indexWhere(o => !p(o)) match {
            case None => Pull.output(hd) >> tl.pull.takeWhile_(p, takeFailure)
            case Some(idx) =>
              val toTake = if (takeFailure) idx + 1 else idx
              val (pfx, sfx) = hd.splitAt(toTake)
              Pull.output(pfx) >> Pull.pure(Some(tl.cons(sfx)))
          }
      }
  }

  /** Projection of a `Stream` providing various ways to compile a `Stream[F,O]` to an `F[...]`. */
  final class ToEffect[F[_], O] private[Stream] (
      private val free: FreeC[Algebra[Nothing, Nothing, ?], Unit])
      extends AnyVal {

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
      * scala> Stream.empty.compile[IO, Int].foldSemigroup.unsafeRunSync
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
      val head: Chunk[O],
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
        }(self.setHead(Chunk.empty))
        .stream

    /** Replaces head of this leg. Useful when the head was not fully consumed. */
    def setHead(nextHead: Chunk[O]): StepLeg[F, O] =
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

  /** Implicitly covaries a pipe. */
  implicit def covaryPurePipe[F[_], I, O](p: Pipe[Pure, I, O]): Pipe[F, I, O] =
    p.covary[F]

  /**
    * `MonadError` instance for `Stream`.
    *
    * @example {{{
    * scala> import cats.implicits._
    * scala> Stream(1, -2, 3).fproduct(_.abs).toList
    * res0: List[(Int, Int)] = List((1,1), (-2,2), (3,3))
    * }}}
    */
  implicit def monadErrorInstance[F[_]]: MonadError[Stream[F, ?], Throwable] =
    new MonadError[Stream[F, ?], Throwable] {
      def pure[A](a: A) = Stream(a)
      def handleErrorWith[A](s: Stream[F, A])(h: Throwable => Stream[F, A]) =
        s.handleErrorWith(h)
      def raiseError[A](t: Throwable) = Stream.raiseError(t)
      def flatMap[A, B](s: Stream[F, A])(f: A => Stream[F, B]) = s.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Stream[F, Either[A, B]]) = f(a).flatMap {
        case Left(a)  => tailRecM(a)(f)
        case Right(b) => Stream(b)
      }
    }

  /** `Monoid` instance for `Stream`. */
  implicit def monoidInstance[F[_], O]: Monoid[Stream[F, O]] =
    new Monoid[Stream[F, O]] {
      def empty = Stream.empty
      def combine(x: Stream[F, O], y: Stream[F, O]) = x ++ y
    }
}
