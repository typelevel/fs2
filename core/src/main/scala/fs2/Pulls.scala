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
  type Stream[+F[_],+O]

  /** The completed `Pull`. Reads and outputs nothing. */
  def done: Pull[Nothing,Nothing,Nothing]

  /** The `Pull` that reads and outputs nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing]

  /** The `Pull` that reads and outputs nothing, and succeeds with the given value, `R`. */
  def pure[R](a: R): Pull[Nothing,Nothing,R]

  /** If `p` terminates with `fail(e)`, invoke `handle(e)`. */
  def onError[F[_],O,R](p: Pull[F,O,R])(handle: Throwable => Pull[F,O,R]): Pull[F,O,R]

  /** Monadic bind. */
  def flatMap[F[_],O,R0,R](p: Pull[F,O,R0])(f: R0 => Pull[F,O,R]): Pull[F,O,R]

  /** Promote an effect to a `Pull`. */
  def eval[F[_],R](f: F[R]): Pull[F,Nothing,R]

  /** Write a stream to the output of this `Pull`. */
  def outputs[F[_],O](s: Stream[F,O]): Pull[F,O,Unit]

  /**
   * Consult `p2` if `p` fails due to an `await` on an exhausted `Handle`.
   * If `p` fails due to an error, `p2` is not consulted.
   */
  def or[F[_],O,R](p1: Pull[F,O,R], p2: => Pull[F,O,R]): Pull[F,O,R]

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def run[F[_],O,R](p: Pull[F,O,R]): Stream[F,O]
}
