package fs2.async

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import cats.{Eq, Show}
import cats.effect.Sync
import cats.implicits._

import scala.annotation.tailrec
import Ref._

/**
  * An asynchronous, concurrent mutable reference.
  *
  * Provides safe concurrent access and modification of its content, but no
  * functionality for synchronisation, which is instead handled by [[Promise]].
  * For this reason, a `Ref` is always initialised to a value.
  *
  * The implementation is nonblocking and lightweight, consisting essentially of
  * a purely functional wrapper over an `AtomicReference`
  */
final class Ref[F[_], A] private[fs2] (private val ar: AtomicReference[A])(implicit F: Sync[F]) {

  /**
    * Obtains the current value.
    *
    * Since `Ref` is always guaranteed to have a value, the returned action
    * completes immediately after being bound.
    */
  def get: F[A] = F.delay(ar.get)

  /**
    * *Synchronously* sets the current value to `a`.
    *
    * The returned action completes after the reference has been successfully set.
    *
    * Satisfies:
    *   `r.setSync(fa) *> r.get == fa`
    */
  def setSync(a: A): F[Unit] = F.delay(ar.set(a))

  /**
    * *Asynchronously* sets the current value to the `a`
    *
    * After the returned `F[Unit]` is bound, an update will eventually occur,
    * setting the current value to `a`.
    *
    * Satisfies:
    *   `r.setAsync(fa) == async.fork(r.setSync(a))`
    * but it's significantly faster.
    */
  def setAsync(a: A): F[Unit] = F.delay(ar.lazySet(a))

  /**
    * Obtains a snapshot of the current value, and a setter for updating it.
    * The setter may noop (in which case `false` is returned) if another concurrent
    * call to `access` uses its setter first.
    *
    * Once it has noop'd or been used once, a setter never succeeds again.
    *
    * Satisfies:
    *   `r.access.map(_._1) == r.get`
    *   `r.access.flatMap { case (v, setter) => setter(f(v)) } == r.tryModify(f).map(_.isDefined)`
    */
  def access: F[(A, A => F[Boolean])] = F.delay {
    val snapshot = ar.get
    val hasBeenCalled = new AtomicBoolean(false)
    def setter =
      (a: A) =>
        F.delay {
          if (hasBeenCalled.compareAndSet(false, true))
            ar.compareAndSet(snapshot, a)
          else
            false
      }

    (snapshot, setter)
  }

  /**
    * Attempts to modify the current value once, returning `None` if another
    * concurrent modification completes between the time the variable is
    * read and the time it is set.
    */
  def tryModify(f: A => A): F[Option[Change[A]]] = F.delay {
    val c = ar.get
    val u = f(c)
    if (ar.compareAndSet(c, u)) Some(Change(c, u))
    else None
  }

  /** Like `tryModify` but allows returning a `B` along with the update. */
  def tryModify2[B](f: A => (A, B)): F[Option[(Change[A], B)]] = F.delay {
    val c = ar.get
    val (u, b) = f(c)
    if (ar.compareAndSet(c, u)) Some(Change(c, u) -> b)
    else None
  }

  /**
    * Like `tryModify` but does not complete until the update has been successfully made.
    *
    * Satisfies:
    *   `r.modify(_ => a).void == r.setSync(a)`
    */
  def modify(f: A => A): F[Change[A]] = {
    @tailrec
    def spin: Change[A] = {
      val c = ar.get
      val u = f(c)
      if (!ar.compareAndSet(c, u)) spin
      else Change(c, u)
    }
    F.delay(spin)
  }

  /** Like `modify` but allows returning a `B` along with the update. */
  def modify2[B](f: A => (A, B)): F[(Change[A], B)] = {
    @tailrec
    def spin: (Change[A], B) = {
      val c = ar.get
      val (u, b) = f(c)
      if (!ar.compareAndSet(c, u)) spin
      else (Change(c, u), b)
    }
    F.delay(spin)
  }
}

object Ref {

  /** Creates an asynchronous, concurrent mutable reference initialized to the supplied value. */
  def apply[F[_], A](a: A)(implicit F: Sync[F]): F[Ref[F, A]] =
    F.delay(unsafeCreate(a))

  private[fs2] def unsafeCreate[F[_]: Sync, A](a: A): Ref[F, A] =
    new Ref[F, A](new AtomicReference[A](a))

  /**
    * The result of a modification to a [[Ref]]
    *
    * `previous` is the value before modification (i.e., the value passed to modify
    * function, `f` in the call to `modify(f)`. `now` is the new value computed by `f`.
    */
  final case class Change[+A](previous: A, now: A) {
    def map[B](f: A => B): Change[B] = Change(f(previous), f(now))
  }

  object Change {
    implicit def eqInstance[A: Eq]: Eq[Change[A]] =
      Eq.by(c => c.previous -> c.now)

    implicit def showInstance[A: Show]: Show[Change[A]] =
      Show(c => show"Change(previous: ${c.previous}, now: ${c.now})")
  }
}
