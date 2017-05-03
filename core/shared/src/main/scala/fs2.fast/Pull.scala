package fs2.fast

import core.Pull.PullF
import fs2.internal.LinkedSet
// import fs2.util.{RealSupertype, Lub1, Sub1}

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
    new core.Pull(getFree)

  private[fs2]
  def getFree[F2[x]>:F[x],O2>:O,R2>:R]: PullF[F2,O2,R2] =
    free.asInstanceOf[PullF[F2,O2,R2]]

  private def close_(asStep: Boolean): Stream[F,O] =
    if (asStep) Stream.fromFree(getFree[F,O,R] map (_ => ()))
    else Stream.fromFree(this.scope[F].getFree[F,O,R] map (_ => ()))

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def close: Stream[F,O] = close_(false)

  /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
  def onError[F2[x]>:F[x],O2>:O,R2>:R](h: Throwable => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    get[F2,O2,R2] onError (e => h(e).get) covariant

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x]>:F[x],O2>:O,R2](f: R => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    get[F2,O2,R] flatMap (r => f(r).get) covariant

  /** Defined as `p >> p2 == p flatMap { _ => p2 }`. */
  def >>[F2[x]>:F[x],O2>:O,R2](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    this flatMap { _ => p2 }

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x]>:F[x],O2>:O,R2>:R](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    (this onError (e => p2 >> Pull.fail(e))) flatMap { _ =>  p2 }

  def covary[F2[x]>:F[x]]: Pull[F2,O,R] = this.asInstanceOf

  def scope[F2[x]>:F[x]]: Pull[F2,O,R] = core.Pull.snapshot[F2,O].covariant flatMap { tokens0 =>
    this flatMap { r =>
      core.Pull.snapshot.covariant flatMap { tokens1 =>
        val newTokens = tokens1 -- tokens0.values
        Pull.releaseAll(newTokens).as(r)
      }
    } onError { e =>
      core.Pull.snapshot.covariant flatMap { tokens1 =>
        val newTokens = tokens1 -- tokens0.values
        Pull.releaseAll(newTokens) >> Pull.fail(e)
      }
    }
  }

  def as[R2](r2: R2): Pull[F,O,R2] = ???
}

object Pull {

  def fromFree[F[_],O,R](free: PullF[F,O,R]): Pull[F,O,R] =
    new Pull(free.asInstanceOf[PullF[Nothing,Nothing,R]])

  /** The `Pull` that reads and outputs nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(core.Pull.fail[Nothing,Nothing,Nothing](err).algebra)

  def releaseAll[F[_]](tokens: LinkedSet[core.Stream.Token]): Pull[F,Nothing,Unit] =
    ???
}
