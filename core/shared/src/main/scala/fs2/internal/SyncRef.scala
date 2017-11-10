package fs2.internal

import java.util.concurrent.atomic.AtomicReference

import cats.effect.Sync
import fs2.async.Ref.Change

import scala.annotation.tailrec

/**
  * Reference to `A` that supports concurrent, but only synchronous operations.
  *
  * Think of it as an java AtomicReference wrapped in `F`
  *
  */
final class SyncRef[F[_], A] (private val ar: AtomicReference[A])(implicit F: Sync[F]) {

  /** gets current value of `A` **/
  def get: F[A] = F.delay { ar.get }

  /** sets unconditionally `a` **/
  def set(a: A): F[Unit] = F.delay { ar.set(a) }

  /** gets and then sets the `a` returning previous value **/
  def getAndSet(a: A): F[A] = F.delay { ar.getAndSet(a) }

  /**
    * Modifies the reference, returning the modified change as result.
    * Note that this may take few attempts before the reference is updated in high contention environment.
    */
  def modify(f: A => A): F[Change[A]] = {
    @tailrec
    def go(): Change[A] = {
      val c = ar.get()
      val u = f(c)
      if (! ar.compareAndSet(c, u)) go
      else Change(c, u)
    }
    F.delay { go() }
  }

  /**
    * Like `modify` but allows to return result as part of the modifying the reference
    */
  def modify2[B](f: A => (A, B)): F[(Change[A], B)] = {
    @tailrec
    def go(): (Change[A], B) = {
      val c = ar.get()
      val (u,b) = f(c)
      if (! ar.compareAndSet(c, u)) go
      else (Change(c, u), b)
    }
    F.delay { go() }
  }


}


object SyncRef {

  def apply[F[_] : Sync, A](a: A): SyncRef[F, A] =
    new SyncRef[F, A](new AtomicReference[A](a))

}
