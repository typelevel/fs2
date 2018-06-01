package fs2
package async
package mutable

import cats.{Applicative, Functor, Invariant}
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import fs2.Stream._
import fs2.internal.Token

/** Data type of a single value of type `A` that can be read and written in the effect `F`. */
abstract class Signal[F[_], A] extends immutable.Signal[F, A] { self =>

  /** Sets the value of this `Signal`. */
  def set(a: A): F[Unit]

  /**
    * Updates the current value using the supplied update function. If another modification
    * occurs between the time the current value is read and subsequently updated, the modification
    * is retried using the new value. Hence, `f` may be invoked multiple times.
    *
    * Satisfies:
    *   `r.modify(_ => a) == r.set(a)`
    */
  def update(f: A => A): F[Unit]

  /**
    * Like [[update]] but allows the update function to return an output value of type `B`
    */
  def modify[B](f: A => (A, B)): F[B]

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
      def update(bb: B => B): F[Unit] =
        modify(b => (bb(b), ()))
      def modify[B2](bb: B => (B, B2)): F[B2] =
        self
          .modify { a =>
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

      /**
        * We put a single element here because otherwise the implementations of
        * Signal as a Monad or Applicative get more annoying. In particular if
        * this stream were empty, Applicatively zipping another Signal in the
        * straightforward way would cause the (non-deterministically) zipped
        * stream to be empty.
        */
      def discrete = Stream(a)
    }

  def apply[F[_], A](initA: A)(implicit F: Concurrent[F]): F[Signal[F, A]] =
    Ref
      .of[F, (A, Long, Map[Token, Deferred[F, (A, Long)]])]((initA, 0, Map.empty))
      .map { state =>
        new Signal[F, A] {
          def refresh: F[Unit] = update(identity)
          def set(a: A): F[Unit] = update(_ => a)
          def get: F[A] = state.get.map(_._1)
          def update(f: A => A): F[Unit] =
            modify(a => (f(a), ()))
          def modify[B](f: A => (A, B)): F[B] =
            state.modify {
              case (a, updates, listeners) =>
                val (newA, result) = f(a)
                val newUpdates = updates + 1
                val newState = (newA, newUpdates, Map.empty[Token, Deferred[F, (A, Long)]])
                val action = listeners.toVector.traverse {
                  case (_, deferred) =>
                    F.start(deferred.complete(newA -> newUpdates))
                }

                newState -> (action *> result.pure[F])
            }.flatten

          def continuous: Stream[F, A] =
            Stream.repeatEval(get)

          def discrete: Stream[F, A] = {
            def go(id: Token, lastUpdate: Long): Stream[F, A] = {
              def getNext: F[(A, Long)] =
                Deferred[F, (A, Long)]
                  .flatMap { deferred =>
                    state.modify {
                      case s @ (a, updates, listeners) =>
                        if (updates != lastUpdate) s -> (a -> updates).pure[F]
                        else (a, updates, listeners + (id -> deferred)) -> deferred.get
                    }.flatten
                  }

              eval(getNext).flatMap { case (a, l) => emit(a) ++ go(id, l) }
            }

            def cleanup(id: Token): F[Unit] =
              state.update(s => s.copy(_3 = s._3 - id))

            bracket(F.delay(new Token))(cleanup).flatMap { id =>
              eval(state.get).flatMap {
                case (a, l, _) => emit(a) ++ go(id, l)
              }
            }
          }
        }
      }

  implicit def invariantInstance[F[_]: Functor]: Invariant[Signal[F, ?]] =
    new Invariant[Signal[F, ?]] {
      override def imap[A, B](fa: Signal[F, A])(f: A => B)(g: B => A): Signal[F, B] = fa.imap(f)(g)
    }
}
