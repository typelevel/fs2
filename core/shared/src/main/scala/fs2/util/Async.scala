package fs2
package util

import fs2.util.syntax._

@annotation.implicitNotFound("No implicit `Async[${F}]` found.\nNote that the implicit `Async[fs2.Task]` requires an implicit `fs2.Strategy` in scope.")
trait Async[F[_]] extends Effect[F] { self =>

  /** Create an asynchronous, concurrent mutable reference. */
  def ref[A]: F[Async.Ref[F,A]]

  /** Create an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[A](a: A): F[Async.Ref[F,A]] = flatMap(ref[A])(r => map(r.setPure(a))(_ => r))

  /**
   Create an `F[A]` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version.
   */
  // Note: `register` does not use the `Attempt` alias due to scalac inference limitation
  def async[A](register: (Either[Throwable,A] => Unit) => F[Unit]): F[A] =
    flatMap(ref[A]) { ref =>
    flatMap(register { e => unsafeRunAsync(ref.set(e.fold(fail, pure)))(_ => ()) }) { _ => ref.get }}

  def parallelTraverse[A,B](s: Seq[A])(f: A => F[B]): F[Vector[B]] =
    flatMap(traverse(s)(f andThen start)) { tasks => traverse(tasks)(identity) }

  def parallelSequence[A](v: Seq[F[A]]): F[Vector[A]] =
    parallelTraverse(v)(identity)

  /**
   * Begin asynchronous evaluation of `f` when the returned `F[F[A]]` is
   * bound. The inner `F[A]` will block until the result is available.
   */
  def start[A](f: F[A]): F[F[A]] =
    flatMap(ref[A]) { ref =>
    flatMap(ref.set(f)) { _ => pure(ref.get) }}
}

object Async {

  /** Create an asynchronous, concurrent mutable reference. */
  def ref[F[_],A](implicit F:Async[F]): F[Async.Ref[F,A]] = F.ref

  /** Create an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[F[_],A](a: A)(implicit F:Async[F]): F[Async.Ref[F,A]] = F.refOf(a)

  /** An asynchronous, concurrent mutable reference. */
  trait Ref[F[_],A] { self =>
    implicit protected val F: Async[F]

    /**
     * Obtain a snapshot of the current value of the `Ref`, and a setter
     * for updating the value. The setter may noop (in which case `false`
     * is returned) if another concurrent call to `access` uses its
     * setter first. Once it has noop'd or been used once, a setter
     * never succeeds again.
     */
    def access: F[(A, Attempt[A] => F[Boolean])]

    /** Obtain the value of the `Ref`, or wait until it has been `set`. */
    def get: F[A] = access.map(_._1)

    /** Like `get`, but returns an `F[Unit]` that can be used cancel the subscription. */
    def cancellableGet: F[(F[A], F[Unit])]

    /**
     * Try modifying the reference once, returning `None` if another
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

    /** Repeatedly invoke `[[tryModify]](f)` until it succeeds. */
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
     * Asynchronously set a reference. After the returned `F[Unit]` is bound,
     * the task is running in the background. Multiple tasks may be added to a
     * `Ref[A]`.
     *
     * Satisfies: `r.set(t) flatMap { _ => r.get } == t`.
     */
    def set(a: F[A]): F[Unit]

    /**
     * Asynchronously set a reference to a pure value.
     *
     * Satisfies: `r.setPure(a) flatMap { _ => r.get(a) } == pure(a)`.
     */
    def setPure(a: A): F[Unit] = set(F.pure(a))
  }

  /**
   * The result of a `Ref` modification. `previous` contains value before modification
   * (the value passed to modify function, `f` in the call to `modify(f)`. And `now`
   * is the new value computed by `f`.
   */
  final case class Change[+A](previous: A, now: A) {
    def modified: Boolean = previous != now
    def map[B](f: A => B): Change[B] = Change(f(previous), f(now))
  }
}
