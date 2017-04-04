package fs2
package util

import fs2.util.syntax._

/**
 * Type class which describes effects that support asynchronous evaluation.
 *
 * Instances of this type class are defined by providing an implementation of `ref`,
 * which allocates a mutable memory cell that supports various asynchronous operations.
 *
 * For infix syntax, import `fs2.util.syntax._`.
 */
trait Async[F[_]] extends Effect[F] { self =>

  /** Creates an asynchronous, concurrent mutable reference. */
  def ref[A]: F[Async.Ref[F,A]]

  /** Creates an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[A](a: A): F[Async.Ref[F,A]] = flatMap(ref[A])(r => map(r.setPure(a))(_ => r))

  /**
   * Creates an `F[A]` from an asynchronous computation, which takes the form
   * of a function with which we can register a callback. This can be used
   * to translate from a callback-based API to a straightforward monadic
   * version.
   */
  // Note: `register` does not use the `Attempt` alias due to scalac inference limitation
  def async[A](register: (Either[Throwable,A] => Unit) => F[Unit]): F[A] =
    flatMap(ref[A]) { ref =>
    flatMap(register { e => unsafeRunAsync(ref.set(e.fold(fail, pure)))(_ => ()) }) { _ => ref.get }}

  /** Like `traverse` but each `F[B]` computed from an `A` is evaluated in parallel. */
  def parallelTraverse[G[_],A,B](g: G[A])(f: A => F[B])(implicit G: Traverse[G]): F[G[B]] =
    flatMap(G.traverse(g)(f andThen start)(self)) { _.sequence(G, self) }

  /** Like `sequence` but each `F[A]` is evaluated in parallel. */
  def parallelSequence[G[_],A](v: G[F[A]])(implicit G: Traverse[G]): F[G[A]] =
    parallelTraverse(v)(identity)

  /**
   * Begins asynchronous evaluation of `f` when the returned `F[F[A]]` is
   * bound. The inner `F[A]` will block until the result is available.
   */
  def start[A](f: F[A]): F[F[A]] =
    flatMap(ref[A]) { ref =>
    flatMap(ref.set(f)) { _ => pure(ref.get) }}
}

object Async {
  def apply[F[_]](implicit F: Async[F]): Async[F] = F

  /** Creates an asynchronous, concurrent mutable reference. */
  def ref[F[_],A](implicit F:Async[F]): F[Async.Ref[F,A]] = F.ref

  /** Creates an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[F[_],A](a: A)(implicit F:Async[F]): F[Async.Ref[F,A]] = F.refOf(a)

  /** An asynchronous, concurrent mutable reference. */
  trait Ref[F[_],A] { self =>
    implicit protected val F: Async[F]

    /**
     * Obtains a snapshot of the current value of the `Ref`, and a setter
     * for updating the value. The setter may noop (in which case `false`
     * is returned) if another concurrent call to `access` uses its
     * setter first. Once it has noop'd or been used once, a setter
     * never succeeds again.
     */
    def access: F[(A, Attempt[A] => F[Boolean])]

    /** Obtains the value of the `Ref`, or wait until it has been `set`. */
    def get: F[A] = access.map(_._1)

    /** Like `get`, but returns an `F[Unit]` that can be used cancel the subscription. */
    def cancellableGet: F[(F[A], F[Unit])]

    /**
     * Tries modifying the reference once, returning `None` if another
     * concurrent `set` or `modify` completes between the time
     * the variable is read and the time it is set.
     */
    def tryModify(f: A => A): F[Option[Change[A]]] =
      access.flatMap { case (previous,set) =>
        val now = f(previous)
        set(Right(now)).map { b =>
          if (b) Some(Change(previous, now))
          else None
        }
      }

    /** Like `tryModify` but allows to return `B` along with change. **/
    def tryModify2[B](f: A => (A,B)): F[Option[(Change[A], B)]] =
      access.flatMap { case (previous,set) =>
        val (now,b0) = f(previous)
        set(Right(now)).map { b =>
          if (b) Some(Change(previous, now) -> b0)
          else None
        }
    }

    /** Repeatedly invokes `[[tryModify]](f)` until it succeeds. */
    def modify(f: A => A): F[Change[A]] =
      tryModify(f).flatMap {
        case None => modify(f)
        case Some(change) => F.pure(change)
      }

    /** Like modify, but allows to extra `b` in single step. **/
    def modify2[B](f: A => (A,B)): F[(Change[A], B)] =
      tryModify2(f).flatMap {
        case None => modify2(f)
        case Some(changeAndB) => F.pure(changeAndB)
      }

    /**
     * *Asynchronously* sets a reference. After the returned `F[Unit]` is bound,
     * the task is running in the background. Multiple tasks may be added to a
     * `Ref[A]`.
     *
     * Satisfies: `r.set(t) flatMap { _ => r.get } == t`.
     */
    def set(a: F[A]): F[Unit]

    /**
     * *Asynchronously* sets a reference to a pure value.
     *
     * Satisfies: `r.setPure(a) flatMap { _ => r.get(a) } == pure(a)`.
     */
    def setPure(a: A): F[Unit] = set(F.pure(a))
  }

  /**
   * The result of a `Ref` modification. `previous` is the value before modification
   * (the value passed to modify function, `f` in the call to `modify(f)`. `now`
   * is the new value computed by `f`.
   */
  final case class Change[+A](previous: A, now: A) {
    def modified: Boolean = previous != now
    def map[B](f: A => B): Change[B] = Change(f(previous), f(now))
  }
}
