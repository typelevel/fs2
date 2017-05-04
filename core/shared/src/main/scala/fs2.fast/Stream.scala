package fs2.fast

import fs2.{Chunk,Pure}

import scala.concurrent.ExecutionContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.Effect

import fs2.internal.TwoWayLatch
import fs2.fast.internal.{Algebra,Free}

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

  def flatMap[F2[x]>:F[x],O2](f: O => Stream[F2,O2]): Stream[F2,O2] =
    Stream.fromFree(Algebra.uncons(get[F2,O]).flatMap {
      case None => Stream.empty[F2,O2].get
      case Some((hd, tl)) =>
        (hd.map(f).toChunk.foldRight(Stream.empty[F2,O2])(Stream.append(_,_)) ++ Stream.fromFree(tl).flatMap(f)).get
    })

  /** Defined as `s >> s2 == s flatMap { _ => s2 }`. */
  def >>[F2[x]>:F[x],O2](s2: => Stream[F2,O2]): Stream[F2,O2] =
    this flatMap { _ => s2 }

  def ++[F2[x]>:F[x],O2>:O](s2: => Stream[F2,O2]): Stream[F2,O2] =
    Stream.append(this, s2)

  /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
  def onError[F2[x]>:F[x],O2>:O](h: Throwable => Stream[F2,O2]): Stream[F2,O2] =
    Stream.fromFree(get[F2,O2] onError { e => h(e).get })

  /** Repeat this stream an infinite number of times. `s.repeat == s ++ s ++ s ++ ...` */
  def repeat: Stream[F,O] = this ++ repeat

  def run[F2[x]>:F[x]](implicit F: Effect[F2], ec: ExecutionContext): F2[Unit] =
    runFold[F2,Unit](())((u, _) => u)

  def runFold[F2[x]>:F[x],B](init: B)(f: (B, O) => B)(implicit F: Effect[F2], ec: ExecutionContext): F2[B] =
    Algebra.runFold(get[F2,O], init)(f, new AtomicBoolean(false), TwoWayLatch(0), new ConcurrentHashMap)

  def runLog[F2[x]>:F[x],O2>:O](implicit F: Effect[F2], ec: ExecutionContext): F2[Vector[O2]] = {
    import scala.collection.immutable.VectorBuilder
    F.suspend(F.map(runFold[F2, VectorBuilder[O2]](new VectorBuilder[O2])(_ += _))(_.result))
  }
}

object Stream {
  private[fs2] def fromFree[F[_],O](free: Free[Algebra[F,O,?],Unit]): Stream[F,O] =
    new Stream(free.asInstanceOf[Free[Algebra[Nothing,Nothing,?],Unit]])

  def apply[F[_],O](os: O*): Stream[F,O] = fromFree[F,O](Algebra.output(Segment(os: _*)))

  def attemptEval[F[_],O](fo: F[O]): Stream[F,Either[Throwable,O]] =
    fromFree(Pull.attemptEval(fo).flatMap(Pull.output1).get)

  def chunk[F[_],O](os: Chunk[O]): Stream[F,O] = fromFree[F,O](Algebra.output(Segment.chunk(os)))
  def emit[F[_],O](o: O): Stream[F,O] = fromFree[F,O](Algebra.output1(o))
  def emits[F[_],O](os: Seq[O]): Stream[F,O] = chunk(Chunk.seq(os))

  private[fs2] val empty_ = fromFree[Nothing,Nothing](Algebra.pure[Nothing,Nothing,Unit](())): Stream[Nothing,Nothing]
  def empty[F[_],O]: Stream[F,O] = empty_.asInstanceOf[Stream[F,O]]

  def eval[F[_],O](fo: F[O]): Stream[F,O] = fromFree[F,O](Algebra.eval(fo).flatMap(Algebra.output1))
  def eval_[F[_],A](fa: F[A]): Stream[F,Nothing] = eval(fa) >> empty

  def fail[F[_],O](e: Throwable): Stream[F,O] = fromFree(Algebra.fail(e))

  def append[F[_],O](s1: Stream[F,O], s2: => Stream[F,O]): Stream[F,O] =
    Stream.fromFree(s1.get.flatMap { _ => s2.get })

  def pure[O](o: O*): Stream[Pure,O] = apply[Pure,O](o: _*)

  def suspend[F[_],O](s: => Stream[F,O]): Stream[F,O] =
    emit(()).flatMap { _ => s }
}
