package fs2.async

import java.util.concurrent.atomic.AtomicReference

import cats.effect.Sync

import scala.annotation.tailrec

/**
 * Lightweight alternative to [[Ref]] backed by an `AtomicReference`.
 *
 * `SyncRef` is less powerful than `Ref` but is significantly faster and only requires a `Sync[F]` instance,
 * as opposed to `Ref`, which requires an `Effect[F]` instance.
 *
 * Unlike `Ref`, a `SyncRef` must be initialized to a value.
 */
final class SyncRef[F[_], A] private[fs2] (private val ar: AtomicReference[A])(implicit F: Sync[F]) extends RefOps[F,A] {

  override def get: F[A] = F.delay(ar.get)

  override def setAsyncPure(a: A): F[Unit] = F.delay(ar.lazySet(a))
  override def setSyncPure(a: A): F[Unit] = F.delay(ar.set(a))

  override def tryModify(f: A => A): F[Option[Change[A]]] = F.delay {
    val c = ar.get
    val u = f(c)
    if (ar.compareAndSet(c, u)) Some(Change(c, u))
    else None
  }

  override def tryModify2[B](f: A => (A,B)): F[Option[(Change[A], B)]] = F.delay {
    val c = ar.get
    val (u,b) = f(c)
    if (ar.compareAndSet(c, u)) Some(Change(c, u) -> b)
    else None
  }

  override def modify(f: A => A): F[Change[A]] = {
    @tailrec
    def spin: Change[A] = {
      val c = ar.get
      val u = f(c)
      if (!ar.compareAndSet(c, u)) spin
      else Change(c, u)
    }
    F.delay(spin)
  }

  override def modify2[B](f: A => (A, B)): F[(Change[A], B)] = {
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

object SyncRef {
  /** Creates an asynchronous, concurrent mutable reference initialized to the supplied value. */
  def apply[F[_], A](a: A)(implicit F: Sync[F]): F[SyncRef[F, A]] =
    F.delay(new SyncRef[F, A](new AtomicReference[A](a)))
}
