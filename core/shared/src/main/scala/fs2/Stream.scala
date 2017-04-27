package fs2

import scala.collection.immutable.VectorBuilder
import scala.concurrent.ExecutionContext

import cats.{ Applicative, Eq, MonadError, Monoid, Semigroup }
import cats.effect.{ Effect, IO }
import cats.implicits._

import fs2.util.{ Attempt, Free, Lub1, RealSupertype, Sub1, UF1 }

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
final class Stream[+F[_],+O] private (private val coreRef: Stream.CoreRef[F,O]) { self =>

  private[fs2] def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2] = coreRef.get

  def ++[G[_],Lub[_],O2>:O](s2: => Stream[G,O2])(implicit R: RealSupertype[O,O2], L: Lub1[F,G,Lub]): Stream[Lub,O2] =
    self append s2

  def append[G[_],Lub[_],O2>:O](s2: => Stream[G,O2])(implicit R: RealSupertype[O,O2], L: Lub1[F,G,Lub]): Stream[Lub,O2] =
    Stream.mk { StreamCore.append(Sub1.substStream(self)(L.subF).get[Lub,O2], StreamCore.suspend(Sub1.substStream(s2)(L.subG).get[Lub,O2])) }

  def attempt: Stream[F,Attempt[O]] =
    self.map(Right(_)).onError(e => Stream.emit(Left(e)))

  /** Alias for `self through [[pipe.buffer]]`. */
  def buffer(n: Int): Stream[F,O] = self through pipe.buffer(n)

  /** Alias for `self through [[pipe.bufferAll]]`. */
  def bufferAll: Stream[F,O] = self through pipe.bufferAll

  /** Alias for `self through [[pipe.bufferBy]]`. */
  def bufferBy(f: O => Boolean): Stream[F,O] = self through pipe.bufferBy(f)

  /** Alias for `self through [[pipe.changesBy]]`. */
  def changesBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F,O] = self through pipe.changesBy(f)

  /** Alias for `self through [[pipe.chunkLimit]]`. */
  def chunkLimit(n: Int): Stream[F,NonEmptyChunk[O]] = self through pipe.chunkLimit(n)

  /** Alias for `self through [[pipe.chunkN]]`. */
  def chunkN(n: Int, allowFewer: Boolean = true): Stream[F,List[NonEmptyChunk[O]]] =
    self through pipe.chunkN(n, allowFewer)

  /** Alias for `self through [[pipe.chunks]]`. */
  def chunks: Stream[F,NonEmptyChunk[O]] = self through pipe.chunks

  /** Alias for `self through [[pipe.collect]]`. */
  def collect[O2](pf: PartialFunction[O, O2]) = self through pipe.collect(pf)

  /** Alias for `self through [[pipe.collectFirst]]`. */
  def collectFirst[O2](pf: PartialFunction[O, O2]) = self through pipe.collectFirst(pf)

  /** Prepend a single chunk onto the front of this stream. */
  def cons[O2>:O](c: Chunk[O2])(implicit T: RealSupertype[O,O2]): Stream[F,O2] =
    Stream.cons[F,O2](self)(c)

  /** Prepend a single value onto the front of this stream. */
  def cons1[O2>:O](a: O2)(implicit T: RealSupertype[O,O2]): Stream[F,O2] =
    cons(Chunk.singleton(a))

  /** Converts this stream to a stream of the specified subtype. */
  def covary[F2[_]](implicit S: Sub1[F,F2]): Stream[F2,O] =
    Sub1.substStream(self)

  /** Alias for `self through [[pipe.delete]]`. */
  def delete(f: O => Boolean): Stream[F,O] = self through pipe.delete(f)

  /** Removes all output values from this stream.
   * For example, `Stream.eval(Task.delay(println("x"))).drain.runLog`
   * will, when `unsafeRun` in called, print "x" but return `Vector()`.
   */
  def drain: Stream[F, Nothing] = flatMap { _ => Stream.empty }

  /** Alias for `self through [[pipe.drop]]`. */
  def drop(n: Long): Stream[F,O] = self through pipe.drop(n)

  /** Alias for `self through [[pipe.dropLast]]`. */
  def dropLast: Stream[F,O] = self through pipe.dropLast

  /** Alias for `self through [[pipe.dropLastIf]]`. */
  def dropLastIf(p: O => Boolean): Stream[F,O] = self through pipe.dropLastIf(p)

  /** Alias for `self through [[pipe.dropRight]]`. */
  def dropRight(n: Int): Stream[F,O] = self through pipe.dropRight(n)

  /** Alias for `self through [[pipe.dropWhile]]` */
  def dropWhile(p: O => Boolean): Stream[F,O] = self through pipe.dropWhile(p)

  /** Alias for `(this through2v s2)(pipe2.either)`. */
  def either[F2[_],O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,Either[O,O2]] =
    (self through2v s2)(pipe2.either)

  def evalMap[G[_],Lub[_],O2](f: O => G[O2])(implicit L: Lub1[F,G,Lub]): Stream[Lub,O2] =
    Sub1.substStream(self)(L.subF).flatMap(a => Sub1.substStream(Stream.eval(f(a)))(L.subG))

  /** Alias for `self throughv [[pipe.evalScan]](z)(f)`. */
  def evalScan[F2[_], O2](z: O2)(f: (O2, O) => F2[O2])(implicit S: Sub1[F, F2]): Stream[F2, O2] =  self throughv pipe.evalScan(z)(f)

  /** Alias for `self through [[pipe.exists]]`. */
  def exists(f: O => Boolean): Stream[F, Boolean] = self through pipe.exists(f)

  def flatMap[G[_],Lub[_],O2](f: O => Stream[G,O2])(implicit L: Lub1[F,G,Lub]): Stream[Lub,O2] =
    Stream.mk { Sub1.substStream(self)(L.subF).get flatMap (o => Sub1.substStream(f(o))(L.subG).get) }

  def fetchAsync[F2[_],O2>:O](implicit F2: Effect[F2], S: Sub1[F,F2], T: RealSupertype[O,O2], ec: ExecutionContext): Stream[F2, ScopedFuture[F2,Stream[F2,O2]]] =
    Stream.mk(StreamCore.evalScope(get.covary[F2].covaryOutput[O2].fetchAsync).map(_ map (Stream.mk(_))))

  /** Alias for `self through [[pipe.filter]]`. */
  def filter(f: O => Boolean): Stream[F,O] = self through pipe.filter(f)

  /** Alias for `self through [[pipe.filterWithPrevious]]`. */
  def filterWithPrevious(f: (O, O) => Boolean): Stream[F,O] = self through pipe.filterWithPrevious(f)

  /** Alias for `self through [[pipe.find]]`. */
  def find(f: O => Boolean): Stream[F,O] = self through pipe.find(f)

  /** Alias for `self through [[pipe.fold]](z)(f)`. */
  def fold[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self through pipe.fold(z)(f)

  /** Alias for `self through [[pipe.fold1]](f)`. */
  def fold1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.fold1(f)

  /** Alias for `map(f).foldMonoid`. */
  def foldMap[O2](f: O => O2)(implicit O2: Monoid[O2]): Stream[F,O2] = fold(O2.empty)((acc, o) => O2.combine(acc, f(o)))

  /** Alias for `self through [[pipe.forall]]`. */
  def forall(f: O => Boolean): Stream[F, Boolean] = self through pipe.forall(f)

  /** Alias for `self through [[pipe.groupBy]]`. */
  def groupBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, (O2, Vector[O])] = self through pipe.groupBy(f)

  /** Alias for `self through [[pipe.head]]`. */
  def head: Stream[F,O] = self through pipe.head

  def interleave[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R:RealSupertype[O,O2], S:Sub1[F,F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.interleave)

  def interleaveAll[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R:RealSupertype[O,O2], S:Sub1[F,F2]): Stream[F2,O2] =
    (self through2v s2)(pipe2.interleaveAll)

  /** Alias for `(haltWhenTrue through2 this)(pipe2.interrupt)`. */
  def interruptWhen[F2[_]](haltWhenTrue: Stream[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
    (haltWhenTrue through2 Sub1.substStream(self))(pipe2.interrupt)

  /** Alias for `(haltWhenTrue.discrete through2 this)(pipe2.interrupt)`. */
  def interruptWhen[F2[_]](haltWhenTrue: async.immutable.Signal[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
    (haltWhenTrue.discrete through2 Sub1.substStream(self))(pipe2.interrupt)

  /** Alias for `self through [[pipe.intersperse]]`. */
  def intersperse[O2 >: O](separator: O2): Stream[F,O2] = self through pipe.intersperse(separator)

  /** Alias for `self through [[pipe.last]]`. */
  def last: Stream[F,Option[O]] = self through pipe.last

  /** Alias for `self through [[pipe.lastOr]]`. */
  def lastOr[O2 >: O](li: => O2): Stream[F,O2] = self through pipe.lastOr(li)

  /** Alias for `self through [[pipe.mapAccumulate]]` */
  def mapAccumulate[S,O2](init: S)(f: (S, O) => (S, O2)): Stream[F, (S, O2)] =
    self through pipe.mapAccumulate(init)(f)

  def map[O2](f: O => O2): Stream[F,O2] = mapChunks(_ map f)

  def mapChunks[O2](f: Chunk[O] => Chunk[O2]): Stream[F,O2] =
    Stream.mk(get mapChunks f)

  def mask: Stream[F,O] = onError(_ => Stream.empty)

  def merge[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
    (self through2v s2)(pipe2.merge)

  def mergeHaltBoth[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeHaltBoth)

  def mergeHaltL[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeHaltL)

  def mergeHaltR[F2[_],O2>:O](s2: Stream[F2,O2])(implicit R: RealSupertype[O,O2], S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeHaltR)

  def mergeDrainL[F2[_],O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O2] =
    (self through2v s2)(pipe2.mergeDrainL)

  def mergeDrainR[F2[_],O2](s2: Stream[F2,O2])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
    (self through2v s2)(pipe2.mergeDrainR)

  def noneTerminate: Stream[F,Option[O]] = map(Some(_)) ++ Stream.emit(None)

  def observe[F2[_],O2>:O](sink: Sink[F2,O2])(implicit F: Effect[F2], R: RealSupertype[O,O2], S: Sub1[F,F2], ec: ExecutionContext): Stream[F2,O2] =
    pipe.observe(Sub1.substStream(self)(S))(sink)

  def observeAsync[F2[_],O2>:O](sink: Sink[F2,O2], maxQueued: Int)(implicit F: Effect[F2], R: RealSupertype[O,O2], S: Sub1[F,F2], ec: ExecutionContext): Stream[F2,O2] =
    pipe.observeAsync(Sub1.substStream(self)(S), maxQueued)(sink)

  def onError[G[_],Lub[_],O2>:O](f: Throwable => Stream[G,O2])(implicit R: RealSupertype[O,O2], L: Lub1[F,G,Lub]): Stream[Lub,O2] =
    Stream.mk { (Sub1.substStream(self)(L.subF): Stream[Lub,O2]).get.onError { e => Sub1.substStream(f(e))(L.subG).get } }

  def onFinalize[F2[_]](f: F2[Unit])(implicit S: Sub1[F,F2], F2: Applicative[F2]): Stream[F2,O] =
    Stream.bracket(F2.pure(()))(_ => Sub1.substStream(self), _ => f)

  def open: Pull[F, Nothing, Handle[F,O]] = Pull.pure(new Handle(List(), self))

  def output: Pull[F,O,Unit] = Pull.outputs(self)

  /** Alias for `(pauseWhenTrue through2 this)(pipe2.pause)`. */
  def pauseWhen[F2[_]](pauseWhenTrue: Stream[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
    (pauseWhenTrue through2 Sub1.substStream(self))(pipe2.pause[F2,O])

  /** Alias for `(pauseWhenTrue.discrete through2 this)(pipe2.pause)`. */
  def pauseWhen[F2[_]](pauseWhenTrue: async.immutable.Signal[F2,Boolean])(implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
    (pauseWhenTrue.discrete through2 Sub1.substStream(self))(pipe2.pause)

  def pull[F2[_],O2](using: Handle[F,O] => Pull[F2,O2,Any])(implicit S: Sub1[F,F2]) : Stream[F2,O2] =
    Sub1.substPull(open).flatMap(h => Sub1.substPull(using(h))).close

  /** Converts a `Stream[Nothing,O]` in to a `Stream[Pure,O]`. */
  def pure(implicit S: Sub1[F,Pure]): Stream[Pure,O] = covary[Pure]

  /** Repeat this stream an infinite number of times. `s.repeat == s ++ s ++ s ++ ...` */
  def repeat: Stream[F,O] = self ++ repeat

  def runFree: Free[F,Unit] =
    runFoldFree(())((_,_) => ())

  def runFoldFree[O2](z: O2)(f: (O2,O) => O2): Free[F,O2] =
    get.runFold(z)(f)

  def runLogFree: Free[F,Vector[O]] =
    Free.suspend(runFoldFree(new VectorBuilder[O])(_ += _).map(_.result))

  /** Alias for `self through [[pipe.prefetch]](f).` */
  def prefetch[F2[_]](implicit S: Sub1[F,F2], F2: Effect[F2], ec: ExecutionContext): Stream[F2,O] =
    Sub1.substStream(self)(S) through pipe.prefetch

  /** Alias for `self through [[pipe.rechunkN]](f).` */
  def rechunkN(n: Int, allowFewer: Boolean = true): Stream[F,O] = self through pipe.rechunkN(n, allowFewer)

  /** Alias for `self through [[pipe.reduce]](z)(f)`. */
  def reduce[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.reduce(f)

  /** Alias for `self through [[pipe.scan]](z)(f)`. */
  def scan[O2](z: O2)(f: (O2, O) => O2): Stream[F,O2] = self through pipe.scan(z)(f)

  /** Alias for `self through [[pipe.scan1]](f)`. */
  def scan1[O2 >: O](f: (O2, O2) => O2): Stream[F,O2] = self through pipe.scan1(f)

  /**
   * Used in conjunction with `[[Stream.uncons]]` or `[[Stream.uncons1]]`.
   * When `s.scope` starts, the set of live resources is recorded.
   * When `s.scope` finishes, any newly allocated resources since the start of `s`
   * are all cleaned up.
   */
  def scope: Stream[F,O] =
    Stream.mk { StreamCore.scope { self.get } }

  /** Alias for `self through [[pipe.shiftRight]]`. */
  def shiftRight[O2 >: O](head: O2*): Stream[F,O2] = self through pipe.shiftRight(head: _*)

  /** Alias for `self through [[pipe.sliding]]`. */
  def sliding(n: Int): Stream[F,Vector[O]] = self through pipe.sliding(n)

  /** Alias for `self through [[pipe.split]]`. */
  def split(f: O => Boolean): Stream[F,Vector[O]] = self through pipe.split(f)

  def step: Pull[F,Nothing,(NonEmptyChunk[O],Handle[F,O])] =
    Pull.evalScope(get.step).flatMap {
      case StreamCore.StepResult.Done => Pull.done
      case StreamCore.StepResult.Failed(err) => Pull.fail(err)
      case StreamCore.StepResult.Emits(c, h) => Pull.pure((c, new Handle(Nil, Stream.mk(h))))
    }

  def stepAsync[F2[_],O2>:O](
    implicit S: Sub1[F,F2], F2: Effect[F2], T: RealSupertype[O,O2], ec: ExecutionContext)
    : Pull[F2,Nothing,ScopedFuture[F2,Pull[F2,Nothing,(NonEmptyChunk[O2], Handle[F2,O2])]]]
    =
    Pull.evalScope { get.covary[F2].unconsAsync.map { _ map { case (leftovers,o) =>
      val inner: Pull[F2,Nothing,(NonEmptyChunk[O2], Handle[F2,O2])] = o match {
        case None => Pull.done
        case Some(Left(err)) => Pull.fail(err)
        case Some(Right((hd,tl))) => Pull.pure((hd, new Handle(Nil, Stream.mk(tl))))
      }
      if (leftovers.isEmpty) inner else Pull.release(leftovers) flatMap { _ => inner }
    }}}

  /** Alias for `self through [[pipe.tail]]`. */
  def tail: Stream[F,O] = self through pipe.tail

  /** Alias for `self through [[pipe.take]](n)`. */
  def take(n: Long): Stream[F,O] = self through pipe.take(n)

  /** Alias for `self through [[pipe.takeRight]]`. */
  def takeRight(n: Long): Stream[F,O] = self through pipe.takeRight(n)

  /** Alias for `self through [[pipe.takeThrough]]`. */
  def takeThrough(p: O => Boolean): Stream[F,O] = self through pipe.takeThrough(p)

  /** Alias for `self through [[pipe.takeWhile]]`. */
  def takeWhile(p: O => Boolean): Stream[F,O] = self through pipe.takeWhile(p)

  /** Like `through`, but the specified `Pipe`'s effect may be a supertype of `F`. */
  def throughv[F2[_],O2](f: Pipe[F2,O,O2])(implicit S: Sub1[F,F2]): Stream[F2,O2] =
    f(Sub1.substStream(self))

  /** Like `through2`, but the specified `Pipe2`'s effect may be a supertype of `F`. */
  def through2v[F2[_],O2,O3](s2: Stream[F2,O2])(f: Pipe2[F2,O,O2,O3])(implicit S: Sub1[F,F2]): Stream[F2,O3] =
    f(Sub1.substStream(self), s2)

  /** Like `to`, but the specified `Sink`'s effect may be a supertype of `F`. */
  def tov[F2[_]](f: Sink[F2,O])(implicit S: Sub1[F,F2]): Stream[F2,Unit] =
    f(Sub1.substStream(self))

  def translate[G[_]](u: UF1[F, G]): Stream[G,O] =
    Stream.mk { get.translate(StreamCore.NT.T(u)) }

  /** Alias for `self through [[pipe.unchunk]]`. */
  def unchunk: Stream[F,O] = self through pipe.unchunk

  /**
    * Same as `uncons1` except operates over chunks instead of single elements of type `A`
    */
  def uncons: Stream[F, Option[(NonEmptyChunk[O], Stream[F,O])]] =
    Stream.mk(get.uncons.map(_ map { case (hd,tl) => (hd, Stream.mk(tl)) }))

  /**
    * A new [[Stream]] of one element containing the head element of this [[Stream]] along
    * with a reference to the remaining [[Stream]] after evaluation of the first element.
    *
    * {{{
    *   scala> Stream(1,2,3).uncons1.toList
    *   res1: List[Option[(Int, Stream[Nothing, Int])]] = List(Some((1,append(Segment(Emit(Chunk(2, 3))), Segments()))))
    * }}}
    *
    * You can use this to implement any stateful stream function, like `take`:
    *
    * {{{
    *   def take[F[_],A](n: Int)(s: Stream[F,A]): Stream[F,A] =
    *     if (n <= 0) Stream.empty
    *     else s.uncons1.flatMap {
    *       case None => Stream.empty
    *       case Some((hd, tl)) => Stream.emit(hd) ++ take(n-1)(tl)
    *     }
    * }}}
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
    *    take(2)(Stream(1,2,3).onFinalize(Task.delay(println("done"))) ++
    *    anotherStream
    * }}}
    *
    * The "done" would not be printed until the end of `anotherStream`. To get the
    * prompt finalization behavior, we would have to do:
    *
    * {{{
    *    take(2)(Stream(1,2,3).onFinalize(Task.delay(println("done"))).scope ++
    *    anotherStream
    * }}}
    *
    * Note the call to `scope` after the completion of `take`, which ensures that
    * when that stream completes, any streams which have been opened by the `take`
    * are deemed closed and their finalizers can be run.
    */
  def uncons1: Stream[F, Option[(O,Stream[F,O])]] =
    Stream.mk {
      def go(s: StreamCore[F,O]): StreamCore[F,Option[(O,Stream[F,O])]] = s.uncons.flatMap {
        case None => StreamCore.emit(None)
        case Some((hd,tl)) =>
          val (hc, tc) = hd.unconsNonEmpty
          StreamCore.emit(Some((hc, Stream.mk(tl).cons(tc))))
      }
      go(get)
    }

  /** Alias for `self through [[pipe.vectorChunkN]]`. */
  def vectorChunkN(n: Int, allowFewer: Boolean = true): Stream[F,Vector[O]] =
    self through pipe.vectorChunkN(n, allowFewer)

  def zip[F2[_],O2](s2: Stream[F2,O2])(implicit S:Sub1[F,F2]): Stream[F2,(O,O2)] =
    (self through2v s2)(pipe2.zip)

  def zipWith[F2[_],O2,O3](s2: Stream[F2,O2])(f: (O,O2) => O3)(implicit S:Sub1[F,F2]): Stream[F2, O3] =
    (self through2v s2)(pipe2.zipWith(f))

  /** Alias for `self through [[pipe.zipWithIndex]]`. */
  def zipWithIndex: Stream[F, (O, Int)] = self through pipe.zipWithIndex

  /** Alias for `self through [[pipe.zipWithNext]]`. */
  def zipWithNext: Stream[F, (O, Option[O])] = self through pipe.zipWithNext

  /** Alias for `self through [[pipe.zipWithPrevious]]`. */
  def zipWithPrevious: Stream[F, (Option[O], O)] = self through pipe.zipWithPrevious

  /** Alias for `self through [[pipe.zipWithPreviousAndNext]]`. */
  def zipWithPreviousAndNext: Stream[F, (Option[O], O, Option[O])] = self through pipe.zipWithPreviousAndNext

  /** Alias for `self through [[pipe.zipWithScan]]`. */
  def zipWithScan[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] = self through pipe.zipWithScan(z)(f)

  /** Alias for `self through [[pipe.zipWithScan1]]`. */
  def zipWithScan1[O2](z: O2)(f: (O2, O) => O2): Stream[F,(O,O2)] = self through pipe.zipWithScan1(z)(f)

  /** Alias for `s >> s2 == s flatMap { _ => s2 }`. */
  def >>[G[_],Lub[_],O2](f: Stream[G,O2])(implicit L: Lub1[F,G,Lub]): Stream[Lub,O2] =
    Stream.mk { Sub1.substStream(self)(L.subF).get flatMap (_ => Sub1.substStream(f)(L.subG).get) }

  override def toString = get.toString
}

object Stream {

  def apply[F[_],A](a: A*): Stream[F,A] = chunk(Chunk.seq(a))

  def attemptEval[F[_], A](fa: F[A]): Stream[F,Attempt[A]] =
    Stream.mk { StreamCore.attemptEval(fa) }


  def bracket[F[_],R,A](r: F[R])(use: R => Stream[F,A], release: R => F[Unit]): Stream[F,A] = Stream.mk {
    StreamCore.acquire(r, release andThen (Free.eval)) flatMap { case (_, r) => use(r).get }
  }

  private[fs2]
  def bracketWithToken[F[_],R,A](r: F[R])(use: R => Stream[F,A], release: R => F[Unit]): Stream[F,(StreamCore.Token,A)] = Stream.mk {
    StreamCore.acquire(r, release andThen (Free.eval)) flatMap { case (token, r) => use(r).get.map(a => (token, a)) }
  }

  def chunk[F[_], A](as: Chunk[A]): Stream[F,A] =
    Stream.mk { StreamCore.chunk[F,A](as) }

  def cons[F[_],O](h: Stream[F,O])(c: Chunk[O]): Stream[F,O] =
    if (c.isEmpty) h
    else Stream.mk { h.get.pushEmit(c) }

  /**
   * The infinite `Stream`, always emits `a`.
   * If for performance reasons it is good to emit `a` in chunks,
   * specify size of chunk by `chunkSize` parameter
   */
  def constant[F[_],A](a: A, chunkSize: Int = 1): Stream[F, A] =
    emits(List.fill(chunkSize)(a)) ++ constant(a, chunkSize)

  def emit[F[_],A](a: A): Stream[F,A] = chunk(Chunk.singleton(a))

  def emits[F[_],A](a: Seq[A]): Stream[F,A] = chunk(Chunk.seq(a))

  def empty[F[_],A]: Stream[F,A] = chunk(Chunk.empty: Chunk[A])

  def eval_[F[_],A](fa: F[A]): Stream[F,Nothing] = eval(fa).flatMap { _ => empty }

  def eval[F[_],A](fa: F[A]): Stream[F, A] = attemptEval(fa).flatMap { _ fold(fail, emit) }

  def evalScope[F[_],A](fa: Scope[F,A]): Stream[F,A] =
    Stream.mk { StreamCore.evalScope(fa) }

  def fail[F[_]](e: Throwable): Stream[F,Nothing] =
    Stream.mk { StreamCore.fail(e) }

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
    Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Attempt[Chunk[O]]]) flatMap { outputQ => // sync queue assures we won't overload heap when resulting stream is not able to catchup with inner streams
      val incrementRunning: F[Unit] = running.modify(_ + 1) as (())
      val decrementRunning: F[Unit] = running.modify(_ - 1) as (())

      // runs inner stream
      // each stream is forked.
      // terminates when killSignal is true
      // if fails will enq in queue failure
      def runInner(inner: Stream[F, O]): Stream[F, Nothing] = {
        Stream.eval_(
          available.decrement >> incrementRunning >>
          concurrent.start {
            inner.chunks.attempt
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
        outer.interruptWhen(killSignal) flatMap runInner onFinalize decrementRunning run
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
        case Right(c) => Stream.chunk(c)
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

  def pure[A](a: A*): Stream[Pure,A] = apply[Pure,A](a: _*)

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

  def repeatEval[F[_],A](a: F[A]): Stream[F,A] = Stream.eval(a).repeat

  def suspend[F[_],A](s: => Stream[F,A]): Stream[F,A] = emit(()) flatMap { _ => s }

  /** Produce a (potentially infinite) stream from an unfold. */
  def unfold[F[_],S,A](s0: S)(f: S => Option[(A,S)]): Stream[F,A] = {
    def go(s: S): Stream[F,A] =
      f(s) match {
        case Some((a, sn)) => emit(a) ++ go(sn)
        case None => empty
      }
    suspend(go(s0))
  }

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

  implicit class StreamInvariantOps[F[_],O](private val self: Stream[F,O]) extends AnyVal {

    /** Alias for `self through [[pipe.changes]]`. */
    def changes(implicit eq: Eq[O]): Stream[F,O] = self through pipe.changes

    /** Folds this stream with the monoid for `O`. */
    def foldMonoid(implicit O: Monoid[O]): Stream[F,O] = self.fold(O.empty)(O.combine)

    def pull2[O2,O3](s2: Stream[F,O2])(using: (Handle[F,O], Handle[F,O2]) => Pull[F,O3,Any]): Stream[F,O3] =
      self.open.flatMap { h1 => s2.open.flatMap { h2 => using(h1,h2) }}.close

    /** Reduces this stream with the Semigroup for `O`. */
    def reduceSemigroup(implicit S: Semigroup[O]): Stream[F, O] =
      self.reduce(S.combine(_, _))

    def repeatPull[O2](using: Handle[F,O] => Pull[F,O2,Handle[F,O]]): Stream[F,O2] =
      self.pull(Pull.loop(using))

    def repeatPull2[O2,O3](s2: Stream[F,O2])(using: (Handle[F,O],Handle[F,O2]) => Pull[F,O3,(Handle[F,O],Handle[F,O2])]): Stream[F,O3] =
      self.open.flatMap { s => s2.open.flatMap { s2 => Pull.loop(using.tupled)((s,s2)) }}.close

    def run(implicit F: MonadError[F, Throwable]):F[Unit] =
      self.runFree.run

    def runFold[O2](z: O2)(f: (O2,O) => O2)(implicit F: MonadError[F, Throwable]): F[O2] =
      self.runFoldFree(z)(f).run

    def runFoldMonoid(implicit F: MonadError[F, Throwable], O: Monoid[O]): F[O] =
      runFold(O.empty)(O.combine)

    def runFoldSemigroup(implicit F: MonadError[F, Throwable], O: Semigroup[O]): F[Option[O]] =
      runFold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    def runLog(implicit F: MonadError[F, Throwable]): F[Vector[O]] =
      self.runLogFree.run

    def runLast(implicit F: MonadError[F, Throwable]): F[Option[O]] =
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
  }

  implicit class StreamPureOps[+O](private val self: Stream[Pure,O]) extends AnyVal {

    def toList: List[O] =
      self.covary[IO].runFold(List.empty[O])((b, a) => a :: b).unsafeRunSync.reverse

    def toVector: Vector[O] =
      self.covary[IO].runLog.unsafeRunSync
  }

  implicit class StreamOptionOps[F[_],O](private val self: Stream[F,Option[O]]) extends AnyVal {

    /** Alias for `[[pipe.unNone]]` */
    def unNone: Stream[F, O] = self through pipe.unNone

    /** Alias for `[[pipe.unNoneTerminate]]` */
    def unNoneTerminate: Stream[F,O] = self through pipe.unNoneTerminate

  }

  implicit class StreamStreamOps[F[_],O](private val self: Stream[F,Stream[F,O]]) extends AnyVal {
    /** Alias for `Stream.join(maxOpen)(self)`. */
    def join(maxOpen: Int)(implicit F: Effect[F], ec: ExecutionContext) = Stream.join(maxOpen)(self)

    /** Alias for `Stream.joinUnbounded(self)`. */
    def joinUnbounded(implicit F: Effect[F], ec: ExecutionContext) = Stream.joinUnbounded(self)
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
    def covary[F[_]]: Pipe[F,I,O] =
      pipe.covary[F,I,O](self)
  }

  implicit def covaryPure[F[_],O](s: Stream[Pure,O]): Stream[F,O] = s.covary[F]

  implicit def covaryPurePipe[F[_],I,O](p: Pipe[Pure,I,O]): Pipe[F,I,O] = pipe.covary[F,I,O](p)

  implicit def covaryPurePipe2[F[_],I,I2,O](p: Pipe2[Pure,I,I2,O]): Pipe2[F,I,I2,O] = pipe2.covary[F,I,I2,O](p)

  implicit def streamMonadErrorInstance[F[_]]: MonadError[Stream[F,?], Throwable] =
    new MonadError[Stream[F,?], Throwable] {
      def pure[A](a: A): Stream[F,A] = Stream.emit(a)
      def flatMap[A,B](s: Stream[F,A])(f: A => Stream[F,B]): Stream[F,B] = s.flatMap(f)
      def tailRecM[A,B](a: A)(f: A => Stream[F,Either[A,B]]): Stream[F,B] =
        f(a).flatMap {
          case Left(a) => tailRecM(a)(f)
          case Right(b) => pure(b)
        }
      def handleErrorWith[A](s: Stream[F,A])(f: Throwable => Stream[F, A]): Stream[F, A] =
        s.attempt.flatMap {
          case Left(t) => f(t)
          case Right(a) => Stream.emit(a)
        }
      def raiseError[A](e: Throwable): Stream[F,A] = Stream.fail(e)
    }

  private[fs2] def mk[F[_],O](s: StreamCore[F,O]): Stream[F,O] = new Stream[F,O](new CoreRef(s))

  private final class CoreRef[+F[_],+O](core: StreamCore[F,O]) {
    def get[F2[_],O2>:O](implicit S: Sub1[F,F2], T: RealSupertype[O,O2]): StreamCore[F2,O2] =
      core.covary[F2].covaryOutput[O2]
  }
}
