package fs2

import fs2.util.Free
import fs2.util.~>

/**
Laws (using infix syntax):

`append` forms a monoid in conjunction with `empty`:

  * `empty append p == p` and `p append empty == p`.
  * `(p1 append p2) append p3 == p1 append (p2 append p3)`

And `push` is consistent with using `append` to prepend a single chunk:

  * `push(c)(s) == chunk(c) append s`

`fail` propagates until being caught by `onError`:

  * `fail(e) onError h == h(e)`
  * `fail(e) append s == fail(e)`
  * `fail(e) flatMap f == fail(e)`

`Stream` forms a monad with `emit` and `flatMap`:

  * `emit >=> f == f`
  * `f >=> emit == f`
  * `(f >=> g) >=> h == f >=> (g >=> h)`
  where `emit(a)` is defined as `chunk(Chunk.singleton(a)) and
        `f >=> g` is defined as `a => a flatMap f flatMap g`

The monad is the list-style sequencing monad:

  * `(a append b) flatMap f == (a flatMap f) append (b flatMap f)`
  * `empty flatMap f == empty`
*/
trait Streams[Stream[+_[_],+_]] { self =>

  // list-like operations

  def empty[F[_],A]: Stream[F,A] = chunk(Chunk.empty: Chunk[A])

  def chunk[F[_],A](as: Chunk[A]): Stream[F,A]

  def append[F[_],A](a: Stream[F,A], b: => Stream[F,A]): Stream[F,A]

  def flatMap[F[_],A,B](a: Stream[F,A])(f: A => Stream[F,B]): Stream[F,B]

  // evaluating effects

  def attemptEval[F[_],A](fa: F[A]): Stream[F,Either[Throwable,A]]

  // translating effects

  def translate[F[_],G[_],A](s: Stream[F,A])(u: F ~> G): Stream[G,A]

  // failure and error recovery

  def fail[F[_]](e: Throwable): Stream[F,Nothing]

  def onError[F[_],A](p: Stream[F,A])(handle: Throwable => Stream[F,A]): Stream[F,A]

  // safe resource usage

  def bracket[F[_],R,A](acquire: F[R])(use: R => Stream[F,A], release: R => F[Unit]): Stream[F,A]

  // stepping a stream

  type Handle[+F[_],+_]
  type Pull[+F[_],+R,+O]

  def Pull: Pulls[Pull]

  type AsyncStep[F[_],A] = Async.Future[F, Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]]]
  type AsyncStep1[F[_],A] = Async.Future[F, Pull[F, Nothing, Step[Option[A], Handle[F,A]]]]

  def push[F[_],A](h: Handle[F,A])(c: Chunk[A]): Handle[F,A]
  def cons[F[_],A](h: Stream[F,A])(c: Chunk[A]): Stream[F,A]

  def await[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]]

  def awaitAsync[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, Nothing, AsyncStep[F,A]]

  /** Open a `Stream` for transformation. Guaranteed to return a non-`done` `Pull`. */
  def open[F[_],A](s: Stream[F,A]): Pull[F,Nothing,Handle[F,A]]

  // evaluation

  def runFold[F[_],A,B](p: Stream[F,A], z: B)(f: (B,A) => B): Free[F,B]
  def runFoldTrace[F[_],A,B](t: Trace)(p: Stream[F,A], z: B)(f: (B,A) => B): Free[F,B]
}

