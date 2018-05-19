package fs2
package async
package mutable

import scala.concurrent.ExecutionContext

import cats.{Applicative, Functor}
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._

import fs2.Stream._

/** Data type of a single value of type `A` that can be read and written in the effect `F`. */
abstract class Signal[F[_], A] extends immutable.Signal[F, A] { self =>

  /** Sets the value of this `Signal`. */
  def set(a: A): F[Unit]

  /**
    * Modifies the current value using the supplied update function. If another modification
    * occurs between the time the current value is read and subsequently updated, the modification
    * is retried using the new value. Hence, `f` may be invoked multiple times.
    *
    * Satisfies:
    *   `r.modify(_ => a) == r.set(a)`
    */
  def modify(f: A => A): F[Unit]

  /**
    * Like [[modify]] but allows the update function to return an output value of type `B`
    */
  def modifyAndReturn[B](f: A => (A, B)): F[B]

  /**
    * Asynchronously refreshes the value of the signal,
    * keeping the value of this `Signal` the same, but notifing any listeners.
    */
  def refresh: F[Unit]

  /**
    * Returns an alternate view of this `Signal` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Signal[F, B] =
    new Signal[F, B] {
      def discrete: Stream[F, B] = self.discrete.map(f)
      def continuous: Stream[F, B] = self.continuous.map(f)
      def get: F[B] = self.get.map(f)
      def set(b: B): F[Unit] = self.set(g(b))
      def refresh: F[Unit] = self.refresh
      def modify(bb: B => B): F[Unit] =
        modifyAndReturn(b => (bb(b), ()))
      def modifyAndReturn[B2](bb: B => (B, B2)): F[B2] =
        self
          .modifyAndReturn { a =>
            val (a2, b2) = bb(f(a))
            g(a2) -> b2
          }
    }
}

object Signal {

  def constant[F[_], A](a: A)(implicit F: Applicative[F]): immutable.Signal[F, A] =
    new immutable.Signal[F, A] {
      def get = F.pure(a)
      def continuous = Stream.constant(a)
      def discrete = Stream.empty // never changes, so never any updates
    }

  def apply[F[_], A](initA: A)(implicit F: Concurrent[F], ec: ExecutionContext): F[Signal[F, A]] = {
    class ID

    Ref[F, (A, Long, Map[ID, Deferred[F, (A, Long)]])]((initA, 0, Map.empty))
      .map { state =>
        new Signal[F, A] {
          def refresh: F[Unit] = modify(identity)
          def set(a: A): F[Unit] = modify(_ => a)
          def get: F[A] = state.get.map(_._1)
          def modify(f: A => A): F[Unit] =
            modifyAndReturn(a => (f(a), ()))
          def modifyAndReturn[B](f: A => (A, B)): F[B] =
            state.modifyAndReturn {
              case (a, updates, listeners) =>
                val (newA, result) = f(a)
                val newUpdates = updates + 1
                val newState = (newA, newUpdates, Map.empty[ID, Deferred[F, (A, Long)]])
                val action = listeners.toVector.traverse {
                  case (_, deferred) =>
                    async.fork(deferred.complete(newA -> newUpdates))
                }

                newState -> (action *> result.pure[F])
            }.flatten

          def continuous: Stream[F, A] =
            Stream.repeatEval(get)

          def discrete: Stream[F, A] = {
            def go(id: ID, lastUpdate: Long): Stream[F, A] = {
              def getNext: F[(A, Long)] =
                Deferred[F, (A, Long)]
                  .flatMap { deferred =>
                    state.modifyAndReturn {
                      case s @ (a, updates, listeners) =>
                        if (updates != lastUpdate) s -> (a -> updates).pure[F]
                        else (a, updates, listeners + (id -> deferred)) -> deferred.get
                    }.flatten
                  }

              eval(getNext).flatMap { case (a, l) => emit(a) ++ go(id, l) }
            }

            def cleanup(id: ID): F[Unit] =
              state.modify(s => s.copy(_3 = s._3 - id))

            bracket(F.delay(new ID))(
              id =>
                eval(state.get).flatMap {
                  case (a, l, _) => emit(a) ++ go(id, l)
              },
              id => cleanup(id)
            )
          }
        }
      }
  }
}
