package fs2.async


import java.util.concurrent.atomic.AtomicReference

import cats.{Eq, Show}
import cats.effect.Sync
import cats.implicits._

import scala.annotation.tailrec

import Ref._

// TODO Change scaladoc description, add methods scaladoc
/**
 * Lightweight alternative to [[Ref]] backed by an `AtomicReference`.
 *
 * `SyncRef` is less powerful than `Ref` but is significantly faster and only requires a `Sync[F]` instance,
 * as opposed to `Ref`, which requires an `Effect[F]` instance.
 *
 * Unlike `Ref`, a `SyncRef` must be initialized to a value.
 */
final class Ref[F[_], A] private[fs2] (private val ar: AtomicReference[A])(implicit F: Sync[F]) {

  def get: F[A] = F.delay(ar.get)

  def setSync(a: A): F[Unit] = F.delay(ar.set(a))
  def setAsync(a: A): F[Unit] = F.delay(ar.lazySet(a))

  /**
   * Obtains a snapshot of the current value of the `Ref`, and a setter
   * for updating the value. The setter may noop (in which case `false`
   * is returned) if another concurrent call to `access` uses its
   * setter first. Once it has noop'd or been used once, a setter
   * never succeeds again.
   */
  def access: F[(A, A => F[Boolean])] = F.delay {
    def snapshot = ar.get
    def setter = (a: A) => F.delay(ar.compareAndSet(snapshot, a))

    (snapshot, setter)
  }

  def tryModify(f: A => A): F[Option[Change[A]]] = F.delay {
    val c = ar.get
    val u = f(c)
    if (ar.compareAndSet(c, u)) Some(Change(c, u))
    else None
  }

  def tryModify2[B](f: A => (A,B)): F[Option[(Change[A], B)]] = F.delay {
    val c = ar.get
    val (u,b) = f(c)
    if (ar.compareAndSet(c, u)) Some(Change(c, u) -> b)
    else None
  }

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

  def modify2[B](f: A => (A, B)): F[(Change[A], B)] = {
    @tailrec
    def spin: (Change[A], B) = {
      val c = ar.get
      val (u,b) = f(c)
      if (!ar.compareAndSet(c, u)) spin
      else (Change(c, u), b)
    }
    F.delay(spin)
  }
}

object Ref {
  /** Creates an asynchronous, concurrent mutable reference initialized to the supplied value. */
  def apply[F[_], A](a: A)(implicit F: Sync[F]): F[Ref[F, A]] =
    F.delay(new Ref[F, A](new AtomicReference[A](a)))

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
