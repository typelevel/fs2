package fs2.fast

import fs2.{ Pure }

import scala.concurrent.ExecutionContext

import cats.{ Applicative, MonadError, Monoid, Semigroup }
import cats.effect.{ Effect, IO, Sync }

import fs2.util.UF1
import fs2.fast.internal.{ Algebra, Free }

/**
 * A stream producing output of type `O` and which may evaluate `F`
 * effects. If `F` is `Nothing` or `[[fs2.Pure]]`, the stream is pure.
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

  def ++[F2[x]>:F[x],O2>:O](s2: => Stream[F2,O2]): Stream[F2,O2] =
    Stream.append(this, s2)

  def append[F2[x]>:F[x],O2>:O](s2: => Stream[F2,O2]): Stream[F2,O2] =
    Stream.append(this, s2)

  def attempt: Stream[F,Either[Throwable,O]] =
    map(Right(_)).onError(e => Stream.emit(Left(e)))

  /** `s as x == s map (_ => x)` */
  def as[O2](o2: O2): Stream[F,O2] = map(_ => o2)

  // /** Alias for `self through [[pipe.buffer]]`. */
  // def buffer(n: Int): Stream[F,O] = self through pipe.buffer(n)
  //
  // /** Alias for `self through [[pipe.bufferAll]]`. */
  // def bufferAll: Stream[F,O] = self through pipe.bufferAll
  //
  // /** Alias for `self through [[pipe.bufferBy]]`. */
  // def bufferBy(f: O => Boolean): Stream[F,O] = self through pipe.bufferBy(f)
  //
  // /** Alias for `self through [[pipe.changesBy]]`. */
  // def changesBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F,O] = self through pipe.changesBy(f)
  //
  // /** Alias for `self through [[pipe.chunkLimit]]`. */
  // def chunkLimit(n: Int): Stream[F,NonEmptyChunk[O]] = self through pipe.chunkLimit(n)
  //
  // /** Alias for `self through [[pipe.chunkN]]`. */
  // def chunkN(n: Int, allowFewer: Boolean = true): Stream[F,List[NonEmptyChunk[O]]] =
  //   self through pipe.chunkN(n, allowFewer)
  //
  // /** Alias for `self through [[pipe.chunks]]`. */
  // def chunks: Stream[F,NonEmptyChunk[O]] = self through pipe.chunks
  //
  // /** Alias for `self through [[pipe.collect]]`. */
  // def collect[O2](pf: PartialFunction[O, O2]) = self through pipe.collect(pf)
  //
  // /** Alias for `self through [[pipe.collectFirst]]`. */
  // def collectFirst[O2](pf: PartialFunction[O, O2]) = self through pipe.collectFirst(pf)
  //
  // /** Prepend a single chunk onto the front of this stream. */
  // def cons[O2>:O](c: Chunk[O2])(implicit T: RealSupertype[O,O2]): Stream[F,O2] =
  //   Stream.cons[F,O2](self)(c)

  /** Prepend a single segment onto the front of this stream. */
  def cons[O2>:O](s: Segment[O2,Unit]): Stream[F,O2] =
    Stream.segment(s) ++ this

  /** Prepend a single chunk onto the front of this stream. */
  def consChunk[O2>:O](c: Chunk[O2]): Stream[F,O2] =
    if (c.isEmpty) this else Stream.chunk(c) ++ this

  /** Prepend a single value onto the front of this stream. */
  def cons1[O2>:O](o: O2): Stream[F,O2] =
    cons(Segment.singleton(o))

  /** Lifts this stream to the specified effect type. */
  def covary[F2[x]>:F[x]]: Stream[F2,O] = this.asInstanceOf[Stream[F2,O]]

  /** Lifts this stream to the specified output type. */
  def covaryOutput[O2>:O]: Stream[F,O2] = this.asInstanceOf[Stream[F,O2]]

  /** Lifts this stream to the specified effect and output types. */
  def covaryAll[F2[x]>:F[x],O2>:O]: Stream[F2,O2] = this.asInstanceOf[Stream[F2,O2]]

  // /** Alias for `self through [[pipe.delete]]`. */
  // def delete(f: O => Boolean): Stream[F,O] = self through pipe.delete(f)

  /**
   * Removes all output values from this stream.
   * For example, `Stream.eval(IO(println("x"))).drain.runLog`
   * will, when `unsafeRunSync` in called, print "x" but return `Vector()`.
   */
  def drain: Stream[F, Nothing] = flatMap { _ => Stream.empty }

  def drop(n: Long): Stream[F,O] = {
    def go(s: Stream[F,O], n: Long): Pull[F,O,Unit] = s.uncons flatMap {
      case None => Pull.pure(())
      case Some((hd, tl)) =>
        Pull.segment(hd.drop(n)) flatMap {
          case (n, ()) =>
            if (n > 0) go(tl, n)
            else Pull.fromFree(tl.get)
        }
    }
    go(this, n).close
  }

  // /** Alias for `self through [[pipe.dropLast]]`. */
  // def dropLast: Stream[F,O] = self through pipe.dropLast
  //
  // /** Alias for `self through [[pipe.dropLastIf]]`. */
  // def dropLastIf(p: O => Boolean): Stream[F,O] = self through pipe.dropLastIf(p)
  //
  // /** Alias for `self through [[pipe.dropRight]]`. */
  // def dropRight(n: Int): Stream[F,O] = self through pipe.dropRight(n)
  //
  // /** Alias for `self through [[pipe.dropWhile]]` */
  // def dropWhile(p: O => Boolean): Stream[F,O] = self through pipe.dropWhile(p)

  /** Alias for `(this through2v s2)(pipe2.either)`. */
  def either[F2[x]>:F[x],O2](s2: Stream[F2,O2])(implicit F: Effect[F2], ec: ExecutionContext): Stream[F2,Either[O,O2]] =
    (covary[F2] through2v s2)(pipe2.either)

  def evalMap[F2[x]>:F[x],O2](f: O => F2[O2]): Stream[F2,O2] =
    flatMap(o => Stream.eval(f(o)))

  // /** Alias for `self throughv [[pipe.evalScan]](z)(f)`. */
  // def evalScan[F2[_], O2](z: O2)(f: (O2, O) => F2[O2])(implicit S: Sub1[F, F2]): Stream[F2, O2] =  self throughv pipe.evalScan(z)(f)
  //
  // /** Alias for `self through [[pipe.exists]]`. */
  // def exists(f: O => Boolean): Stream[F, Boolean] = self through pipe.exists(f)

  def flatMap[F2[x]>:F[x],O2](f: O => Stream[F2,O2]): Stream[F2,O2] =
    Stream.fromFree(Algebra.uncons(get[F2,O]).flatMap {
      case None => Stream.empty[F2,O2].get
      case Some((hd, tl)) =>
        val tl2 = Stream.fromFree(tl).flatMap(f)
        (hd.map(f).foldRightLazy(tl2)(Stream.append(_,_))).get
    })

  /** Defined as `s >> s2 == s flatMap { _ => s2 }`. */
  def >>[F2[x]>:F[x],O2](s2: => Stream[F2,O2]): Stream[F2,O2] =
    this flatMap { _ => s2 }

  // /** Alias for `self through [[pipe.filter]]`. */
  // def filter(f: O => Boolean): Stream[F,O] = self through pipe.filter(f)

  // /** Alias for `self through [[pipe.filterWithPrevious]]`. */
  // def filterWithPrevious(f: (O, O) => Boolean): Stream[F,O] = self through pipe.filterWithPrevious(f)

  // /** Alias for `self through [[pipe.find]]`. */
  // def find(f: O => Boolean): Stream[F,O] = self through pipe.find(f)

  // /** Alias for `self through [[pipe.fold]](z)(f)`. */
  // def fold[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self through pipe.fold(z)(f)

  // /** Alias for `self through [[pipe.fold1]](f)`. */
  // def fold1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.fold1(f)

  // /** Alias for `map(f).foldMonoid`. */
  // def foldMap[O2](f: O => O2)(implicit O2: Monoid[O2]): Stream[F,O2] = fold(O2.empty)((acc, o) => O2.combine(acc, f(o)))

  // /** Alias for `self through [[pipe.forall]]`. */
  // def forall(f: O => Boolean): Stream[F, Boolean] = self through pipe.forall(f)

  // /** Alias for `self through [[pipe.groupBy]]`. */
  // def groupBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, (O2, Vector[O])] = self through pipe.groupBy(f)

  // /** Alias for `self through [[pipe.head]]`. */
  // def head: Stream[F,O] = self through pipe.head

  // def interleave[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R:RealSupertype[O,O2], S:Sub1[F,F2]): Stream[F2,O2] =
  //   (self through2v s2)(pipe2.interleave)

  // def interleaveAll[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R:RealSupertype[O,O2], S:Sub1[F,F2]): Stream[F2,O2] =
  //   (self through2v s2)(pipe2.interleaveAll)

  // /** Alias for `(haltWhenTrue through2 this)(pipe2.interrupt)`. */
  // def interruptWhen[F2[_]](haltWhenTrue: Stream[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
  //   (haltWhenTrue through2 Sub1.substStream(self))(pipe2.interrupt)

  // /** Alias for `(haltWhenTrue.discrete through2 this)(pipe2.interrupt)`. */
  // def interruptWhen[F2[_]](haltWhenTrue: async.immutable.Signal[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
  //   (haltWhenTrue.discrete through2 Sub1.substStream(self))(pipe2.interrupt)

  // /** Alias for `self through [[pipe.intersperse]]`. */
  // def intersperse[O2 >: O](separator: O2): Stream[F,O2] = self through pipe.intersperse(separator)

  // /** Alias for `self through [[pipe.last]]`. */
  // def last: Stream[F,Option[O]] = self through pipe.last

  // /** Alias for `self through [[pipe.lastOr]]`. */
  // def lastOr[O2 >: O](li: => O2): Stream[F,O2] = self through pipe.lastOr(li)

  // /** Alias for `self through [[pipe.mapAccumulate]]` */
  // def mapAccumulate[S,O2](init: S)(f: (S, O) => (S, O2)): Stream[F, (S, O2)] =
  //   self through pipe.mapAccumulate(init)(f)

  def map[O2](f: O => O2): Stream[F,O2] = {
    def go(s: Stream[F,O]): Pull[F,O2,Unit] = s.uncons flatMap {
      case None => Pull.pure(())
      case Some((hd, tl)) => Pull.segment(hd map f) >> go(tl)
    }
    go(this).close
  }

  // def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream[F,O2] =
  //   Stream.mk(get mapChunks f)

  def mask: Stream[F,O] = onError(_ => Stream.empty)

  def merge[F2[x]>:F[x],O2>:O](s2: Stream[F2,O2])(implicit F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
    through2v(s2)(pipe2.merge)

  def mergeHaltBoth[F2[x]>:F[x],O2>:O](s2: Stream[F2,O2])(implicit F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
    through2v(s2)(pipe2.mergeHaltBoth)

  // def mergeHaltL[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
  //   (self through2v s2)(pipe2.mergeHaltL)
  //
  // def mergeHaltR[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
  //   (self through2v s2)(pipe2.mergeHaltR)
  //
  // def mergeDrainL[F2[_],O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
  //   (self through2v s2)(pipe2.mergeDrainL)
  //
  // def mergeDrainR[F2[_],O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
  //   (self through2v s2)(pipe2.mergeDrainR)

  def noneTerminate: Stream[F,Option[O]] = map(Some(_)) ++ Stream.emit(None)

  // def observe[F2[_],O2>:O](sink: Sink[F2,O2])(implicit F: Effect[F2], R: RealSupertype[O,O2], S: Sub1[F,F2], ec: ExecutionContext): Stream[F2,O2] =
  //   pipe.observe(Sub1.substStream(self)(S))(sink)
  //
  // def observeAsync[F2[_],O2>:O](sink: Sink[F2,O2], maxQueued: Int)(implicit F: Effect[F2], R: RealSupertype[O,O2], S: Sub1[F,F2], ec: ExecutionContext): Stream[F2,O2] =
  //   pipe.observeAsync(Sub1.substStream(self)(S), maxQueued)(sink)

  /** Run `s2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x]>:F[x],O2>:O](s2: => Stream[F2,O2]): Stream[F2,O2] =
    (this onError (e => s2 ++ Stream.fail(e))) ++ s2

  /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
  def onError[F2[x]>:F[x],O2>:O](h: Throwable => Stream[F2,O2]): Stream[F2,O2] =
    Stream.fromFree(get[F2,O2] onError { e => h(e).get })

  def onFinalize[F2[x]>:F[x]](f: F2[Unit])(F2: Applicative[F2]): Stream[F2,O] =
    Stream.bracket(F2.pure(()))(_ => this, _ => f)

  def open: Pull[F,Nothing,Handle[F,O]] = Pull.pure(new Handle(Nil, this))

  // def output: Pull[F,O,Unit] = Pull.outputs(self)

  // /** Alias for `(pauseWhenTrue through2 this)(pipe2.pause)`. */
  // def pauseWhen[F2[_]](pauseWhenTrue: Stream[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
  //   (pauseWhenTrue through2 Sub1.substStream(self))(pipe2.pause[F2,O])
  //
  // /** Alias for `(pauseWhenTrue.discrete through2 this)(pipe2.pause)`. */
  // def pauseWhen[F2[_]](pauseWhenTrue: async.immutable.Signal[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
  //   (pauseWhenTrue.discrete through2 Sub1.substStream(self))(pipe2.pause)

  // /** Alias for `self through [[pipe.prefetch]](f).` */
  // def prefetch[F2[_]](implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
  //   Sub1.substStream(self)(S) through pipe.prefetch

  def pull[F2[x]>:F[x],O2](using: Handle[F,O] => Pull[F2,O2,Any]) : Stream[F2,O2] =
    open.flatMap(using).close

  /** Repeat this stream an infinite number of times. `s.repeat == s ++ s ++ s ++ ...` */
  def repeat: Stream[F,O] = this ++ repeat

  // /** Alias for `self through [[pipe.rechunkN]](f).` */
  // def rechunkN(n: Int, allowFewer: Boolean = true): Stream[F,O] = self through pipe.rechunkN(n, allowFewer)
  //
  // /** Alias for `self through [[pipe.reduce]](z)(f)`. */
  // def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.reduce(f)
  //
  // /** Alias for `self through [[pipe.scan]](z)(f)`. */
  // def scan[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self through pipe.scan(z)(f)
  //
  // /** Alias for `self through [[pipe.scan1]](f)`. */
  // def scan1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.scan1(f)
  //
  // /**
  //  * Used in conjunction with `[[Stream.uncons]]` or `[[Stream.uncons1]]`.
  //  * When `s.scope` starts, the set of live resources is recorded.
  //  * When `s.scope` finishes, any newly allocated resources since the start of `s`
  //  * are all cleaned up.
  //  */
  // def scope: Stream[F,O] =
  //   Stream.mk { StreamCore.scope { self.get } }
  //
  // /** Alias for `self through [[pipe.shiftRight]]`. */
  // def shiftRight[O2 >: O](head: O2*): Stream[F,O2] = self through pipe.shiftRight(head: _*)
  //
  // /** Alias for `self through [[pipe.sliding]]`. */
  // def sliding(n: Int): Stream[F,Vector[O]] = self through pipe.sliding(n)
  //
  // /** Alias for `self through [[pipe.split]]`. */
  // def split(f: O => Boolean): Stream[F,Vector[O]] = self through pipe.split(f)
  //
  // /** Alias for `self through [[pipe.tail]]`. */
  // def tail: Stream[F,O] = self through pipe.tail

  def take(n: Long): Stream[F,O] = pull(_.take(n))

  // /** Alias for `self through [[pipe.takeRight]]`. */
  // def takeRight(n: Long): Stream[F,O] = self through pipe.takeRight(n)
  //
  // /** Alias for `self through [[pipe.takeThrough]]`. */
  // def takeThrough(p: O => Boolean): Stream[F,O] = self through pipe.takeThrough(p)
  //
  // /** Alias for `self through [[pipe.takeWhile]]`. */
  // def takeWhile(p: O => Boolean): Stream[F,O] = self through pipe.takeWhile(p)
  //
  // /** Like `through`, but the specified `Pipe`'s effect may be a supertype of `F`. */
  // def throughv[F2[_],O2](f: Pipe[F2,O,O2])(implicit S: Sub1[F,F2]): Stream[F2,O2] =
  //   f(Sub1.substStream(self))

  /** Like `through2`, but the specified `Pipe2`'s effect may be a supertype of `F`. */
  def through2v[F2[x]>:F[x],O2,O3](s2: Stream[F2,O2])(f: Pipe2[F2,O,O2,O3]): Stream[F2,O3] =
    f(this, s2)

  // /** Like `to`, but the specified `Sink`'s effect may be a supertype of `F`. */
  // def tov[F2[_]](f: Sink[F2,O])(implicit S: Sub1[F,F2]): Stream[F2,Unit] =
  //   f(Sub1.substStream(self))

  // /** Alias for `self through [[pipe.unchunk]]`. */
  // def unchunk: Stream[F,O] = self through pipe.unchunk

  /** Return leading `Segment[O,Unit]` emitted by this `Stream`. */
  def uncons: Pull[F,Nothing,Option[(Segment[O,Unit],Stream[F,O])]] =
    Pull.fromFree(Algebra.uncons(get)).map { _.map { case (hd, tl) => (hd, Stream.fromFree(tl)) } }

  // /**
  // * A new [[Stream]] of one element containing the head element of this [[Stream]] along
  // * with a reference to the remaining [[Stream]] after evaluation of the first element.
  // *
  // * {{{
  // *   scala> Stream(1,2,3).uncons1.toList
  // *   res1: List[Option[(Int, Stream[Nothing, Int])]] = List(Some((1,append(Segment(Emit(Chunk(2, 3))), Segments()))))
  // * }}}
  // *
  // * You can use this to implement any stateful stream function, like `take`:
  // *
  // * {{{
  // *   def take[F[_],A](n: Int)(s: Stream[F,A]): Stream[F,A] =
  // *     if (n <= 0) Stream.empty
  // *     else s.uncons1.flatMap {
  // *       case None => Stream.empty
  // *       case Some((hd, tl)) => Stream.emit(hd) ++ take(n-1)(tl)
  // *     }
  // * }}}
  // *
  // * So `uncons` and `uncons1` can be viewed as an alternative to using `Pull`, with
  // * an important caveat: if you use `uncons` or `uncons1`, you are responsible for
  // * telling FS2 when you're done unconsing the stream, which you do using `[[Stream.scope]]`.
  // *
  // * For instance, the above definition of `take` doesn't call `scope`, so any finalizers
  // * attached won't be run until the very end of any enclosing `scope` or `Pull`
  // * (or the end of the stream if there is no enclosing scope). So in the following code:
  // *
  // * {{{
  // *    take(2)(Stream(1,2,3).onFinalize(Task.delay(println("done"))) ++
  // *    anotherStream
  // * }}}
  // *
  // * The "done" would not be printed until the end of `anotherStream`. To get the
  // * prompt finalization behavior, we would have to do:
  // *
  // * {{{
  // *    take(2)(Stream(1,2,3).onFinalize(Task.delay(println("done"))).scope ++
  // *    anotherStream
  // * }}}
  // *
  // * Note the call to `scope` after the completion of `take`, which ensures that
  // * when that stream completes, any streams which have been opened by the `take`
  // * are deemed closed and their finalizers can be run.
  // */
  // def uncons1: Stream[F, Option[(O,Stream[F,O])]] =

  // def unNone[O2](implicit ev: O <:< Option[O2]): Stream[F, O2] = self.asInstanceOf[Stream[F, Option[O2]]] through pipe.unNone

  // def zip[F2[_],O2](s2: Stream[F2,O2])(implicit S:Sub1[F,F2]): Stream[F2,(O,O2)] =
  //   (self through2v s2)(pipe2.zip)
  //
  // def zipWith[F2[_],O2,O3](s2: Stream[F2,O2])(f: (O,O2) => O3)(implicit S:Sub1[F,F2]): Stream[F2, O3] =
  //   (self through2v s2)(pipe2.zipWith(f))
  //
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

}

object Stream {
  private[fs2] def fromFree[F[_],O](free: Free[Algebra[F,O,?],Unit]): Stream[F,O] =
    new Stream(free.asInstanceOf[Free[Algebra[Nothing,Nothing,?],Unit]])

  def append[F[_],O](s1: Stream[F,O], s2: => Stream[F,O]): Stream[F,O] =
    fromFree(s1.get.flatMap { _ => s2.get })

  def apply[F[_],O](os: O*): Stream[F,O] = fromFree(Algebra.output[F,O](Chunk.seq(os)))

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
   * The infinite `Stream`, always emits `a`.
   * If for performance reasons it is good to emit `a` in chunks,
   * specify size of chunk by `chunkSize` parameter
   */
  def constant[F[_],A](a: A, chunkSize: Int = 1): Stream[F, A] =
    emits(List.fill(chunkSize)(a)) ++ constant(a, chunkSize)

  def emit[F[_],O](o: O): Stream[F,O] = fromFree(Algebra.output1[F,O](o))
  def emits[F[_],O](os: Seq[O]): Stream[F,O] = chunk(Chunk.seq(os))

  private[fs2] val empty_ = fromFree[Nothing,Nothing](Algebra.pure[Nothing,Nothing,Unit](())): Stream[Nothing,Nothing]
  def empty[F[_],O]: Stream[F,O] = empty_.asInstanceOf[Stream[F,O]]

  def eval[F[_],O](fo: F[O]): Stream[F,O] = fromFree(Algebra.eval(fo).flatMap(Algebra.output1))
  def eval_[F[_],A](fa: F[A]): Stream[F,Nothing] = eval(fa) >> empty

  def fail[F[_],O](e: Throwable): Stream[F,O] = fromFree(Algebra.fail(e))

  def force[F[_],A](f: F[Stream[F, A]]): Stream[F,A] =
    eval(f).flatMap(s => s)

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


  def pure[O](o: O*): Stream[Pure,O] = apply[Pure,O](o: _*)

  def range[F[_]](start: Int, stopExclusive: Int, by: Int = 1): Stream[F,Int] =
    unfold(start) { i =>
      if (i >= stopExclusive) None
      else Some(i -> (i + by))
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
   * @throws scala.IllegalArgumentException if `size` <= 0
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

  implicit class StreamInvariantOps[F[_],O](private val self: Stream[F,O]) {

    // /** Alias for `self through [[pipe.changes]]`. */
    // def changes(implicit eq: Eq[O]): Stream[F,O] = self through pipe.changes
    //
    // /** Folds this stream with the monoid for `O`. */
    // def foldMonoid(implicit O: Monoid[O]): Stream[F,O] = self.fold(O.empty)(O.combine)

    def pull2[O2,O3](s2: Stream[F,O2])(using: (Handle[F,O], Handle[F,O2]) => Pull[F,O3,Any]): Stream[F,O3] =
      self.open.flatMap { h1 => s2.open.flatMap { h2 => using(h1,h2) }}.close

    // /** Reduces this stream with the Semigroup for `O`. */
    // def reduceSemigroup(implicit S: Semigroup[O]): Stream[F, O] =
    //   self.reduce(S.combine(_, _))

    def repeatPull[O2](using: Handle[F,O] => Pull[F,O2,Option[Handle[F,O]]]): Stream[F,O2] =
      self.pull(Pull.loop(using))

    // def repeatPull2[O2,O3](s2: Stream[F,O2])(using: (Handle[F,O],Handle[F,O2]) => Pull[F,O3,(Handle[F,O],Handle[F,O2])]): Stream[F,O3] =
    //   self.open.flatMap { s => s2.open.flatMap { s2 => Pull.loop(using.tupled)((s,s2)) }}.close

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

    def translate[G[_]](u: UF1[F, G])(implicit G: Effect[G]): Stream[G,O] =
      translate_(u, Right(G))

    def translateSync[G[_]](u: UF1[F, G])(implicit G: MonadError[G, Throwable]): Stream[G,O] =
      translate_(u, Left(G))

    private def translate_[G[_]](u: UF1[F, G], G: Either[MonadError[G, Throwable], Effect[G]]): Stream[G,O] =
      Stream.fromFree[G,O](Algebra.translate[F,G,O,Unit](self.get, u, G))

    def unconsAsync(implicit F: Effect[F], ec: ExecutionContext): Pull[F,Nothing,AsyncPull[F,Option[(Segment[O,Unit], Stream[F,O])]]] =
      Pull.fromFree(Algebra.unconsAsync(self.get)).map(_.map(_.map { case (hd, tl) => (hd, Stream.fromFree(tl)) }))
  }

  implicit class StreamPureOps[+O](private val self: Stream[Pure,O]) {

    // TODO this is uncallable b/c covary is defined on Stream too
    def covary[F2[_]]: Stream[F2,O] = self.asInstanceOf[Stream[F2,O]]

    def toList: List[O] = covary[IO].runFold(List.empty[O])((b, a) => a :: b).unsafeRunSync.reverse

    def toVector: Vector[O] = covary[IO].runLog.unsafeRunSync
  }

  implicit class StreamOptionOps[F[_],O](private val self: Stream[F,Option[O]]) {

    def unNoneTerminate: Stream[F,O] = self.through(pipe.unNoneTerminate)
  }

  // implicit class StreamStreamOps[F[_],O](private val self: Stream[F,Stream[F,O]]) extends AnyVal {
  //   /** Alias for `Stream.join(maxOpen)(self)`. */
  //   def join(maxOpen: Int)(implicit F: Effect[F], ec: ExecutionContext) = Stream.join(maxOpen)(self)
  //
  //   /** Alias for `Stream.joinUnbounded(self)`. */
  //   def joinUnbounded(implicit F: Effect[F], ec: ExecutionContext) = Stream.joinUnbounded(self)
  // }

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
    def covary[F[_]]: Pipe[F,I,O] = self.asInstanceOf[Pipe[F,I,O]]
      //pipe.covary[F,I,O](self) todo
  }

  /** Provides operations on pure pipes for syntactic convenience. */
  implicit class PurePipe2Ops[I,I2,O](private val self: Pipe2[Pure,I,I2,O]) extends AnyVal {

    /** Lifts this pipe to the specified effect type. */
    def covary[F[_]]: Pipe2[F,I,I2,O] = self.asInstanceOf[Pipe2[F,I,I2,O]]
      //pipe2.covary[F](self) todo
  }


  implicit def covaryPure[F[_],O](s: Stream[Pure,O]): Stream[F,O] = s.asInstanceOf[Stream[F,O]]

  implicit def covaryPurePipe[F[_],I,O](p: Pipe[Pure,I,O]): Pipe[F,I,O] = p.covary[F]

  implicit def covaryPurePipe2[F[_],I,I2,O](p: Pipe2[Pure,I,I2,O]): Pipe2[F,I,I2,O] = p.covary[F]
}
