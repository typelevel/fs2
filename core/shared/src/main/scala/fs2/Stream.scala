package fs2

import scala.concurrent.ExecutionContext
// import scala.concurrent.duration.FiniteDuration

import cats.{ ~>, Applicative, Eq, Monoid, Semigroup }
import cats.effect.{ Effect, IO, Sync }
import cats.implicits._

import fs2.internal.{ Algebra, Free }

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
 * And `push` is consistent with using `append` to prepend a single chunk:
 *   - `push(c)(s) == chunk(c) append s`
 *
 * `fail` propagates until being caught by `onError`:
 *   - `fail(e) onError h == h(e)`
 *   - `fail(e) append s == fail(e)`
 *   - `fail(e) flatMap f == fail(e)`
 *
 * `Stream` forms a monad with `emit` and `flatMap`:
 *   - `emit >=> f == f` (left identity)
 *   - `f >=> emit === f` (right identity - note weaker equality notion here)
 *   - `(f >=> g) >=> h == f >=> (g >=> h)` (associativity)
 *  where `emit(a)` is defined as `chunk(Chunk.singleton(a)) and
 *  `f >=> g` is defined as `a => a flatMap f flatMap g`
 *
 * The monad is the list-style sequencing monad:
 *   - `(a append b) flatMap f == (a flatMap f) append (b flatMap f)`
 *   - `empty flatMap f == empty`
 *
 * '''Technical notes'''
 *
 * ''Note:'' since the chunk structure of the stream is observable, and
 * `s flatMap (emit)` produces a stream of singleton chunks,
 * the right identity law uses a weaker notion of equality, `===` which
 * normalizes both sides with respect to chunk structure:
 *
 *   `(s1 === s2) = normalize(s1) == normalize(s2)`
 *   where `==` is full equality
 *   (`a == b` iff `f(a)` is identical to `f(b)` for all `f`)
 *
 * `normalize(s)` can be defined as `s.repeatPull(_.echo1)`, which just
 * produces a singly-chunked stream from any input stream `s`.
 *
 * ''Note:'' For efficiency `[[Stream.map]]` function operates on an entire
 * chunk at a time and preserves chunk structure, which differs from
 * the `map` derived from the monad (`s map f == s flatMap (f andThen emit)`)
 * which would produce singleton chunks. In particular, if `f` throws errors, the
 * chunked version will fail on the first ''chunk'' with an error, while
 * the unchunked version will fail on the first ''element'' with an error.
 * Exceptions in pure code like this are strongly discouraged.
 */
final class Stream[+F[_],+O] private(private val free: Free[Algebra[Nothing,Nothing,?],Unit]) extends AnyVal {

  private[fs2] def get[F2[x]>:F[x],O2>:O]: Free[Algebra[F2,O2,?],Unit] = free.asInstanceOf[Free[Algebra[F2,O2,?],Unit]]

  def attempt: Stream[F,Either[Throwable,O]] =
    map(Right(_)).onError(e => Stream.emit(Left(e)))

  /** `s as x == s map (_ => x)` */
  def as[O2](o2: O2): Stream[F,O2] = map(_ => o2)

  /** Alias for [[pipe.buffer]]. */
  def buffer(n: Int): Stream[F,O] = this through pipe.buffer(n)

  // /** Alias for `self through [[pipe.bufferAll]]`. */
  // def bufferAll: Stream[F,O] = self through pipe.bufferAll
  //
  // /** Alias for `self through [[pipe.bufferBy]]`. */
  // def bufferBy(f: O => Boolean): Stream[F,O] = self through pipe.bufferBy(f)

  /** Alias for `self through [[pipe.changesBy]]`. */
  def changesBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F,O] = this through pipe.changesBy(f)

  /** Alias for `self through [[pipe.chunks]]`. */
  def chunks: Stream[F,Chunk[O]] = this through pipe.chunks

  /** Alias for `self through [[pipe.collect]]`. */
  def collect[O2](pf: PartialFunction[O, O2]) = this through pipe.collect(pf)

  /** Alias for `self through [[pipe.collectFirst]]`. */
  def collectFirst[O2](pf: PartialFunction[O, O2]) = this through pipe.collectFirst(pf)

  /** Prepend a single segment onto the front of this stream. */
  def cons[O2>:O](s: Segment[O2,Unit]): Stream[F,O2] =
    Stream.segment(s) ++ this

  /** Prepend a single chunk onto the front of this stream. */
  def consChunk[O2>:O](c: Chunk[O2]): Stream[F,O2] =
    if (c.isEmpty) this else Stream.chunk(c) ++ this

  /** Prepend a single value onto the front of this stream. */
  def cons1[O2>:O](o: O2): Stream[F,O2] =
    cons(Segment.singleton(o))

  /** Lifts this stream to the specified output type. */
  def covaryOutput[O2>:O]: Stream[F,O2] = this.asInstanceOf[Stream[F,O2]]

  /** Alias for [[pipe.delete]]. */
  def delete(f: O => Boolean): Stream[F,O] = this through pipe.delete(f)

  /**
   * Removes all output values from this stream.
   * For example, `Stream.eval(IO(println("x"))).drain.runLog`
   * will, when `unsafeRunSync` in called, print "x" but return `Vector()`.
   */
  def drain: Stream[F, Nothing] = this.flatMap { _ => Stream.empty }

  def drop(n: Long): Stream[F,O] = this.through(pipe.drop(n))

  /** Alias for `self through [[pipe.dropLast]]`. */
  def dropLast: Stream[F,O] = this through pipe.dropLast

  /** Alias for `self through [[pipe.dropLastIf]]`. */
  def dropLastIf(p: O => Boolean): Stream[F,O] = this through pipe.dropLastIf(p)

  /** Alias for `self through [[pipe.dropRight]]`. */
  def dropRight(n: Int): Stream[F,O] = this through pipe.dropRight(n)

  /** Alias for `self through [[pipe.dropThrough]]` */
  def dropThrough(p: O => Boolean): Stream[F,O] = this through pipe.dropThrough(p)

  /** Alias for `self through [[pipe.dropWhile]]` */
  def dropWhile(p: O => Boolean): Stream[F,O] = this through pipe.dropWhile(p)

  /** Alias for `self through [[pipe.exists]]`. */
  def exists(f: O => Boolean): Stream[F, Boolean] = this through pipe.exists(f)

  /** Alias for `self through [[pipe.filter]]`. */
  def filter(f: O => Boolean): Stream[F,O] = this through pipe.filter(f)

  /** Alias for `self through [[pipe.filterWithPrevious]]`. */
  def filterWithPrevious(f: (O, O) => Boolean): Stream[F,O] = this through pipe.filterWithPrevious(f)

  /** Alias for [[pipe.find]]. */
  def find(f: O => Boolean): Stream[F,O] = this through pipe.find(f)

  /** Alias for `self through [[pipe.fold]](z)(f)`. */
  def fold[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = this through pipe.fold(z)(f)

  /** Alias for `self through [[pipe.fold1]](f)`. */
  def fold1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = this through pipe.fold1(f)

  /** Alias for `map(f).foldMonoid`. */
  def foldMap[O2](f: O => O2)(implicit O2: Monoid[O2]): Stream[F,O2] = fold(O2.empty)((acc, o) => O2.combine(acc, f(o)))

  /** Alias for `self through [[pipe.forall]]`. */
  def forall(f: O => Boolean): Stream[F, Boolean] = this through pipe.forall(f)

  // /** Alias for `self through [[pipe.groupBy]]`. */
  // def groupBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, (O2, Vector[O])] = self through pipe.groupBy(f)

  /** Alias for `self through [[pipe.head]]`. */
  def head: Stream[F,O] = this through pipe.head

  /** Alias for `self through [[pipe.intersperse]]`. */
  def intersperse[O2 >: O](separator: O2): Stream[F,O2] = this through pipe.intersperse(separator)

  /** Alias for `self through [[pipe.last]]`. */
  def last: Stream[F,Option[O]] = this through pipe.last

  /** Alias for `self through [[pipe.lastOr]]`. */
  def lastOr[O2 >: O](li: => O2): Stream[F,O2] = this through pipe.lastOr(li)

  // /** Alias for `self through [[pipe.mapAccumulate]]` */
  // def mapAccumulate[S,O2](init: S)(f: (S, O) => (S, O2)): Stream[F, (S, O2)] =
  //   self through pipe.mapAccumulate(init)(f)

  def map[O2](f: O => O2): Stream[F,O2] =
    this.repeatPull(_.receive { (hd, tl) => Pull.output(hd map f).as(Some(tl)) })

  def mapSegments[O2](f: Segment[O,Unit] => Segment[O2,Unit]): Stream[F,O2] =
    this.repeatPull { _.receive { (hd,tl) => Pull.output(f(hd)).as(Some(tl)) }}

  def mask: Stream[F,O] = this.onError(_ => Stream.empty)

  def noneTerminate: Stream[F,Option[O]] = map(Some(_)) ++ Stream.emit(None)

  // def open: Pull[F,Nothing,Stream[F,O]] = Pull.pure(this)

  // def output: Pull[F,O,Unit] = Pull.outputs(self)

  /** Repeat this stream an infinite number of times. `s.repeat == s ++ s ++ s ++ ...` */
  def repeat: Stream[F,O] = this ++ repeat

  // /** Alias for `self through [[pipe.rechunkN]](f).` */
  // def rechunkN(n: Int, allowFewer: Boolean = true): Stream[F,O] = self through pipe.rechunkN(n, allowFewer)

  /** Alias for `[[pipe.rethrow]]` */
  def rethrow[O2](implicit ev: O <:< Either[Throwable,O2]): Stream[F,O2] =
    this.asInstanceOf[Stream[F,Either[Throwable,O2]]] through pipe.rethrow

  /** Alias for `self through [[pipe.reduce]](z)(f)`. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = this through pipe.reduce(f)

  /** Alias for `self through [[pipe.scan]](z)(f)`. */
  def scan[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = this through pipe.scan(z)(f)

  /** Alias for `self through [[pipe.scan1]](f)`. */
  def scan1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = this through pipe.scan1(f)

  // /**
  //  * Used in conjunction with `[[Stream.uncons]]` or `[[Stream.uncons1]]`.
  //  * When `s.scope` starts, the set of live resources is recorded.
  //  * When `s.scope` finishes, any newly allocated resources since the start of `s`
  //  * are all cleaned up.
  //  */
  // def scope: Stream[F,O] =
  //   Stream.mk { StreamCore.scope { self.get } }

  /** Alias for [[pipe.segmentLimit]]. */
  def segmentLimit(n: Int): Stream[F,Segment[O,Unit]] =
    this through pipe.segmentLimit(n)

  /** Alias for [[pipe.segmentN]]. */
  def segmentN(n: Int, allowFewer: Boolean = true): Stream[F,Segment[O,Unit]] =
    this through pipe.segmentN(n, allowFewer)

  /** Alias for [[pipe.segments]]. */
  def segments: Stream[F,Segment[O,Unit]] = this through pipe.segments

  //
  // /** Alias for `self through [[pipe.shiftRight]]`. */
  // def shiftRight[O2 >: O](head: O2*): Stream[F,O2] = self through pipe.shiftRight(head: _*)
  //
  // /** Alias for `self through [[pipe.sliding]]`. */
  // def sliding(n: Int): Stream[F,Vector[O]] = self through pipe.sliding(n)
  //
  // /** Alias for `self through [[pipe.split]]`. */
  // def split(f: O => Boolean): Stream[F,Vector[O]] = self through pipe.split(f)

  /** Alias for [[pipe.tail]]. */
  def tail: Stream[F,O] = this through pipe.tail

  def take(n: Long): Stream[F,O] = this through pipe.take(n)

  /** Alias for [[pipe.takeRight]]. */
  def takeRight(n: Long): Stream[F,O] = this through pipe.takeRight(n)

  /** Alias for [[pipe.takeThrough]]. */
  def takeThrough(p: O => Boolean): Stream[F,O] = this through pipe.takeThrough(p)

  /** Alias for `self through [[pipe.takeWhile]]`. */
  def takeWhile(p: O => Boolean): Stream[F,O] = this through pipe.takeWhile(p)

  /** Alias for `self through [[pipe.unchunk]]`. */
  def unchunk: Stream[F,O] = this through pipe.unchunk

  /** Return leading `Segment[O,Unit]` emitted by this stream. */
  def uncons: Stream[F,Option[(Segment[O,Unit],Stream[F,O])]] =
    // this.pull.uncons.flatMap(Pull.output1).close <-- TODO Do we want scoping here? Doesn't seem to matter either way - finalizers are eagerly run
    Stream.fromFree(Algebra.uncons(get[F,O]).flatMap(o => Algebra.output(Chunk.singleton(o)))).
      map { _.map { case (hd, tl) => (hd, Stream.fromFree(tl)) } }

  /**
  * A new [[Stream]] of one element containing the head element of this stream along
  * with a reference to the remaining stream after evaluation of the first element.
  *
  * {{{
  *   scala> Stream(1,2,3).uncons1.toList
  *   res1: List[Option[(Int, Stream[Pure, Int])]] = List(Some((1,Stream(..))))
  * }}}
  *
  * You can use this to implement any stateful stream function, like `take`:
  *
  * {{{
     def take[F[_],A](n: Int)(s: Stream[F,A]): Stream[F,A] = {
       if (n <= 0) Stream.empty
       else s.uncons1.flatMap {
         case None => Stream.empty
         case Some((hd, tl)) => Stream.emit(hd) ++ take(n-1)(tl)
       }
     }
   }}}
  *
  * So `uncons` and `uncons1` can be viewed as an alternative to using `Pull`, with
  * an important caveat: if you use `uncons` or `uncons1`, you are responsible for
  * telling FS2 when you're done unconsing the stream, which you do using `[[Stream.scope]]`.
  *
  * For instance, the above definition of `take` doesn't call `scope`, so any finalizers
  * attached won't be run until the very end of any enclosing `scope` or `Pull`
  * (or the end of the stream if there is no enclosing scope). So in the following code:
  *
  * {{{
  *    take(2)(Stream(1,2,3).onFinalize(IO(println("done"))) ++
  *    anotherStream
  * }}}
  *
  * The "done" would not be printed until the end of `anotherStream`. To get the
  * prompt finalization behavior, we would have to do:
  *
  * {{{
  *    take(2)(Stream(1,2,3).onFinalize(IO(println("done"))).scope ++
  *    anotherStream
  * }}}
  *
  * Note the call to `scope` after the completion of `take`, which ensures that
  * when that stream completes, any streams which have been opened by the `take`
  * are deemed closed and their finalizers can be run.
  */
  def uncons1: Stream[F,Option[(O,Stream[F,O])]] =
    this.pull.uncons1.flatMap(Pull.output1).stream

  /** Alias for `[[pipe.unNone]]` */
  def unNone[O2](implicit ev: O <:< Option[O2]): Stream[F,O2] =
    this.asInstanceOf[Stream[F,Option[O2]]] through pipe.unNone

  /** Alias for `[[pipe.unNoneTerminate]]` */
  def unNoneTerminate[O2](implicit ev: O <:< Option[O2]): Stream[F,O2] =
    this.asInstanceOf[Stream[F,Option[O2]]] through pipe.unNoneTerminate

  // /** Alias for `self through [[pipe.zipWithIndex]]`. */
  // def zipWithIndex: Stream[F, (O, Int)] = self through pipe.zipWithIndex
  //
  // /** Alias for `self through [[pipe.zipWithNext]]`. */
  // def zipWithNext: Stream[F, (O, Option[O])] = self through pipe.zipWithNext
  //
  // /** Alias for `self through [[pipe.zipWithPrevious]]`. */
  // def zipWithPrevious: Stream[F, (Option[O], O)] = self through pipe.zipWithPrevious
  //
  // /** Alias for `self through [[pipe.zipWithPreviousAndNext]]`. */
  // def zipWithPreviousAndNext: Stream[F, (Option[O], O, Option[O])] = self through pipe.zipWithPreviousAndNext
  //
  // /** Alias for `self through [[pipe.zipWithScan]]`. */
  // def zipWithScan[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] = self through pipe.zipWithScan(z)(f)
  //
  // /** Alias for `self through [[pipe.zipWithScan1]]`. */
  // def zipWithScan1[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] = self through pipe.zipWithScan1(z)(f)

  override def toString: String = "Stream(..)"
}

object Stream {
  private[fs2] def fromFree[F[_],O](free: Free[Algebra[F,O,?],Unit]): Stream[F,O] =
    new Stream(free.asInstanceOf[Free[Algebra[Nothing,Nothing,?],Unit]])

  def append[F[_],O](s1: Stream[F,O], s2: => Stream[F,O]): Stream[F,O] =
    fromFree(s1.get.flatMap { _ => s2.get })

  def apply[O](os: O*): Stream[Pure,O] = fromFree(Algebra.output[Pure,O](Chunk.seq(os)))

  def attemptEval[F[_],O](fo: F[O]): Stream[F,Either[Throwable,O]] =
    fromFree(Pull.attemptEval(fo).flatMap(Pull.output1).get)

  def bracket[F[_],R,O](r: F[R])(use: R => Stream[F,O], release: R => F[Unit]): Stream[F,O] =
    fromFree(Algebra.acquire[F,O,R](r, release).flatMap { case (r, token) =>
      use(r).onComplete { fromFree(Algebra.release(token)) }.get
    })

  private[fs2] def bracketWithToken[F[_],R,O](r: F[R])(use: R => Stream[F,O], release: R => F[Unit]): Stream[F,(Algebra.Token,O)] =
    fromFree(Algebra.acquire[F,(Algebra.Token,O),R](r, release).flatMap { case (r, token) =>
      use(r).map(o => (token,o)).onComplete { fromFree(Algebra.release(token)) }.get
    })

  def chunk[F[_],O](os: Chunk[O]): Stream[F,O] = segment(os)

  @deprecated("Use s.cons(c) instead", "1.0")
  def cons[F[_],O](s: Stream[F,O])(c: Chunk[O]): Stream[F,O] = s.cons(c)

  /**
   * The infinite `Stream`, always emits `o`.
   * If for performance reasons it is good to emit `o` in chunks,
   * specify size of chunk by `chunkSize` parameter
   */
  def constant[F[_],O](o: O, chunkSize: Int = 1): Stream[F,O] =
    emits(List.fill(chunkSize)(o)) ++ constant(o, chunkSize)

  def emit[F[_],O](o: O): Stream[F,O] = fromFree(Algebra.output1[F,O](o))
  def emits[F[_],O](os: Seq[O]): Stream[F,O] = chunk(Chunk.seq(os))

  private[fs2] val empty_ = fromFree[Nothing,Nothing](Algebra.pure[Nothing,Nothing,Unit](())): Stream[Nothing,Nothing]
  def empty[F[_],O]: Stream[F,O] = empty_

  def eval[F[_],O](fo: F[O]): Stream[F,O] = fromFree(Algebra.eval(fo).flatMap(Algebra.output1))
  def eval_[F[_],A](fa: F[A]): Stream[F,Nothing] = eval(fa) >> empty

  def fail[F[_],O](e: Throwable): Stream[F,O] = fromFree(Algebra.fail(e))

  def force[F[_],A](f: F[Stream[F, A]]): Stream[F,A] =
    eval(f).flatMap(s => s)

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
   * stream that cased initial failure.
   *
   * Finalizers on each inner stream are run at the end of the inner stream,
   * concurrently with other stream computations.
   *
   * Finalizers on the outer stream are run after all inner streams have been pulled
   * from the outer stream -- hence, finalizers on the outer stream will likely run
   * BEFORE the LAST finalizer on the last inner stream.
   *
   * Finalizers on the returned stream are run after the outer stream has finished
   * and all open inner streams have finished.
   *
   * @param maxOpen    Maximum number of open inner streams at any time. Must be > 0.
   * @param outer      Stream of streams to join.
   */
  def join[F[_],O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] = {
    assert(maxOpen > 0, "maxOpen must be > 0, was: " + maxOpen)

    Stream.eval(async.signalOf(false)) flatMap { killSignal =>
    Stream.eval(async.semaphore(maxOpen)) flatMap { available =>
    Stream.eval(async.signalOf(1l)) flatMap { running => // starts with 1 because outer stream is running by default
    Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Either[Throwable,Segment[O,Unit]]]) flatMap { outputQ => // sync queue assures we won't overload heap when resulting stream is not able to catchup with inner streams
      val incrementRunning: F[Unit] = running.modify(_ + 1).as(())
      val decrementRunning: F[Unit] = running.modify(_ - 1).as(())

      // runs inner stream
      // each stream is forked.
      // terminates when killSignal is true
      // if fails will enq in queue failure
      def runInner(inner: Stream[F, O]): Stream[F, Nothing] = {
        Stream.eval_(
          available.decrement >> incrementRunning >>
          concurrent.start {
            inner.segments.attempt
            .flatMap(r => Stream.eval(outputQ.enqueue1(Some(r))))
            .interruptWhen(killSignal) // must be AFTER enqueue to the the sync queue, otherwise the process may hang to enq last item while being interrupted
            .run.flatMap { _ =>
              available.increment >> decrementRunning
            }
          }
        )
      }

      // runs the outer stream, interrupts when kill == true, and then decrements the `available`
      def runOuter: F[Unit] = {
        (outer.interruptWhen(killSignal) flatMap runInner onFinalize decrementRunning run).attempt flatMap {
          case Right(_) => F.pure(())
          case Left(err) => outputQ.enqueue1(Some(Left(err)))
        }
      }

      // monitors when the all streams (outer/inner) are terminated an then suplies None to output Queue
      def doneMonitor: F[Unit]= {
        running.discrete.dropWhile(_ > 0) take 1 flatMap { _ =>
          Stream.eval(outputQ.enqueue1(None))
        } run
      }

      Stream.eval_(concurrent.start(runOuter)) ++
      Stream.eval_(concurrent.start(doneMonitor)) ++
      outputQ.dequeue.unNoneTerminate.flatMap {
        case Left(e) => Stream.fail(e)
        case Right(s) => Stream.segment(s)
      } onFinalize {
        killSignal.set(true) >> (running.discrete.dropWhile(_ > 0) take 1 run) // await all open inner streams and the outer stream to be terminated
      }
    }}}}
  }

  /** Like [[join]] but races all inner streams simultaneously. */
  def joinUnbounded[F[_],O](outer: Stream[F,Stream[F,O]])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
    join(Int.MaxValue)(outer)

  /**
   * An infinite `Stream` that repeatedly applies a given function
   * to a start value. `start` is the first value emitted, followed
   * by `f(start)`, then `f(f(start))`, and so on.
   */
  def iterate[F[_],A](start: A)(f: A => A): Stream[F,A] =
    emit(start) ++ iterate(f(start))(f)

  /**
   * Like [[iterate]], but takes an effectful function for producing
   * the next state. `start` is the first value emitted.
   */
  def iterateEval[F[_],A](start: A)(f: A => F[A]): Stream[F,A] =
    emit(start) ++ eval(f(start)).flatMap(iterateEval(_)(f))

  /**
   * Lazily produce the range `[start, stopExclusive)`. If you want to produce
   * the sequence in one chunk, instead of lazily, use
   * `emits(start until stopExclusive)`.
   */
  def range[F[_]](start: Int, stopExclusive: Int, by: Int = 1): Stream[F,Int] =
    unfold(start){i =>
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
   * @throws IllegalArgumentException if `size` <= 0
   */
  def ranges[F[_]](start: Int, stopExclusive: Int, size: Int): Stream[F,(Int,Int)] = {
    require(size > 0, "size must be > 0, was: " + size)
    unfold(start){
      lower =>
        if (lower < stopExclusive)
          Some((lower -> ((lower+size) min stopExclusive), lower+size))
        else
          None
    }
  }

  def repeatEval[F[_],O](fo: F[O]): Stream[F,O] = eval(fo).repeat

  def segment[F[_],O](s: Segment[O,Unit]): Stream[F,O] =
    fromFree(Algebra.output[F,O](s))

  def suspend[F[_],O](s: => Stream[F,O]): Stream[F,O] =
    emit(()).flatMap { _ => s }

  def unfold[F[_],S,O](s: S)(f: S => Option[(O,S)]): Stream[F,O] =
    segment(Segment.unfold(s)(f))

  /** Produce a (potentially infinite) stream from an unfold of Chunks. */
  def unfoldChunk[F[_],S,A](s0: S)(f: S => Option[(Chunk[A],S)]): Stream[F,A] = {
    def go(s: S): Stream[F,A] =
      f(s) match {
        case Some((a, sn)) => chunk(a) ++ go(sn)
        case None => empty
      }
    suspend(go(s0))
  }

  /** Like [[unfold]], but takes an effectful function. */
  def unfoldEval[F[_],S,A](s0: S)(f: S => F[Option[(A,S)]]): Stream[F,A] = {
    def go(s: S): Stream[F,A] =
      eval(f(s)).flatMap {
        case Some((a, sn)) => emit(a) ++ go(sn)
        case None => empty
      }
    suspend(go(s0))
  }

  /** Like [[unfoldChunk]], but takes an effectful function. */
  def unfoldChunkEval[F[_],S,A](s0: S)(f: S => F[Option[(Chunk[A],S)]]): Stream[F,A] = {
    def go(s: S): Stream[F,A] =
      eval(f(s)).flatMap {
        case Some((a, sn)) => chunk(a) ++ go(sn)
        case None => empty
      }
    suspend(go(s0))
  }

  implicit def StreamInvariantOps[F[_],O](s: Stream[F,O]): StreamInvariantOps[F,O] = new StreamInvariantOps(s.get)
  class StreamInvariantOps[F[_],O](private val free: Free[Algebra[F,O,?],Unit]) extends AnyVal {
    private def self: Stream[F,O] = Stream.fromFree(free)

    def ++[O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(self, s2)

    def append[O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(self, s2)

    /** Alias for `self through [[pipe.changes]]`. */
    def changes(implicit eq: Eq[O]): Stream[F,O] = self through pipe.changes

    /** Lifts this stream to the specified effect type. */
    def covary[F2[x]>:F[x]]: Stream[F2,O] = self.asInstanceOf[Stream[F2,O]]

    /** Lifts this stream to the specified effect and output types. */
    def covaryAll[F2[x]>:F[x],O2>:O]: Stream[F2,O2] = self.asInstanceOf[Stream[F2,O2]]

    // /** Alias for `self through [[pipe.debounce]]`. */
    // def debounce(d: FiniteDuration)(implicit F: Effect[F], scheduler: Scheduler, ec: ExecutionContext): Stream[F, O] =
    //   self through pipe.debounce(d)

    /** Alias for [[pipe2.either]]. */
    def either[O2](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,Either[O,O2]] =
      self.through2(s2)(pipe2.either)

    def evalMap[O2](f: O => F[O2]): Stream[F,O2] =
      self.flatMap(o => Stream.eval(f(o)))

    /** Alias for [[pipe.evalScan]]. */
    def evalScan[O2](z: O2)(f: (O2, O) => F[O2]): Stream[F,O2] =
      self through pipe.evalScan(z)(f)

    def flatMap[O2](f: O => Stream[F,O2]): Stream[F,O2] =
      Stream.fromFree(Algebra.uncons(self.get[F,O]).flatMap {
        case None => Stream.empty[F,O2].get
        case Some((hd, tl)) =>
          val tl2 = Stream.fromFree(tl).flatMap(f)
          (hd.map(f).foldRightLazy(tl2)(Stream.append(_,_))).get
      })

    /** Defined as `s >> s2 == s flatMap { _ => s2 }`. */
    def >>[O2](s2: => Stream[F,O2]): Stream[F,O2] =
      flatMap { _ => s2 }

    // /** Folds this stream with the monoid for `O`. */
    // def foldMonoid(implicit O: Monoid[O]): Stream[F,O] = self.fold(O.empty)(O.combine)

    def interleave[O2>:O](s2: Stream[F,O2]): Stream[F,O2] =
      (self through2 s2)(pipe2.interleave)

    def interleaveAll[O2>:O](s2: Stream[F,O2]): Stream[F,O2] =
      (this through2 s2)(pipe2.interleaveAll)

    /** Alias for `(haltWhenTrue through2 this)(pipe2.interrupt)`. */
    def interruptWhen(haltWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      (haltWhenTrue through2 self)(pipe2.interrupt)

    /** Alias for `(haltWhenTrue.discrete through2 this)(pipe2.interrupt)`. */
    def interruptWhen(haltWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      (haltWhenTrue.discrete through2 self)(pipe2.interrupt)

    /** Alias for `Stream.join(maxOpen)(self)`. */
    def join[O2](maxOpen: Int)(implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      Stream.join(maxOpen)(self.asInstanceOf[Stream[F,Stream[F,O2]]])

    /** Alias for `Stream.joinUnbounded(self)`. */
    def joinUnbounded[O2](implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      Stream.joinUnbounded(self.asInstanceOf[Stream[F,Stream[F,O2]]])

    def merge[O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      through2(s2)(pipe2.merge)

    def mergeHaltBoth[O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      through2(s2)(pipe2.mergeHaltBoth)

    def mergeHaltL[O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      through2(s2)(pipe2.mergeHaltL)

    def mergeHaltR[O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      through2(s2)(pipe2.mergeHaltR)

    def mergeDrainL[O2](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      through2(s2)(pipe2.mergeDrainL)

    def mergeDrainR[O2](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      through2(s2)(pipe2.mergeDrainR)

    def observe[O2>:O](sink: Sink[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      pipe.observe(self)(sink)

    def observeAsync[O2>:O](sink: Sink[F,O2], maxQueued: Int)(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      pipe.observeAsync(self, maxQueued)(sink)

    /** Run `s2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
    def onComplete[O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      (self onError (e => s2 ++ Stream.fail(e))) ++ s2

    /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
    def onError[O2>:O](h: Throwable => Stream[F,O2]): Stream[F,O2] =
      Stream.fromFree(self.get[F,O2] onError { e => h(e).get })

    def onFinalize(f: F[Unit])(implicit F: Applicative[F]): Stream[F,O] =
      Stream.bracket(F.pure(()))(_ => self, _ => f)

    /** Alias for `(pauseWhenTrue through2 this)(pipe2.pause)`. */
    def pauseWhen(pauseWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      (pauseWhenTrue through2 self)(pipe2.pause)

    /** Alias for `(pauseWhenTrue.discrete through2 this)(pipe2.pause)`. */
    def pauseWhen(pauseWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      (pauseWhenTrue.discrete through2 self)(pipe2.pause)

    /** Alias for `self through [[pipe.prefetch]](f).` */
    def prefetch(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      self through pipe.prefetch

    def pull: Stream.ToPull[F,O] = new Stream.ToPull(self.free)

    def pull2[O2,O3](s2: Stream[F,O2])(using: (Stream.ToPull[F,O], Stream.ToPull[F,O2]) => Pull[F,O3,Any]): Stream[F,O3] =
      using(pull, s2.pull).stream

    /** Reduces this stream with the Semigroup for `O`. */
    def reduceSemigroup(implicit S: Semigroup[O]): Stream[F, O] =
      self.reduce(S.combine(_, _))

    def repeatPull[O2](using: Stream.ToPull[F,O] => Pull[F,O2,Option[Stream[F,O]]]): Stream[F,O2] =
      Pull.loop(using.andThen(_.map(_.map(_.pull))))(self.pull).stream

    def run(implicit F: Sync[F]): F[Unit] =
      runFold(())((u, _) => u)

    def runFold[B](init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
      Algebra.runFold(self.get, init)(f)

    def runFoldMonoid(implicit F: Sync[F], O: Monoid[O]): F[O] =
      runFold(O.empty)(O.combine)

    def runFoldSemigroup(implicit F: Sync[F], O: Semigroup[O]): F[Option[O]] =
      runFold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    def runLog(implicit F: Sync[F]): F[Vector[O]] = {
      import scala.collection.immutable.VectorBuilder
      F.suspend(F.map(runFold(new VectorBuilder[O])(_ += _))(_.result))
    }

    def runLast(implicit F: Sync[F]): F[Option[O]] =
      self.runFold(Option.empty[O])((_, a) => Some(a))

    /** Transform this stream using the given `Pipe`. */
    def through[O2](f: Pipe[F,O,O2]): Stream[F,O2] = f(self)

    /** Transform this stream using the given pure `Pipe`. */
    def throughPure[O2](f: Pipe[Pure,O,O2]): Stream[F,O2] = f(self)

    /** Transform this stream using the given `Pipe2`. */
    def through2[O2,O3](s2: Stream[F,O2])(f: Pipe2[F,O,O2,O3]): Stream[F,O3] =
      f(self,s2)

    /** Transform this stream using the given pure `Pipe2`. */
    def through2Pure[O2,O3](s2: Stream[F,O2])(f: Pipe2[Pure,O,O2,O3]): Stream[F,O3] =
      f(self,s2)

    /** Applies the given sink to this stream and drains the output. */
    def to(f: Sink[F,O]): Stream[F,Unit] = f(self)

    def translate[G[_]](u: F ~> G)(implicit G: Effect[G]): Stream[G,O] =
      translate_(u, Some(G))

    def translateSync[G[_]](u: F ~> G): Stream[G,O] =
      translate_(u, None)

    private def translate_[G[_]](u: F ~> G, G: Option[Effect[G]]): Stream[G,O] =
      Stream.fromFree[G,O](Algebra.translate[F,G,O,Unit](self.get, u, G))

    // def unconsAsync(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,AsyncPull[F,Option[(Segment[O,Unit], Stream[F,O])]]] =
    //   Pull.fromFree(Algebra.unconsAsync(self.get)).map(_.map(_.map { case (hd, tl) => (hd, Stream.fromFree(tl)) }))

    def zip[O2](s2: Stream[F,O2]): Stream[F,(O,O2)] =
      through2(s2)(pipe2.zip)

    def zipWith[O2,O3](s2: Stream[F,O2])(f: (O,O2) => O3): Stream[F, O3] =
      through2(s2)(pipe2.zipWith(f))
  }

  implicit def StreamPureOps[O](s: Stream[Pure,O]): StreamPureOps[O] = new StreamPureOps(s.get[Pure,O])
  final class StreamPureOps[O](private val free: Free[Algebra[Pure,O,?],Unit]) extends AnyVal {
    private def self: Stream[Pure,O] = Stream.fromFree[Pure,O](free)

    def ++[F[_],O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(covary[F], s2)

    def append[F[_],O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      Stream.append(self.covary[F], s2)

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F,O] = self.asInstanceOf[Stream[F,O]]

    /** Lifts this stream to the specified effect and output types. */
    def covaryAll[F[_],O2>:O]: Stream[F,O2] = self.asInstanceOf[Stream[F,O2]]

    /** Alias for [[pipe2.either]]. */
    def either[F[_],O2](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,Either[O,O2]] =
      covary[F].through2(s2)(pipe2.either)

    def evalMap[F[_],O2](f: O => F[O2]): Stream[F,O2] = covary[F].evalMap(f)

    /** Alias for [[pipe.evalScan]]. */
    def evalScan[F[_],O2](z: O2)(f: (O2, O) => F[O2]): Stream[F,O2] = covary[F].evalScan(z)(f)

    def flatMap[F[_],O2](f: O => Stream[F,O2]): Stream[F,O2] =
      covary[F].flatMap(f)

    /** Defined as `s >> s2 == s flatMap { _ => s2 }`. */
    def >>[F[_],O2](s2: => Stream[F,O2]): Stream[F,O2] =
      flatMap { _ => s2 }

    def interleave[F[_],O2>:O](s2: Stream[F,O2]): Stream[F,O2] =
      (covary[F] through2 s2)(pipe2.interleave)

    def interleaveAll[F[_],O2>:O](s2: Stream[F,O2]): Stream[F,O2] =
      (covary[F] through2 s2)(pipe2.interleaveAll)

    /** Alias for `(haltWhenTrue through2 this)(pipe2.interrupt)`. */
    def interruptWhen[F[_]](haltWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      (haltWhenTrue through2 covary[F])(pipe2.interrupt)

    /** Alias for `(haltWhenTrue.discrete through2 this)(pipe2.interrupt)`. */
    def interruptWhen[F[_]](haltWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      (haltWhenTrue.discrete through2 covary[F])(pipe2.interrupt)

    /** Alias for `Stream.join(maxOpen)(self)`. */
    def join[F[_],O2](maxOpen: Int)(implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      Stream.join(maxOpen)(self.asInstanceOf[Stream[F,Stream[F,O2]]])

    /** Alias for `Stream.joinUnbounded(self)`. */
    def joinUnbounded[F[_],O2](implicit ev: O <:< Stream[F,O2], F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      Stream.joinUnbounded(self.asInstanceOf[Stream[F,Stream[F,O2]]])

    def merge[F[_],O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].through2(s2)(pipe2.merge)

    def mergeHaltBoth[F[_],O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].through2(s2)(pipe2.mergeHaltBoth)

    def mergeHaltL[F[_],O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].through2(s2)(pipe2.mergeHaltL)

    def mergeHaltR[F[_],O2>:O](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].through2(s2)(pipe2.mergeHaltR)

    def mergeDrainL[F[_],O2](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].through2(s2)(pipe2.mergeDrainL)

    def mergeDrainR[F[_],O2](s2: Stream[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].through2(s2)(pipe2.mergeDrainR)

    def observe[F[_],O2>:O](sink: Sink[F,O2])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].observe(sink)

    def observeAsync[F[_],O2>:O](sink: Sink[F,O2], maxQueued: Int)(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O2] =
      covary[F].observeAsync(sink, maxQueued)

    /** Run `s2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
    def onComplete[F[_],O2>:O](s2: => Stream[F,O2]): Stream[F,O2] =
      covary[F].onComplete(s2)

    /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
    def onError[F[_],O2>:O](h: Throwable => Stream[F,O2]): Stream[F,O2] =
      covary[F].onError(h)

    def onFinalize[F[_]](f: F[Unit])(implicit F: Applicative[F]): Stream[F,O] =
      covary[F].onFinalize(f)

    /** Alias for `(pauseWhenTrue through2 this)(pipe2.pause)`. */
    def pauseWhen[F[_]](pauseWhenTrue: Stream[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].pauseWhen(pauseWhenTrue)

    /** Alias for `(pauseWhenTrue.discrete through2 this)(pipe2.pause)`. */
    def pauseWhen[F[_]](pauseWhenTrue: async.immutable.Signal[F,Boolean])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
      covary[F].pauseWhen(pauseWhenTrue)

    def toList: List[O] = covary[IO].runFold(List.empty[O])((b, a) => a :: b).unsafeRunSync.reverse

    def toVector: Vector[O] = covary[IO].runLog.unsafeRunSync

    def zip[F[_],O2](s2: Stream[F,O2]): Stream[F,(O,O2)] =
      covary[F].through2(s2)(pipe2.zip)

    def zipWith[F[_],O2,O3](s2: Stream[F,O2])(f: (O,O2) => O3): Stream[F, O3] =
      covary[F].through2(s2)(pipe2.zipWith(f))
  }

  final class ToPull[F[_],O] private[Stream] (private val free: Free[Algebra[Nothing,Nothing,?],Unit]) extends AnyVal {

    private def self: Stream[F,O] = Stream.fromFree(free.asInstanceOf[Free[Algebra[F,O,?],Unit]])

    /**
     * Waits for a segment of elements to be available in the source stream.
     * The segment of elements along with a new stream are provided as the resource of the returned pull.
     * The new stream can be used for subsequent operations, like awaiting again.
     * A `None` is returned as the resource of the pull upon reaching the end of the stream.
     */
    def uncons: Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] =
      Pull.fromFree(Algebra.uncons(self.get)).map { _.map { case (hd, tl) => (hd, Stream.fromFree(tl)) } }

    /** Like [[uncons]] but waits for a chunk instead of an entire segment. */
    def unconsChunk: Pull[F,Nothing,Option[(Chunk[O],Stream[F,O])]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd,tl)) => hd.unconsChunk match {
          case Left(()) => tl.pull.unconsChunk
          case Right((c,tl2)) => Pull.pure(Some((c, tl.cons(tl2))))
        }
      }

    /** Like [[uncons]] but waits for a single element instead of an entire segment. */
    def uncons1: Pull[F,Nothing,Option[(O,Stream[F,O])]] =
      uncons flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) => hd.uncons1 match {
          case Left(_) => tl.pull.uncons1
          case Right((hd,tl2)) => Pull.pure(Some(hd -> tl.cons(tl2)))
        }
      }

    def unconsAsync(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,AsyncPull[F,Option[(Segment[O,Unit], Stream[F,O])]]] =
      Pull.fromFree(Algebra.unconsAsync(self.get)).map(_.map(_.map { case (hd, tl) => (hd, Stream.fromFree(tl)) }))

    // def unconsChunkAsync(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,AsyncPull[F,Option[(Chunk[O], Stream[F,O])]]] =
    //   unconsAsync.map { ap =>
    //     ap.map {
    //       case None => None
    //       case Some((hd,tl)) =>
    //         hd.unconsChunk match {
    //           case Left(()) => sys.error("FS2-bug; unconsChunk after uncons is guaranteed to produce at least 1 value")
    //           case Right((hd,tl2)) => Some(hd -> tl.cons(tl2))
    //         }
    //     }
    //   }
    //
    // def uncons1Async(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,AsyncPull[F,Option[(O, Stream[F,O])]]] =
    //   unconsAsync.map { ap =>
    //     ap.map {
    //       case None => None
    //       case Some((hd,tl)) =>
    //         hd.uncons1 match {
    //           case Left(()) => sys.error("FS2-bug; uncons1 after uncons is guaranteed to produce at least 1 value")
    //           case Right((hd,tl2)) => Some(hd -> tl.cons(tl2))
    //         }
    //     }
    //   }

    /**
     * Like [[uncons]], but returns a segment of no more than `n` elements.
     *
     * The returned segment has a result tuple consisting of the remaining limit
     * (`n` minus the segment size, or 0, whichever is larger) and the remainder
     * of the source stream.
     *
     * `Pull.pure(None)` is returned if the end of the source stream is reached.
     */
    def unconsLimit(n: Long): Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] = {
      require(n > 0)
      uncons.flatMapOpt { case (hd,tl) =>
        val (segments, cnt, result) = hd.splitAt(n)
        val out = Segment.catenated(segments).getOrElse(Segment.empty)
        val rest = result match {
          case Left(()) => tl
          case Right(tl2) => tl.cons(tl2)
        }
        Pull.pure(Some((out,rest)))
      }
    }

    def unconsN(n: Long, allowFewer: Boolean = false): Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] = {
      def go(acc: Catenable[Segment[O,Unit]], n: Long, s: Stream[F,O]): Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] = {
        s.pull.uncons.flatMap {
          case None =>
            if (allowFewer && acc.nonEmpty) Pull.pure(Some((Segment.catenated(acc).getOrElse(Segment.empty), Stream.empty)))
            else Pull.pure(None)
          case Some((hd,tl)) =>
            val (segments, cnt, result) = hd.splitAt(n)
            val rest = result match {
              case Left(()) => tl
              case Right(tl2) => tl.cons(tl2)
            }
            if (cnt >= n) Pull.pure(Some((Segment.catenated(acc ++ segments).getOrElse(Segment.empty), rest)))
            else go(acc ++ segments, n - cnt, rest)
        }
      }
      if (n <= 0) Pull.pure(Some((Segment.empty, self)))
      else go(Catenable.empty, n, self)
    }

    /** Drops the first `n` elements of this `Stream`, and returns the new `Stream`. */
    def drop(n: Long): Pull[F,Nothing,Option[Stream[F,O]]] =
      if (n <= 0) Pull.pure(Some(self))
      else uncons.flatMapOpt { case (hd,tl) =>
        val (segments, count, result) = hd.splitAt(n)
        val rest = result match {
          case Left(()) => tl
          case Right(tl2) => tl.cons(tl2)
        }
        if (count >= n) Pull.pure(Some(rest)) else rest.pull.drop(n - count)
    }

    /** Like [[dropWhile]], but drops the first value which tests false. */
    def dropThrough(p: O => Boolean): Pull[F,Nothing,Option[Stream[F,O]]] =
      dropWhile_(p, true)

    /**
     * Drops elements of the this stream until the predicate `p` fails, and returns the new stream.
     * If defined, the first element of the returned stream will fail `p`.
     */
    def dropWhile(p: O => Boolean): Pull[F,Nothing,Option[Stream[F,O]]] =
      dropWhile_(p, false)

    def dropWhile_(p: O => Boolean, emitFailure: Boolean): Pull[F,Nothing,Option[Stream[F,O]]] =
      receive { (hd, tl) =>
        val (segments, unfinished, result) = hd.splitWhile(p, emitFailure)
        val rest = result match {
          case Left(()) => tl
          case Right(tl2) => tl.cons(tl2)
        }
        if (unfinished) rest.pull.dropWhile_(p, emitFailure) else Pull.pure(Some(rest))
      }

    /** Writes all inputs to the output of the returned `Pull`. */
    def echo: Pull[F,O,Unit] = Pull.loop[F,O,Stream[F,O]](_.pull.echoSegment)(self)

    /** Reads a single element from the input and emits it to the output. Returns the new `Handle`. */
    def echo1: Pull[F,O,Option[Stream[F,O]]] =
      receive1 { (hd, tl) => Pull.output1(hd).as(Some(tl)) }

    /** Reads the next available segment from the input and emits it to the output. Returns the new `Handle`. */
    def echoSegment: Pull[F,O,Option[Stream[F,O]]] =
      receive { (hd, tl) => Pull.output(hd).as(Some(tl)) }

    /** Like `[[unconsN]]`, but leaves the buffered input unconsumed. */
    def fetchN(n: Int): Pull[F,Nothing,Option[Stream[F,O]]] =
      unconsN(n).map { _.map { case (hd, tl) => tl.cons(hd) } }

    /** Awaits the next available element where the predicate returns true. */
    def find(f: O => Boolean): Pull[F,Nothing,Option[(O,Stream[F,O])]] =
      receiveChunk { (hd, tl) =>
        hd.indexWhere(f) match {
          case None => tl.pull.find(f)
          case Some(idx) if idx + 1 < hd.size => Pull.pure(Some((hd(idx), tl.cons(hd.drop(idx + 1).voidResult))))
          case Some(idx) => Pull.pure(Some((hd(idx), tl)))
        }
      }

    /**
     * Folds all inputs using an initial value `z` and supplied binary operator, and writes the final
     * result to the output of the supplied `Pull` when the stream has no more values.
     */
    def fold[O2](z: O2)(f: (O2, O) => O2): Pull[F,Nothing,O2] =
      receiveOption {
        case None => Pull.pure(z)
        case Some((hd,tl)) => tl.pull.fold(hd.fold(z)(f).run)(f)
      }

    /**
     * Folds all inputs using the supplied binary operator, and writes the final result to the output of
     * the supplied `Pull` when the stream has no more values.
     */
    def fold1[O2 >: O](f: (O2, O2) => O2): Pull[F,Nothing,Option[O2]] =
      receive1Option {
        case None => Pull.pure(None)
        case Some((hd,tl)) => tl.pull.fold(hd: O2)(f).map(Some(_))
      }

    /** Writes a single `true` value if all input matches the predicate, `false` otherwise. */
    def forall(p: O => Boolean): Pull[F,Nothing,Boolean] = {
      receiveOption {
        case None => Pull.pure(true)
        case Some((hd,tl)) =>
          Pull.segment(hd.takeWhile(p).drain).flatMap {
            case Right(()) => tl.pull.forall(p)
            case Left(_) => Pull.pure(false)
          }
      }
    }

    /** Returns the last element of the input, if non-empty. */
    def last: Pull[F,Nothing,Option[O]] = {
      def go(prev: Option[O], s: Stream[F,O]): Pull[F,Nothing,Option[O]] =
        s.pull.receiveOption {
          case None => Pull.pure(prev)
          case Some((hd,tl)) => Pull.segment(hd.fold(prev)((_,o) => Some(o))).flatMap(go(_,tl))
        }
      go(None, self)
    }

    /** Like [[receive]] but does not consume the segment (i.e., the segment is pushed back). */
    def peek: Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] =
      receive { (hd, tl) => Pull.pure(Some((hd, tl.cons(hd)))) }

    /** Like [[receive]] but does not consume the element (i.e., the element is pushed back). */
    def peek1: Pull[F,Nothing,Option[(O,Stream[F,O])]] =
      receive1 { (hd, tl) => Pull.pure(Some((hd, tl.cons1(hd)))) }

    /**
     * Like [[uncons]], but runs the `uncons` asynchronously. A `flatMap` into
     * inner `Pull` logically blocks until this await completes.
     */
    def prefetch(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,Pull[F,Nothing,Option[Stream[F,O]]]] =
      unconsAsync.map { _.pull.map { _.map { case (hd, h) => h cons hd } } }

    /** Apply `f` to the next available `Segment`. */
    def receive[O2,R](f: (Segment[O,Unit],Stream[F,O]) => Pull[F,O2,Option[R]]): Pull[F,O2,Option[R]] =
      uncons.flatMapOpt { case (hd, tl) => f(hd, tl) }

    /** Apply `f` to the next available `Chunk`. */
    def receiveChunk[O2,R](f: (Chunk[O],Stream[F,O]) => Pull[F,O2,Option[R]]): Pull[F,O2,Option[R]] =
      unconsChunk.flatMapOpt { case (hd, tl) => f(hd, tl) }

    /** Apply `f` to the next available element. */
    def receive1[O2,R](f: (O,Stream[F,O]) => Pull[F,O2,Option[R]]): Pull[F,O2,Option[R]] =
      uncons1.flatMapOpt { case (hd, tl) => f(hd, tl) }

    /** Apply `f` to the next available segment, or `None` if the input is exhausted. */
    def receiveOption[O2,R](f: Option[(Segment[O,Unit],Stream[F,O])] => Pull[F,O2,R]): Pull[F,O2,R] =
      uncons.flatMap(f)

    /** Apply `f` to the next available chunk, or `None` if the input is exhausted. */
    def receiveChunkOption[O2,R](f: Option[(Chunk[O],Stream[F,O])] => Pull[F,O2,R]): Pull[F,O2,R] =
      unconsChunk.flatMap(f)

    /** Apply `f` to the next available element, or `None` if the input is exhausted. */
    def receive1Option[O2,R](f: Option[(O,Stream[F,O])] => Pull[F,O2,R]): Pull[F,O2,R] =
      uncons1.flatMap(f)

    /** Emits the first `n` elements of the input. */
    def take(n: Long): Pull[F,O,Option[Stream[F,O]]] =
      if (n <= 0) Pull.pure(None)
      else uncons.flatMapOpt {
        case (hd,tl) =>
          val (segments, count, result) = hd.splitAt(n)
          val out = Segment.catenated(segments).map(Pull.output).getOrElse(Pull.pure(()))
          val rest = result match {
            case Left(()) => tl
            case Right(tl2) => tl.cons(tl2)
          }
          out >> (if (count >= n) Pull.pure(Some(rest)) else rest.pull.take(n - count))
      }

    /** Emits the last `n` elements of the input. */
    def takeRight(n: Long): Pull[F,Nothing,Chunk[O]]  = {
      def go(acc: Vector[O], s: Stream[F,O]): Pull[F,Nothing,Chunk[O]] = {
        s.pull.unconsN(n, true).flatMap {
          case None => Pull.pure(Chunk.vector(acc))
          case Some((hd, tl)) =>
            val vector = hd.toVector
            go(acc.drop(vector.length) ++ vector, tl)
        }
      }
      if (n <= 0) Pull.pure(Chunk.empty)
      else go(Vector.empty, self)
    }

    /** Like [[takeWhile]], but emits the first value which tests false. */
    def takeThrough(p: O => Boolean): Pull[F,O,Option[Stream[F,O]]] = takeWhile_(p, true)

    /**
     * Emits the elements of the stream until the predicate `p` fails,
     * and returns the remaining `Stream`. If non-empty, the returned stream will have
     * a first element `i` for which `p(i)` is `false`. */
    def takeWhile(p: O => Boolean): Pull[F,O,Option[Stream[F,O]]] = takeWhile_(p, false)

    def takeWhile_(p: O => Boolean, emitFailure: Boolean): Pull[F,O,Option[Stream[F,O]]] =
      receive { (hd, tl) =>
        val (segments, unfinished, result) = hd.splitWhile(p, emitFailure = emitFailure)
        val rest = result match {
          case Left(()) => tl
          case Right(tl2) => tl.cons(tl2)
        }
        Segment.catenated(segments).map(Pull.output).getOrElse(Pull.pure(())) >> (if (unfinished) rest.pull.takeWhile_(p, emitFailure) else Pull.pure(Some(rest)))
      }
  }

  /** Provides operations on effectful pipes for syntactic convenience. */
  implicit class PipeOps[F[_],I,O](private val self: Pipe[F,I,O]) extends AnyVal {

    /** Transforms the left input of the given `Pipe2` using a `Pipe`. */
    def attachL[I1,O2](p: Pipe2[F,O,I1,O2]): Pipe2[F,I,I1,O2] =
      (l, r) => p(self(l), r)

    /** Transforms the right input of the given `Pipe2` using a `Pipe`. */
    def attachR[I0,O2](p: Pipe2[F,I0,O,O2]): Pipe2[F,I0,I,O2] =
      (l, r) => p(l, self(r))
  }

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit class PurePipeOps[I,O](private val self: Pipe[Pure,I,O]) extends AnyVal {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe[F,I,O] = pipe.covary[F,I,O](self)
  }

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit class PurePipe2Ops[I,I2,O](private val self: Pipe2[Pure,I,I2,O]) extends AnyVal {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe2[F,I,I2,O] = pipe2.covary[F,I,I2,O](self)
  }

  implicit def covaryPure[F[_],O,O2>:O](s: Stream[Pure,O]): Stream[F,O2] = s.covaryAll[F,O2]

  implicit def covaryPurePipe[F[_],I,O](p: Pipe[Pure,I,O]): Pipe[F,I,O] = p.covary[F]

  implicit def covaryPurePipe2[F[_],I,I2,O](p: Pipe2[Pure,I,I2,O]): Pipe2[F,I,I2,O] = p.covary[F]
}
