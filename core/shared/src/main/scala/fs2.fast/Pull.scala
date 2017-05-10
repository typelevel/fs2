package fs2.fast

import fs2.internal.LinkedSet
import fs2.fast.internal.{Algebra,Free}

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

  def as[R2](r2: R2): Pull[F,O,R2] = map(_ => r2)

  private def close_(asStep: Boolean): Stream[F,O] =
    if (asStep) Stream.fromFree(get[F,O,R] map (_ => ()))
    else Stream.fromFree(scope[F].get[F,O,R] map (_ => ()))

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def close: Stream[F,O] = close_(false)

  /** Close this `Pull`, but don't cleanup any resources acquired. */
  private[fs2] def closeAsStep: Stream[F,O] = close_(true) // todo this isn't used anywhere

  def covary[F2[x]>:F[x]]: Pull[F2,O,R] = this.asInstanceOf[Pull[F2,O,R]]
  def covaryOutput[O2>:O]: Pull[F,O2,R] = this.asInstanceOf[Pull[F,O2,R]]
  def covaryResource[R2>:R]: Pull[F,O,R2] = this.asInstanceOf[Pull[F,O,R2]]
  def covaryAll[F2[x]>:F[x],O2>:O,R2>:R]: Pull[F2,O2,R2] = this.asInstanceOf[Pull[F2,O2,R2]]

  /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
  def onError[F2[x]>:F[x],O2>:O,R2>:R](h: Throwable => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    Pull.fromFree(get[F2,O2,R2] onError { e => h(e).get })

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x]>:F[x],O2>:O,R2](f: R => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    Pull.fromFree(get[F2,O2,R] flatMap { r => f(r).get })

  /** Defined as `p >> p2 == p flatMap { _ => p2 }`. */
  def >>[F2[x]>:F[x],O2>:O,R2](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    this flatMap { _ => p2 }

  /** Applies the resource of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F,O,R2] =
    Pull.fromFree(get map f)

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x]>:F[x],O2>:O,R2>:R](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    (this onError (e => p2 >> Pull.fail(e))) flatMap { _ =>  p2 }

  def race[F2[x]>:F[x],O2>:O,R2](p2: Pull[F2,O2,R2]): Pull[F2,O2,Either[R,R2]] = ???

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
}

object Pull {

  private[fs2] def fromFree[F[_],O,R](free: Free[Algebra[F,O,?],R]): Pull[F,O,R] =
    new Pull(free.asInstanceOf[Free[Algebra[Nothing,Nothing,?],R]])

  def attemptEval[F[_],R](fr: F[R]): Pull[F,Nothing,Either[Throwable,R]] =
    fromFree(
      Algebra.eval[F,Nothing,R](fr).
        map(r => Right(r): Either[Throwable,R]).
        onError(t => Algebra.pure[F,Nothing,Either[Throwable,R]](Left(t))))

  /** The completed `Pull`. Reads and outputs nothing. */
  val done: Pull[Nothing,Nothing,Unit] = fromFree[Nothing,Nothing,Unit](Algebra.pure[Nothing,Nothing,Unit](()))

  def eval[F[_],R](fr: F[R]): Pull[F,Nothing,R] =
    fromFree(Algebra.eval[F,Nothing,R](fr))

  /** The `Pull` that reads and outputs nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Algebra.fail[Nothing,Nothing,Nothing](err))

  /**
   * Repeatedly use the output of the `Pull` as input for the next step of the pull.
   * Halts when a step terminates with `None` or `Pull.fail`.
   */
  def loop[F[_],O,R](using: R => Pull[F,O,Option[R]]): R => Pull[F,O,Unit] =
    r => using(r) flatMap { _.map(loop(using)).getOrElse(Pull.pure(())) }

  def output1[F[_],O](o: O): Pull[F,O,Unit] =
    fromFree(Algebra.output1[F,O](o))

  def output[F[_],O](os: Segment[O,Unit]): Pull[F,O,Unit] =
    fromFree(Algebra.output[F,O](os))

  def pure[F[_],R](r: R): Pull[F,Nothing,R] =
    fromFree(Algebra.pure(r))

  def segment[F[_],O,R](s: Segment[O,R]): Pull[F,O,R] =
    fromFree(Algebra.segment[F,O,R](s))

  private def snapshot[F[_],O]: Pull[F,O,LinkedSet[Algebra.Token]] =
    fromFree[F,O,LinkedSet[Algebra.Token]](Algebra.snapshot)

  private def releaseAll[F[_]](tokens: LinkedSet[Algebra.Token]): Pull[F,Nothing,Unit] = {
    def go(err: Option[Throwable], tokens: List[Algebra.Token]): Pull[F,Nothing,Unit] = tokens match {
      case Nil => err map (Pull.fail) getOrElse Pull.pure(())
      case tok :: tokens =>
        fromFree[F,Nothing,Unit](Algebra.release(tok)) onError (e => go(Some(e), tokens))
    }
    go(None, tokens.values.toList.reverse)
  }

  implicit class PullOptionOps[+F[_],+O,+R](self: Pull[F,O,Option[R]]) {
    /** Alias for `self.flatMap(_.map(f).getOrElse(Pull.pure(None)))`. */
    def flatMapOpt[F2[x]>:F[x],O2>:O,R2](f: R => Pull[F2,O2,Option[R2]]): Pull[F2,O2,Option[R2]] =
      self.flatMap {
        case None => Pull.pure(None)
        case Some(r) => f(r)
      }
  }
}
