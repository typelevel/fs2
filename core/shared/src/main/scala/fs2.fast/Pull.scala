package fs2.fast

import fs2.Chunk
import fs2.internal.LinkedSet
import fs2.fast.internal.{Algebra,Free}
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
final class Pull[+F[_],+O,+R] private(private val free: Free[Algebra[Nothing,Nothing,?],R]) extends AnyVal {

  private[fs2] def get[F2[x]>:F[x],O2>:O,R2>:R]: Free[Algebra[F2,O2,?],R2] = free.asInstanceOf[Free[Algebra[F2,O2,?],R2]]

  private def close_(asStep: Boolean): Stream[F,O] =
    if (asStep) Stream.fromFree(get[F,O,R] map (_ => ()))
    else Stream.fromFree(scope[F].get[F,O,R] map (_ => ()))

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def close: Stream[F,O] = close_(false)

  /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
  def onError[F2[x]>:F[x],O2>:O,R2>:R](h: Throwable => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    Pull.fromFree(get[F2,O2,R2] onError { e => h(e).get })

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x]>:F[x],O2>:O,R2](f: R => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    Pull.fromFree(get[F2,O2,R] flatMap { r => f(r).get })

  /** Defined as `p >> p2 == p flatMap { _ => p2 }`. */
  def >>[F2[x]>:F[x],O2>:O,R2](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    this flatMap { _ => p2 }

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x]>:F[x],O2>:O,R2>:R](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    (this onError (e => p2 >> Pull.fail(e))) flatMap { _ =>  p2 }

  def covary[F2[x]>:F[x]]: Pull[F2,O,R] = this.asInstanceOf

  def scope[F2[x]>:F[x]]: Pull[F2,O,R] = Pull.snapshot[F2,O] flatMap { tokens0 =>
    this flatMap { r =>
      Pull.snapshot flatMap { tokens1 =>
        val newTokens = tokens1 -- tokens0.values
        Pull.releaseAll(newTokens).as(r)
      }
    } onError { e =>
      Pull.snapshot flatMap { tokens1 =>
        val newTokens = tokens1 -- tokens0.values
        Pull.releaseAll(newTokens) >> Pull.fail(e)
      }
    }
  }

  def as[R2](r2: R2): Pull[F,O,R2] = ???
}

object Pull {

  def fromFree[F[_],O,R](free: Free[Algebra[F,O,?],R]): Pull[F,O,R] =
    new Pull(free.asInstanceOf[Free[Algebra[Nothing,Nothing,?],R]])

  def attemptEval[F[_],R](fr: F[R]): Pull[F,Nothing,Either[Throwable,R]] =
    fromFree[F,Nothing,Either[Throwable,R]](
      Algebra.eval[F,Nothing,R](fr).
        map(r => Right(r): Either[Throwable,R]).
        onError(t => Algebra.pure[F,Nothing,Either[Throwable,R]](Left(t))))

  /** The `Pull` that reads and outputs nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Algebra.fail[Nothing,Nothing,Nothing](err))

  def output1[F[_],O](o: O): Pull[F,O,Unit] =
    fromFree[F,O,Unit](Algebra.output1(o))

  def output[F[_],O](os: Chunk[O]): Pull[F,O,Unit] =
    fromFree[F,O,Unit](Algebra.output(Segment.chunk(os)))

  private def snapshot[F[_],O]: Pull[F,O,LinkedSet[Algebra.Token]] =
    fromFree[F,O,LinkedSet[Algebra.Token]](Algebra.snapshot)

  def releaseAll[F[_]](tokens: LinkedSet[Algebra.Token]): Pull[F,Nothing,Unit] =
    ???

  // import fs2.internal.LinkedSet
  //
  // // def snapshot[F[_],O]: Pull[F,O,LinkedSet[core.Stream.Token]] = {
  //   type AlgebraF[x] = core.Stream.Algebra[F,O,x]
  //   fromFree[F,O,LinkedSet[core.Stream.Token]](Free.Eval[AlgebraF,LinkedSet[Stream.Token]](Algebra.Snapshot()))
  // }
}
