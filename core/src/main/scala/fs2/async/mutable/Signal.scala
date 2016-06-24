package fs2
package async
package mutable

import fs2.Async.Change
import fs2._
import fs2.Stream._
import fs2.async.immutable
import fs2.util.Monad

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
   * `f` is consulted to set this signal.
   *
   * `F` returns the result of applying `op` to current value.
   */
   def modify(f: A => A): F[Change[A]]

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

  def apply[F[_],A](initA: A)(implicit F: Async[F]): F[Signal[F,A]] =
    F.map(F.refOf[(A, Long, Vector[F.Ref[(A,Long)]])]((initA,0,Vector.empty))) {
    state => new Signal[F,A] {
      def refresh: F[Unit] = F.map(modify(identity))(_ => ())
      def set(a: A): F[Unit] = F.map(modify(_ => a))(_ => ())
      def get: F[A] = F.map(F.get(state))(_._1)
      def modify(f: A => A): F[Change[A]] = {
        F.bind(F.modify(state) { case (a,l,_) =>
          (f(a),l+1,Vector.empty)
        }) { c =>
          if (c.previous._3.isEmpty) F.pure(c.map(_._1))
          else {
            val now = c.now._1 -> c.now._2
            F.bind(F.traverse(c.previous._3)(ref => F.setPure(ref)(now))) { _ =>
              F.pure(c.map(_._1))
            }
          }
        }
      }

      def changes: Stream[F, Unit] =
        discrete.map(_ => ())

      def continuous: Stream[F, A] =
        Stream.repeatEval(get)

      def discrete: Stream[F, A] = {
        def go(lastA:A, last:Long):Stream[F,A] = {
          def getNext:F[(F[(A,Long)],F[Unit])] = {
            F.bind(F.ref[(A,Long)]) { ref =>
              F.map(F.modify(state) { case s@(a, l, listen) =>
                if (l != last) s
                else (a, l, listen :+ ref)
              }) { c =>
                if (c.modified) {
                  val cleanup = F.map(F.modify(state) {
                    case s@(a, l, listen) => if (l != last) s else (a, l, listen.filterNot(_ == ref))
                  })(_ => ())
                  (F.get(ref),cleanup)
                }
                else (F.pure(c.now._1 -> c.now._2), F.pure(()))
              }
            }
          }
          emit(lastA) ++ Stream.bracket(getNext)(n => eval(n._1).flatMap { case (lastA, last) => go(lastA, last) }, n => n._2)
        }

        Stream.eval(F.get(state)) flatMap { case (a,l,_) => go(a,l) }
      }}
    }
}
