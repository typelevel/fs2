package fs2.async.mutable

import fs2.{Strategy, Async}

import fs2.async.immutable

/**
 * Created by pach on 10/10/15.
 */
/**
 * A signal whose value may be set asynchronously. Provides continuous
 * and discrete streams for responding to changes to this value.
 */
trait Signal[F[_],A] extends immutable.Signal[F,A] {


  /**
   * Asynchronously refreshes the value of the signal,
   * keep the value of this `Signal` the same, but notify any listeners.
   *
   */
  def refresh:F[Unit]

  /**
   * Sets the value of this `Signal`.
   *
   */
  def set(a: A): F[Unit]

  /**
   * Asynchronously sets the current value of this `Signal` and returns previous value of the `Signal`.
   *
   */
   def getAndSet(a:A) : F[A]

  /**
   * Asynchronously sets the current value of this `Signal` and returns new value of this `Signal`.
   *
   * `op` is consulted to set this signal. It is supplied with current value to either
   * set the value (returning Some) or no-op (returning None)
   *
   * `F` returns the result of applying `op` to current value.
   *
   */
   def compareAndSet(op: A => Option[A]) : F[Option[A]]


}


object Signal {


  def apply[F[_],A](initial:A)(implicit F:Async[F]):F[Signal[F,A]] = {
    F.bind(F.ref[A]) { ref =>
    F.map(F.set(ref)(F.pure(initial))) { _ =>
      new Signal[F,A] {
        override def refresh: F[Unit] = ???
        override def set(a: A): F[Unit] = F.set(ref)(F.pure(a))
        override def get: F[A] = F.get(ref)
        override def compareAndSet(op: (A) => Option[A]): F[Option[A]] = ???
        override def getAndSet(a: A): F[A] = ???
        override def changes: fs2.Stream[F, Boolean] = ???
        override def continuous: fs2.Stream[F, A] = fs2.Stream.eval(F.get(ref))
        override def discrete: fs2.Stream[F, A] = ???
        override def changed: fs2.Stream[F, Boolean] = ???
      }
    }}





  }



}
