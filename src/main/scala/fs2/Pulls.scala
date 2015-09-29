package fs2

/**

Laws:

`or` forms a monoid in conjunction with `done`:

  * `or(done, p) == p` and `or(p, done) == p`.
  * `or(or(p1,p2), p3) == or(p1, or(p2,p3))`

`fail` is caught by `onError`:

  * `onError(fail(e))(f) == f(e)`

`Pull` forms a monad with `pure` and `flatMap`:

  * `pure >=> f == f`
  * `f >=> pure == f`
  * `(f >=> g) >=> h == f >=> (g >=> h)`
  where `f >=> g` is defined as `a => a flatMap f flatMap g`

*/
trait Pulls[Pull[+_[_],+_,+_]] {
  type Stream[+F[_],+W]

  /** The completed `Pull`. Reads and writes nothing. */
  def done: Pull[Nothing,Nothing,Nothing]

  /** The `Pull` that reads and writes nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing]

  /** The `Pull` that reads and writes nothing, and succeeds with the given value, `R`. */
  def pure[R](a: R): Pull[Nothing,Nothing,R]

  /** If `p` terminates with `fail(e)`, invoke `handle(e)`. */
  def onError[F[_],W,R](p: Pull[F,W,R])(handle: Throwable => Pull[F,W,R]): Pull[F,W,R]

  /** Monadic bind. */
  def flatMap[F[_],W,R0,R](p: Pull[F,W,R0])(f: R0 => Pull[F,W,R]): Pull[F,W,R]

  /** Promote an effect to a `Pull`. */
  def eval[F[_],R](f: F[R]): Pull[F,Nothing,R]

  /** Write a stream to the output of this `Pull`. */
  def writes[F[_],W](s: Stream[F,W]): Pull[F,W,Unit]

  /**
   * Consult `p2` if `p` fails due to an `await` on an exhausted `Handle`.
   * If `p` fails due to an error, `p2` is not consulted.
   */
  def or[F[_],W,R](p1: Pull[F,W,R], p2: => Pull[F,W,R]): Pull[F,W,R]

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def run[F[_],W,R](p: Pull[F,W,R]): Stream[F,W]

  // derived operations

}
