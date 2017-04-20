package fs2.fast

import core.Pull.PullF

/**
 * A `p: Pull[F,O,R]` reads values from one or more streams, returns a
 * result of type `R`, and produces a `Stream[F,O]` on `p.close`.
 *
 * Any resources acquired by `p` are freed following the `close`.
 *
 * Laws:
 *
 * `Pull` forms a monad in `R` with `pure` and `flatMap`:
 *   - `pure >=> f == f`
 *   - `f >=> pure == f`
 *   - `(f >=> g) >=> h == f >=> (g >=> h)`
 * where `f >=> g` is defined as `a => a flatMap f flatMap g`
 *
 * `fail` is caught by `onError`:
 *   - `onError(fail(e))(f) == f(e)`
 */
final class Pull[+F[_],+O,+R] private(private val free: PullF[Nothing,Nothing,R]) extends AnyVal {

  private[fs2]
  def get[F2[x]>:F[x],O2>:O,R2>:R]: core.Pull[F2,O2,R2] =
    new core.Pull(free.asInstanceOf[PullF[F2,O2,R2]])
}

object Pull {

  def fromFree[F[_],O,R](free: PullF[F,O,R]): Pull[F,O,R] =
    new Pull(free.asInstanceOf[PullF[Nothing,Nothing,R]])
}
