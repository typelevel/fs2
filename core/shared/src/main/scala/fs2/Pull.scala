package fs2

import fs2.util.{Async,Attempt,Free,NonFatal,RealSupertype,Sub1}
import StreamCore.Token
import Stream.Handle
import Pull._

/**
 * A pull allows acquiring elements from a stream in a resource safe way,
 * emitting elements of type `O`, working with a resource of type `R`,
 * and evaluating effects of type `F`.
 *
 * Laws:
 *
 * `or` forms a monoid in conjunction with `done`:
 *   - `or(done, p) == p` and `or(p, done) == p`.
 *   - `or(or(p1,p2), p3) == or(p1, or(p2,p3))`
 *
 * `fail` is caught by `onError`:
 *   - `onError(fail(e))(f) == f(e)`
 *
 * `Pull` forms a monad with `pure` and `flatMap`:
 *   - `pure >=> f == f`
 *   - `f >=> pure == f`
 *   - `(f >=> g) >=> h == f >=> (g >=> h)`
 * where `f >=> g` is defined as `a => a flatMap f flatMap g`
 */
final class Pull[+F[_],+O,+R] private (private val get: Free[AlgebraF[F,O]#f,Option[Attempt[R]]]) {

  private def close_(asStep: Boolean): Stream[F,O] = Stream.mk { val s = {
    type G[x] = StreamCore[F,O]; type Out = Option[Attempt[R]]
    get.fold[AlgebraF[F,O]#f,G,Out](
      StreamCore.suspend,
      o => o match {
        case None => StreamCore.empty
        case Some(e) => e.fold(StreamCore.fail(_), _ => StreamCore.empty)
      },
      err => StreamCore.fail(err),
      new Free.B[AlgebraF[F,O]#f,G,Out] { def f[x] = r => r match {
        case Left((Algebra.Eval(fr), g)) => StreamCore.evalScope(fr.attempt) flatMap g
        case Left((Algebra.Output(o), g)) => StreamCore.append(o, StreamCore.suspend(g(Right(()))))
        case Right((r,g)) => StreamCore.attemptStream(g(r))
      }}
    )(Sub1.sub1[AlgebraF[F,O]#f], implicitly[RealSupertype[Out,Out]])
  }; if (asStep) s else StreamCore.scope(s) }


  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def close: Stream[F,O] = close_(false)

  /** Close this `Pull`, but don't cleanup any resources acquired. */
  private[fs2] def closeAsStep: Stream[F,O] = close_(true)

  def optional: Pull[F,O,Option[R]] =
    map(Some(_)).or(Pull.pure(None))

  /**
   * Consult `p2` if this pull fails due to an `await` on an exhausted `Handle`.
   * If this pull fails due to an error, `p2` is not consulted.
   */
  def or[F2[x]>:F[x],O2>:O,R2>:R](p2: => Pull[F2,O2,R2])(implicit S1: RealSupertype[O,O2], R2: RealSupertype[R,R2]): Pull[F2,O2,R2] = new Pull(
    get.flatMap[AlgebraF[F2,O2]#f,Option[Attempt[R2]]] {
      case Some(Right(r)) => Free.pure(Some(Right(r)))
      case None => attemptPull(p2).get
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  def map[R2](f: R => R2): Pull[F,O,R2] =
    flatMap(f andThen pure)

  def flatMap[F2[x]>:F[x],O2>:O,R2](f: R => Pull[F2,O2,R2]): Pull[F2,O2,R2] = new Pull(
    get.flatMap[AlgebraF[F2,O2]#f,Option[Attempt[R2]]] {
      case Some(Right(r)) => attemptPull(f(r)).get
      case None => Free.pure(None)
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  def filter(f: R => Boolean): Pull[F,O,R] = withFilter(f)

  def withFilter(f: R => Boolean): Pull[F,O,R] =
    flatMap(r => if (f(r)) Pull.pure(r) else Pull.done)

  /** Defined as `p >> p2 == p flatMap { _ => p2 }`. */
  def >>[F2[x]>:F[x],O2>:O,R2](p2: => Pull[F2,O2,R2])(implicit S: RealSupertype[O,O2]): Pull[F2,O2,R2] =
    flatMap { _ => p2 }

  /** Definition: `p as r == p map (_ => r)`. */
  def as[R2](r: R2): Pull[F,O,R2] = map (_ => r)

  def covary[F2[_]](implicit S: Sub1[F,F2]): Pull[F2,O,R] = Sub1.substPull(this)

  override def toString = "Pull"
}

object Pull {

  private sealed trait Algebra[+F[_],+O,+R]
  private object Algebra {
    case class Eval[F[_],O,R](f: Scope[F,R]) extends Algebra[F,O,R]
    case class Output[F[_],O](s: StreamCore[F,O]) extends Algebra[F,O,Unit]
  }

  private sealed trait AlgebraF[F[_],O] { type f[x] = Algebra[F,O,x] }

  /**
   * Acquire a resource within a `Pull`. The cleanup action will be run at the end
   * of the `.close` scope which executes the returned `Pull`. The acquired
   * resource is returned as the result value of the pull.
   */
  def acquire[F[_],R](r: F[R])(cleanup: R => F[Unit]): Pull[F,Nothing,R] =
    acquireCancellable(r)(cleanup).map(_._2)

  /**
   * Like [[acquire]] but the result value is a tuple consisting of a cancellation
   * pull and the acquired resource. Running the cancellation pull frees the resource.
   * This allows the acquired resource to be released earlier than at the end of the
   * containing pull scope.
   */
  def acquireCancellable[F[_],R](r: F[R])(cleanup: R => F[Unit]): Pull[F,Nothing,(Pull[F,Nothing,Unit],R)] =
    Stream.bracketWithToken(r)(Stream.emit, cleanup).open.flatMap { h => h.await1.flatMap {
      case ((token, r), _) => Pull.pure((Pull.release(List(token)), r))
    }}


  def attemptEval[F[_],R](f: F[R]): Pull[F,Nothing,Attempt[R]] =
    new Pull(Free.attemptEval[AlgebraF[F,Nothing]#f,R](Algebra.Eval(Scope.eval(f))).map(e => Some(Right(e))))

  private def attemptPull[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] =
    try p catch { case NonFatal(e) => fail(e) }

  /** Await the next available `Chunk` from the `Handle`. The `Chunk` may be empty. */
  def await[F[_],I]: Handle[F,I] => Pull[F,Nothing,(Chunk[I],Handle[F,I])] =
    _.await

  /** Await the next available nonempty `Chunk`. */
  def awaitNonempty[F[_],I]: Handle[F,I] => Pull[F,Nothing,(Chunk[I],Handle[F,I])] =
    receive { case s @ (hd, tl) => if (hd.isEmpty) awaitNonempty(tl) else Pull.pure(s) }

  /** Await a single element from the `Handle`. */
  def await1[F[_],I]: Handle[F,I] => Pull[F,Nothing,(I,Handle[F,I])] =
    _.await1

  /** Like `await`, but return a `Chunk` of no more than `maxChunkSize` elements. */
  def awaitLimit[F[_],I](maxChunkSize: Int)
    : Handle[F,I] => Pull[F,Nothing,(Chunk[I],Handle[F,I])]
    = _.await.map { case s @ (hd, tl) =>
        if (hd.size <= maxChunkSize) s
        else (hd.take(maxChunkSize), tl.push(hd.drop(maxChunkSize)))
      }

  /** Return a `List[Chunk[I]]` from the input whose combined size has a maximum value `n`. */
  def awaitN[F[_],I](n: Int, allowFewer: Boolean = false)
    : Handle[F,I] => Pull[F,Nothing,(List[Chunk[I]],Handle[F,I])]
    = h =>
        if (n <= 0) Pull.pure((Nil, h))
        else for {
          (hd, tl) <- awaitLimit(n)(h)
          (hd2, tl) <- _awaitN0(n, allowFewer)((hd, tl))
        } yield ((hd :: hd2), tl)
  private def _awaitN0[F[_],I](n: Int, allowFewer: Boolean): ((Chunk[I],Handle[F,I])) => Pull[F, Nothing, (List[Chunk[I]], Handle[F,I])] = {
    case (hd, tl) =>
      val next = awaitN(n - hd.size, allowFewer)(tl)
      if (allowFewer) next.optional.map(_.getOrElse((Nil, Handle.empty))) else next
  }

  /** Await the next available chunk from the input, or `None` if the input is exhausted. */
  def awaitOption[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[(Chunk[I],Handle[F,I])]] =
    h => h.await.map(Some(_)) or Pull.pure(None)

  /** Await the next available element from the input, or `None` if the input is exhausted. */
  def await1Option[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[(I,Handle[F,I])]] =
    h => h.await1.map(Some(_)) or Pull.pure(None)

  /** Await the next available non-empty chunk from the input, or `None` if the input is exhausted. */
  def awaitNonemptyOption[F[_],I]: Handle[F, I] => Pull[F, Nothing, Option[(Chunk[I], Handle[F,I])]] =
    h => awaitNonempty(h).map(Some(_)) or Pull.pure(None)

  /** Copy the next available chunk to the output. */
  def copy[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { (chunk, h) => Pull.output(chunk) >> Pull.pure(h) }

  /** Copy the next available element to the output. */
  def copy1[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive1 { (hd, h) => Pull.output1(hd) >> Pull.pure(h) }

  /** The completed `Pull`. Reads and outputs nothing. */
  def done: Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(None))

  /** Drop the first `n` elements of the input `Handle`, and return the new `Handle`. */
  def drop[F[_], I](n: Long): Handle[F, I] => Pull[F, Nothing, Handle[F, I]] =
    h =>
      if (n <= 0) Pull.pure(h)
      else awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue)(h).flatMap {
        case (chunk, h) => drop(n - chunk.size)(h)
      }

  /**
   * Drop the elements of the input `Handle` until the predicate `p` fails, and return the new `Handle`.
   * If nonempty, the first element of the returned `Handle` will fail `p`.
   */
  def dropWhile[F[_], I](p: I => Boolean): Handle[F,I] => Pull[F,Nothing,Handle[F,I]] =
    receive { (chunk, h) =>
      chunk.indexWhere(!p(_)) match {
        case Some(0) => Pull.pure(h push chunk)
        case Some(i) => Pull.pure(h push chunk.drop(i))
        case None    => dropWhile(p)(h)
      }
    }

  /** Write all inputs to the output of the returned `Pull`. */
  def echo[F[_],I]: Handle[F,I] => Pull[F,I,Nothing] =
    h => echoChunk(h) flatMap (echo)

  /** Read a single element from the input and emit it to the output. Returns the new `Handle`. */
  def echo1[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive1 { (i, h) => Pull.output1(i) >> Pull.pure(h) }

  /** Read the next available chunk from the input and emit it to the output. Returns the new `Handle`. */
  def echoChunk[F[_],I]: Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { (c, h) => Pull.output(c) >> Pull.pure(h) }

  /** Promote an effect to a `Pull`. */
  def eval[F[_],R](f: F[R]): Pull[F,Nothing,R] =
    attemptEval(f) flatMap { _.fold(fail, pure) }

  def evalScope[F[_],R](f: Scope[F,R]): Pull[F,Nothing,R] =
    new Pull(Free.eval[AlgebraF[F,Nothing]#f,R](Algebra.Eval(f)).map(e => Some(Right(e))))

  /** The `Pull` that reads and outputs nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(Some(Left(err))))

  /** Like `[[awaitN]]`, but leaves the buffered input unconsumed. */
  def fetchN[F[_],I](n: Int): Handle[F,I] => Pull[F,Nothing,Handle[F,I]] =
    h => awaitN(n)(h) map { case (buf, h) => buf.reverse.foldLeft(h)(_ push _) }

  /** Await the next available element where the predicate returns true */
  def find[F[_],I](f: I => Boolean): Handle[F,I] => Pull[F,Nothing,(I,Handle[F,I])] =
    receive { (chunk, h) =>
      chunk.indexWhere(f) match {
        case None => find(f).apply(h)
        case Some(i) if i + 1 < chunk.size => Pull.pure((chunk(i), h.push(chunk.drop(i + 1))))
        case Some(i) => Pull.pure((chunk(i), h))
      }
    }

  /**
   * Folds all inputs using an initial value `z` and supplied binary operator, and writes the final
   * result to the output of the supplied `Pull` when the stream has no more values.
   */
  def fold[F[_],I,O](z: O)(f: (O, I) => O): Handle[F,I] => Pull[F,Nothing,O] =
    h => h.await.optional flatMap {
      case Some((c, h)) => fold(c.foldLeft(z)(f))(f)(h)
      case None => Pull.pure(z)
    }

  /**
   * Folds all inputs using the supplied binary operator, and writes the final result to the output of
   * the supplied `Pull` when the stream has no more values.
   */
  def fold1[F[_],I](f: (I, I) => I): Handle[F,I] => Pull[F,Nothing,I] =
    receive1 { (o, h) => fold(o)(f)(h) }

  /** Write a single `true` value if all input matches the predicate, false otherwise */
  def forall[F[_],I](p: I => Boolean): Handle[F,I] => Pull[F,Nothing,Boolean] = {
    h => h.await1.optional flatMap {
      case Some((i, h)) =>
        if (!p(i)) Pull.pure(false)
        else forall(p).apply(h)
      case None => Pull.pure(true)
    }
  }

  /** Return the last element of the input `Handle`, if nonempty. */
  def last[F[_],I]: Handle[F,I] => Pull[F,Nothing,Option[I]] = {
    def go(prev: Option[I]): Handle[F,I] => Pull[F,Nothing,Option[I]] =
      h => h.await.optional.flatMap {
        case None => Pull.pure(prev)
        case Some((c, h)) => go(c.foldLeft(prev)((_,i) => Some(i)))(h)
      }
    go(None)
  }

  /**
   * Repeatedly use the output of the `Pull` as input for the next step of the pull.
   * Halts when a step terminates with `Pull.done` or `Pull.fail`.
   */
  def loop[F[_],W,R](using: R => Pull[F,W,R]): R => Pull[F,W,Nothing] =
    r => using(r) flatMap loop(using)

  /** If `p` terminates with `fail(e)`, invoke `handle(e)`. */
  def onError[F[_],O,R](p: Pull[F,O,R])(handle: Throwable => Pull[F,O,R]): Pull[F,O,R] =
    new Pull(
      p.get.flatMap[AlgebraF[F,O]#f,Option[Attempt[R]]] {
        case Some(Right(r)) => Free.pure(Some(Right(r)))
        case None => Free.pure(None)
        case Some(Left(err)) => attemptPull(handle(err)).get
      }
    )


  /** Write a `Chunk[W]` to the output of this `Pull`. */
  def output[F[_],W](w: Chunk[W]): Pull[F,W,Unit] = outputs(Stream.chunk(w))

  /** Write a single `W` to the output of this `Pull`. */
  def output1[F[_],W](w: W): Pull[F,W,Unit] = outputs(Stream.emit(w))

  /** Write a stream to the output of this `Pull`. */
  def outputs[F[_],O](s: Stream[F,O]): Pull[F,O,Unit] =
    new Pull(Free.eval[AlgebraF[F,O]#f,Unit](Algebra.Output(s.get)).map(_ => Some(Right(()))))

  /** The `Pull` that reads and outputs nothing, and succeeds with the given value, `R`. */
  def pure[R](r: R): Pull[Nothing,Nothing,R] =
    new Pull(Free.pure(Some(Right(r))))

  /**
   * Like `[[await]]`, but runs the `await` asynchronously. A `flatMap` into
   * inner `Pull` logically blocks until this await completes.
   */
  def prefetch[F[_]:Async,I](h: Handle[F,I]): Pull[F,Nothing,Pull[F,Nothing,Handle[F,I]]] =
    h.awaitAsync map { fut =>
      fut.pull flatMap { p =>
        p map { case (hd, h) => h push hd }
      }
    }

  /** Apply `f` to the next available `Chunk`. */
  def receive[F[_],I,O,R](f: (Chunk[I],Handle[F,I]) => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    _.await.flatMap(f.tupled)

  /** Apply `f` to the next available element. */
  def receive1[F[_],I,O,R](f: (I,Handle[F,I]) => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    _.await1.flatMap(f.tupled)

  /** Apply `f` to the next available chunk, or `None` if the input is exhausted. */
  def receiveOption[F[_],I,O,R](f: Option[(Chunk[I],Handle[F,I])] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    awaitOption(_).flatMap(f)

  /** Apply `f` to the next available element, or `None` if the input is exhausted. */
  def receive1Option[F[_],I,O,R](f: Option[(I,Handle[F,I])] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    await1Option(_).flatMap(f)

  def receiveNonemptyOption[F[_],I,O,R](f: Option[(Chunk[I], Handle[F,I])] => Pull[F,O,R]): Handle[F,I] => Pull[F,O,R] =
    awaitNonemptyOption(_).flatMap(f)

  private[fs2] def release(ts: List[Token]): Pull[Nothing,Nothing,Unit] =
    outputs(Stream.mk(StreamCore.release(ts).drain))

  def suspend[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] = Pull.pure(()) flatMap { _ => p }

  /** Emit the first `n` elements of the input `Handle` and return the new `Handle`. */
  def take[F[_],I](n: Long)(h: Handle[F,I]): Pull[F,I,Handle[F,I]] =
    if (n <= 0) Pull.pure(h)
    else Pull.awaitLimit(if (n <= Int.MaxValue) n.toInt else Int.MaxValue)(h).flatMap {
      case (chunk, h) => Pull.output(chunk) >> take(n - chunk.size.toLong)(h)
    }

  /** Emits the last `n` elements of the input. */
  def takeRight[F[_],I](n: Long)(h: Handle[F,I]): Pull[F,Nothing,Vector[I]]  = {
    def go(acc: Vector[I])(h: Handle[F,I]): Pull[F,Nothing,Vector[I]] = {
      Pull.awaitN(if (n <= Int.MaxValue) n.toInt else Int.MaxValue, true)(h).optional.flatMap {
        case None => Pull.pure(acc)
        case Some((cs, h)) =>
          val vector = cs.toVector.flatMap(_.toVector)
          go(acc.drop(vector.length) ++ vector)(h)
      }
    }
    if (n <= 0) Pull.pure(Vector())
    else go(Vector())(h)
  }

  /** Like `takeWhile`, but emits the first value which tests false. */
  def takeThrough[F[_],I](p: I => Boolean): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { (chunk, h) =>
      chunk.indexWhere(!p(_)) match {
        case Some(i) => Pull.output(chunk.take(i+1)) >> Pull.pure(h.push(chunk.drop(i+1)))
        case None => Pull.output(chunk) >> takeThrough(p)(h)
      }
    }

  /**
   * Emit the elements of the input `Handle` until the predicate `p` fails,
   * and return the new `Handle`. If nonempty, the returned `Handle` will have
   * a first element `i` for which `p(i)` is `false`. */
  def takeWhile[F[_],I](p: I => Boolean): Handle[F,I] => Pull[F,I,Handle[F,I]] =
    receive { (chunk, h) =>
      chunk.indexWhere(!p(_)) match {
        case Some(0) => Pull.pure(h.push(chunk))
        case Some(i) => Pull.output(chunk.take(i)) >> Pull.pure(h.push(chunk.drop(i)))
        case None    => Pull.output(chunk) >> takeWhile(p)(h)
      }
    }

  implicit def covaryPure[F[_],W,R](p: Pull[Pure,W,R]): Pull[F,W,R] = p.covary[F]
}
