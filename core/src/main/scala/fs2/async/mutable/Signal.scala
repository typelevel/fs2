package fs2
package async
package mutable

import fs2.Stream

import fs2.async.immutable
import fs2.util.Monad
import fs2.internal.LinkedMap

/**
 * A signal whose value may be set asynchronously. Provides continuous
 * and discrete streams for responding to changes to it's value.
 */
trait Signal[F[_],A] extends immutable.Signal[F,A] {

  /** Sets the value of this `Signal`. */
  def set(a: A): F[Unit]

  /**
   * Asynchronously sets the current value of this `Signal` and returns new value of this `Signal`.
   *
   * `op` is consulted to set this signal. It is supplied with current value to either
   * set the value (returning Some) or no-op (returning None)
   *
   * `F` returns the result of applying `op` to current value.
   */
   def possiblyModify(op: A => Option[A]): F[Option[A]]

  /**
   * Asynchronously refreshes the value of the signal,
   * keep the value of this `Signal` the same, but notify any listeners.
   */
  def refresh: F[Unit]
}

object Signal {

  def constant[F[_],A](a: A)(implicit F: Monad[F]): immutable.Signal[F,A] = new immutable.Signal[F,A] {
    def get = F.pure(a)
    def continuous = Stream.constant(a)
    def discrete = Stream.empty // never changes, so never any updates
    def changes = Stream.empty
  }

  private class ID

  def apply[F[_],A](initA: A)(implicit F: Async[F]): F[Signal[F,A]] =
    F.map(F.refOf[(A, Long, LinkedMap[ID, Semaphore[F]])]((initA,0,LinkedMap.empty))) {
    state => new Signal[F,A] {
      def refresh: F[Unit] = F.map(possiblyModify(a => Some(a)))(_ => ())
      def set(a: A): F[Unit] = F.map(possiblyModify(_ => Some(a)))(_ => ())
      def get: F[A] = F.map(F.get(state))(_._1)
      def possiblyModify(f: A => Option[A]): F[Option[A]] =
        F.bind(F.modify(state) { state => f(state._1) match {
          case None => state
          case Some(a2) => (a2, state._2 + 1, state._3)
        }}) { c =>
          if (c.now._2 != c.previous._2) { // set was successful
            F.map(F.traverse(c.now._3.values.toVector) { semaphore =>
              F.bind(semaphore.tryDecrement) { _ => semaphore.increment }
            }) { (vs: Vector[Unit]) => Some(c.now._1) }
          }
          else F.pure(None)
        }

        def changes: Stream[F, Unit] =
          discrete.map(_ => ())

        def continuous: Stream[F, A] =
          Stream.repeatEval(get)

        def discrete: Stream[F, A] = {
          val s: F[(ID, Semaphore[F])] =
            F.bind(Semaphore(1)) { s =>
            val id = new ID {}
            F.map(F.modify(state)(state => state.copy(_3 = state._3.updated(id, s)))) { _ =>
              (id, s)
            }}
          Stream.bracket(s)(
            { case (id,s) => Stream.repeatEval(s.decrement).flatMap(_ => Stream.eval(get)) },
            { case (id,s) => F.map(F.modify(state)(state => state.copy(_3 = state._3 - id))) {
              _ => () }}
          )
        }
      }
    }
}
