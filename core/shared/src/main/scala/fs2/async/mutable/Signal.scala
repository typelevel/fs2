package fs2
package async
package mutable

import scala.concurrent.ExecutionContext

import cats.{ Eq, Applicative, Functor }
import cats.effect.Effect
import cats.implicits._

import fs2.Stream._

/** Data type of a single value of type `A` that can be read and written in the effect `F`. */
abstract class Signal[F[_], A] extends immutable.Signal[F, A] { self =>

  /** Sets the value of this `Signal`. */
  def set(a: A): F[Unit]

  /**
   * Asynchronously sets the current value of this `Signal` and returns new value of this `Signal`.
   *
   * `f` is consulted to set this signal.
   *
   * `F` returns the result of applying `op` to current value.
   */
  def modify(f: A => A): F[Ref.Change[A]]

  /**
   * Like [[modify]] but allows extraction of a `B` from `A` and returns it along with the `Change`.
   */
  def modify2[B](f: A => (A,B)):F[(Ref.Change[A], B)]

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
      def modify(bb: B => B): F[Ref.Change[B]] = modify2( b => (bb(b),()) ).map(_._1)
      def modify2[B2](bb: B => (B,B2)):F[(Ref.Change[B], B2)] =
        self.modify2 { a =>   val (a2, b2) = bb(f(a)) ; g(a2) -> b2 }
        .map { case (Ref.Change(prev, now),b2) => Ref.Change(f(prev), f(now)) -> b2 }
    }
}

object Signal {

  def constant[F[_],A](a: A)(implicit F: Applicative[F]): immutable.Signal[F,A] = new immutable.Signal[F,A] {
    def get = F.pure(a)
    def continuous = Stream.constant(a)
    def discrete = Stream.empty // never changes, so never any updates
  }

  def apply[F[_],A](initA: A)(implicit F: Effect[F], ec: ExecutionContext): F[Signal[F,A]] = {
    class ID
    async.refOf[F, (A, Long, Map[ID, Ref[F, (A, Long)]])]((initA, 0, Map.empty)).map {
    state => new Signal[F,A] {
      def refresh: F[Unit] = modify(identity).as(())
      def set(a: A): F[Unit] = modify(_ => a).as(())
      def get: F[A] = state.get.map(_._1)
      def modify(f: A => A): F[Ref.Change[A]] = modify2( a => (f(a), ()) ).map(_._1)
      def modify2[B](f: A => (A,B)):F[(Ref.Change[A], B)] = {
        state.modify2 { case (a, l, _) =>
          val (a0, b) = f(a)
          (a0, l+1, Map.empty[ID, Ref[F, (A, Long)]]) -> b
        }.flatMap { case (c, b) =>
          if (c.previous._3.isEmpty) F.pure(c.map(_._1) -> b)
          else {
            val now = c.now._1 -> c.now._2
            c.previous._3.toVector.traverse { case(_, ref) => ref.setAsyncPure(now) } *> F.pure(c.map(_._1) -> b)
          }
        }
      }

      def continuous: Stream[F, A] =
        Stream.repeatEval(get)

      def discrete: Stream[F, A] = {
        def go(id: ID, last: Long): Stream[F, A] = {
          def getNext: F[(A, Long)] = {
            async.ref[F, (A, Long)] flatMap { ref =>
              state.modify { case s@(a, l, listen) =>
                if (l != last) s
                else (a, l, listen + (id -> ref))
              } flatMap { c =>
                if (c.modified(Eq.fromUniversalEquals)) ref.get
                else F.pure((c.now._1, c.now._2))
              }
            }
          }
          eval(getNext).flatMap { case (a, l) => emit(a) ++ go(id, l) }
        }

        def cleanup(id: ID): F[Unit] = state.modify { s => s.copy(_3 = s._3 - id) }.as(())

        bracket(F.delay(new ID))(
          { id => eval(state.get).flatMap { case (a, l, _) => emit(a) ++ go(id, l) } }
          , id => cleanup(id)
        )
      }}
    }}
}
