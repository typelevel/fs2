/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package concurrent

import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._

/** A `Ref` whose value can be waited on.
  *
  * Waiters register a predicate on the value. Every `modify` evaluates the registered predicates
  * against the new value and wakes exactly the waiters whose predicate is satisfied. Unlike [[SignallingRef]],
  * waiters are not woken by unrelated updates.
  */
private[fs2] sealed trait ConditionedRef[F[_], A] {

  def get: F[A]

  /** Atomically updates the value and wakes every waiter whose predicate condition holds for the new value. */
  def modify[B](f: A => (A, B)): F[B]

  def update(f: A => A): F[Unit]

  def updateAndGet(f: A => A): F[A] =
    modify { a =>
      val newA = f(a)
      (newA, newA)
    }

  def set(a: A): F[Unit] = update(_ => a)

  /** Completes if the predicate, `p` holds for the current value, or a later `modify` sets a value that satisfies `p`.
    *
    * `p` may no longer hold by the time this completes: act on the value through `modify`.
    */
  def waitUntil(p: A => Boolean): F[Unit]
}

private[fs2] object ConditionedRef {

  def of[F[_], A](initial: A)(implicit F: Concurrent[F]): F[ConditionedRef[F, A]] =
    F.ref(State[F, A](value = initial, waiters = Nil)).map(new Impl(_))

  private final class Waiter[F[_], A](val accepts: A => Boolean, val wake: Deferred[F, Unit]) {
    def wakeUp: F[Boolean] = wake.complete(())
  }

  private final case class State[F[_], A](value: A, waiters: List[Waiter[F, A]]) {
    def register(waiter: Waiter[F, A]): State[F, A] = copy(waiters = waiter :: waiters)
    def deregister(waiter: Waiter[F, A]): State[F, A] =
      copy(waiters = waiters.filterNot(_ eq waiter))
  }

  private final class Impl[F[_], A](state: Ref[F, State[F, A]])(implicit F: Concurrent[F])
      extends ConditionedRef[F, A] {

    def get: F[A] = state.get.map(_.value)

    def modify[B](f: A => (A, B)): F[B] =
      state.flatModify { s => // uncancellable to avoid losing wake-up signals
        val (value, result) = f(s.value)
        // Scan first instead of calling partition to avoid extra allocations which have a small impact on perf.
        // This optimizes for the case where writes infrequently trigger wake-ups.
        if (!s.waiters.exists(_.accepts(value)))
          State(value = value, waiters = s.waiters) -> result.pure[F]
        else {
          val (toWake, waiting) = s.waiters.partition(_.accepts(value))
          State(value = value, waiters = waiting) -> toWake.traverse_(_.wakeUp).as(result)
        }
      }

    def update(f: A => A): F[Unit] = modify(a => (f(a), ()))

    def waitUntil(p: A => Boolean): F[Unit] =
      get.flatMap { value =>
        if (p(value)) F.unit
        else
          F.deferred[Unit].flatMap { wake =>
            val waiter = new Waiter(accepts = p, wake = wake)
            state.flatModifyFull { (poll, s) =>
              if (p(s.value)) s -> F.unit
              else
                s.register(waiter) -> poll(wake.get).onCancel {
                  state.update(_.deregister(waiter))
                }
            }
          }
      }
  }
}
