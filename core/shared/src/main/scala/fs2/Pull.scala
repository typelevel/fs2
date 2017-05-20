package fs2

import fs2.internal.{ Algebra, Free, LinkedSet }

/**
 * A `p: Pull[F,O,R]` reads values from one or more streams, returns a
 * result of type `R`, and produces a `Stream[F,O]` on `p.stream`.
 *
 * Any resources acquired by `p` are freed following the `stream`.
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

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def stream: Stream[F,O] = Stream.fromFree(this.scope.get[F,O,R] map (_ => ()))

  def covaryOutput[O2>:O]: Pull[F,O2,R] = this.asInstanceOf[Pull[F,O2,R]]
  def covaryResource[R2>:R]: Pull[F,O,R2] = this.asInstanceOf[Pull[F,O,R2]]

  /** Applies the resource of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F,O,R2] = Pull.fromFree(get map f)
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

  def suspend[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] =
    output(Chunk.empty).flatMap { _ => p }

  private def releaseAll[F[_]](tokens: LinkedSet[Algebra.Token]): Pull[F,Nothing,Unit] = {
    def go(err: Option[Throwable], tokens: List[Algebra.Token]): Pull[F,Nothing,Unit] = tokens match {
      case Nil => err map (Pull.fail) getOrElse Pull.pure(())
      case tok :: tokens =>
        fromFree[F,Nothing,Unit](Algebra.release(tok)) onError (e => go(Some(e), tokens))
    }
    go(None, tokens.values.toList.reverse)
  }

  implicit def PullInvariantOps[F[_],O,R](p: Pull[F,O,R]): PullInvariantOps[F,O,R] = new PullInvariantOps(p.get)
  final class PullInvariantOps[F[_],O,R](private val free: Free[Algebra[F,O,?],R]) extends AnyVal {
    private def self: Pull[F,O,R] = Pull.fromFree(free)

    def covary[F2[x]>:F[x]]: Pull[F2,O,R] = self.asInstanceOf[Pull[F2,O,R]]
    def covaryAll[F2[x]>:F[x],O2>:O,R2>:R]: Pull[F2,O2,R2] = self.asInstanceOf[Pull[F2,O2,R2]]

    /** Applies the resource of this pull to `f` and returns the result. */
    def flatMap[O2>:O,R2](f: R => Pull[F,O2,R2]): Pull[F,O2,R2] =
      Pull.fromFree(self.get[F,O2,R] flatMap { r => f(r).get })

    /** Defined as `p >> p2 == p flatMap { _ => p2 }`. */
    def >>[O2>:O,R2](p2: => Pull[F,O2,R2]): Pull[F,O2,R2] =
      this flatMap { _ => p2 }

    /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
    def onComplete[O2>:O,R2>:R](p2: => Pull[F,O2,R2]): Pull[F,O2,R2] =
      (self onError (e => p2 >> Pull.fail(e))) flatMap { _ =>  p2 }

    /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
    def onError[O2>:O,R2>:R](h: Throwable => Pull[F,O2,R2]): Pull[F,O2,R2] =
      Pull.fromFree(self.get[F,O2,R2] onError { e => h(e).get })

    def scope: Pull[F,O,R] = Pull.snapshot[F,O] flatMap { tokens0 =>
      this flatMap { r =>
        Pull.snapshot flatMap { tokens1 =>
          val newTokens = tokens1 -- tokens0.values
          if (newTokens.isEmpty) Pull.pure(r) else Pull.releaseAll(newTokens).as(r)
        }
      } onError { e =>
        Pull.snapshot flatMap { tokens1 =>
          val newTokens = tokens1 -- tokens0.values
          if (newTokens.isEmpty) Pull.fail(e) else Pull.releaseAll(newTokens) >> Pull.fail(e)
        }
      }
    }
  }

  implicit def PullPureOps[O,R](p: Pull[Pure,O,R]): PullPureOps[O,R] = new PullPureOps(p.get[Pure,O,R])
  final class PullPureOps[O,R](private val free: Free[Algebra[Pure,O,?],R]) extends AnyVal {
    private def self: Pull[Pure,O,R] = Pull.fromFree[Pure,O,R](free)

    def covary[F[_]]: Pull[F,O,R] = self.asInstanceOf[Pull[F,O,R]]
    def covaryAll[F[_],O2>:O,R2>:R]: Pull[F,O2,R2] = self.asInstanceOf[Pull[F,O2,R2]]

    /** Applies the resource of this pull to `f` and returns the result. */
    def flatMap[F[_],O2>:O,R2](f: R => Pull[F,O2,R2]): Pull[F,O2,R2] =
      covary[F].flatMap(f)

    /** Defined as `p >> p2 == p flatMap { _ => p2 }`. */
    def >>[F[_],O2>:O,R2](p2: => Pull[F,O2,R2]): Pull[F,O2,R2] =
      covary[F] >> p2

    /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
    def onComplete[F[_],O2>:O,R2>:R](p2: => Pull[F,O2,R2]): Pull[F,O2,R2] =
      covary[F].onComplete(p2)

    /** If `this` terminates with `Pull.fail(e)`, invoke `h(e)`. */
    def onError[F[_],O2>:O,R2>:R](h: Throwable => Pull[F,O2,R2]): Pull[F,O2,R2] =
      covary[F].onError(h)

    def scope[F[_]]: Pull[F,O,R] = covary[F].scope
  }

  implicit def PullInvariantOptionOps[F[_],O,R](p: Pull[F,O,Option[R]]): PullInvariantOptionOps[F,O,R] = new PullInvariantOptionOps(p.get)
  final class PullInvariantOptionOps[F[_],O,R](private val free: Free[Algebra[F,O,?],Option[R]]) extends AnyVal {
    private def self: Pull[F,O,Option[R]] = Pull.fromFree(free)

    /** Alias for `self.flatMap(_.map(f).getOrElse(Pull.pure(None)))`. */
    def flatMapOpt[O2>:O,R2](f: R => Pull[F,O2,Option[R2]]): Pull[F,O2,Option[R2]] =
      self.flatMap {
        case None => Pull.pure(None)
        case Some(r) => f(r)
      }
  }

  implicit def PullPureOptionOps[O,R](p: Pull[Pure,O,Option[R]]): PullPureOptionOps[O,R] = new PullPureOptionOps(p.get[Pure,O,Option[R]])
  final class PullPureOptionOps[O,R](private val free: Free[Algebra[Pure,O,?],Option[R]]) extends AnyVal {
    private def self: Pull[Pure,O,Option[R]] = Pull.fromFree[Pure,O,Option[R]](free)

    /** Alias for `self.flatMap(_.map(f).getOrElse(Pull.pure(None)))`. */
    def flatMapOpt[F[_],O2>:O,R2](f: R => Pull[F,O2,Option[R2]]): Pull[F,O2,Option[R2]] =
      self.flatMap {
        case None => Pull.pure(None)
        case Some(r) => f(r)
      }
  }

  implicit def covaryPure[F[_],O,R,O2>:O,R2>:R](p: Pull[Pure,O,R]): Pull[F,O2,R2] = p.covaryAll[F,O2,R2]
}
