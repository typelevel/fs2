package fs2
package async
package mutable

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.Effect
import cats.implicits._

/**
 * An asynchronous semaphore, useful as a concurrency primitive.
 */
abstract class Semaphore[F[_]] {

  /** Returns the number of permits currently available. Always nonnegative. */
  def available: F[Long]

  /**
   * Decrements the number of available permits by `n`, blocking until `n`
   * are available. Error if `n < 0`. The blocking is semantic; we do not
   * literally block a thread waiting for permits to become available.
   * Note that decrements are satisfied in strict FIFO order, so given
   * `s: Semaphore[F]` with 2 permits available, a `decrementBy(3)` will
   * always be satisfied before a later call to `decrementBy(1)`.
   */
  def decrementBy(n: Long): F[Unit]

  /**
   * Like [[decrementBy]] but limits the amount of time spent blocking for
   * permits. If all permits have not been acquired after the timeout has been
   * reached, the action completes with the number of permits remaining to be acquired.
   * If the requested number of permits is acquired, the action completes with 0.
   */
  def timedDecrementBy(n: Long, timeout: FiniteDuration, scheduler: Scheduler): F[Long]

  /** Acquires `n` permits now and returns `true`, or returns `false` immediately. Error if `n < 0`. */
  def tryDecrementBy(n: Long): F[Boolean]

  /** Alias for `[[tryDecrementBy]](1)`. */
  def tryDecrement: F[Boolean] = tryDecrementBy(1)

  /**
   * Increments the number of available permits by `n`. Error if `n < 0`.
   * This will have the effect of unblocking `n` acquisitions.
   */
  def incrementBy(n: Long): F[Unit]

  /**
   * Obtains a snapshot of the current count. May be out of date the instant
   * after it is retrieved. Use `[[tryDecrement]]` or `[[tryDecrementBy]]`
   * if you wish to attempt a decrement and return immediately if the
   * current count is not high enough to satisfy the request.
   */
  def count: F[Long]

  /**
   * Resets the count of this semaphore back to zero, and returns the previous count.
   * Throws an `IllegalArgumentException` if count is below zero (due to pending
   * decrements).
   */
  def clear: F[Long]

  /** Decrements the number of permits by 1. Alias for `[[decrementBy]](1)`. */
  final def decrement: F[Unit] = decrementBy(1)

  /**
   * Like [[decrement]] but limits the amount of time spent blocking for
   * a permit. If the permit has not been acquired after the timeout has been
   * reached, the action completes with false. If a permit is acquired, the
   * action completes with true.
   */
  def timedDecrement(timeout: FiniteDuration, scheduler: Scheduler): F[Boolean]

  /** Increments the number of permits by 1. Alias for `[[incrementBy]](1)`. */
  final def increment: F[Unit] = incrementBy(1)
}

object Semaphore {

  /** Creates a new `Semaphore`, initialized with `n` available permits. */
  def apply[F[_]](n: Long)(implicit F: Effect[F], ec: ExecutionContext): F[Semaphore[F]] = {
    def ensureNonneg(n: Long) = assert(n >= 0, s"n must be nonnegative, was: $n ")

    ensureNonneg(n)
    // semaphore is either empty, and there are number of outstanding acquires (Left)
    // or it is non-empty, and there are n permits available (Right)
    type S = Either[Vector[(Long,async.Ref[F,Unit])], Long]
    async.refOf[F,S](Right(n)).map { ref => new Semaphore[F] {
      private def open(gate: async.Ref[F,Unit]): F[Unit] =
        gate.setAsyncPure(())

      def count = ref.get.map(count_)

      def decrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else async.ref[F,Unit].flatMap(decrementByImpl(n, _))
      }

      private def decrementByImpl(n: Long, gate: Ref[F,Unit]): F[Unit] =
        ref.modify {
          case Left(waiting) => Left(waiting :+ (n -> gate))
          case Right(m) =>
            if (n <= m) Right(m-n)
            else Left(Vector((n-m) -> gate))
        }.flatMap { c =>
          c.now match {
            case Left(waiting) =>
              def err = sys.error("FS2 bug: Semaphore has empty waiting queue rather than 0 count")
              waiting.lastOption.getOrElse(err)._2.get
            case Right(_) => F.pure(())
          }
        }

      def timedDecrementBy(n: Long, timeout: FiniteDuration, scheduler: Scheduler): F[Long] = {
        ensureNonneg(n)
        if (n == 0) F.pure(0)
        else async.ref[F,Unit].flatMap { gate =>
          val timedOut: F[Long] = ref.modify {
            case Left(waiting) =>
              val w2 = waiting.filter(_._2 ne gate)
              if (w2.isEmpty) Right(0) else Left(w2)
            case Right(n) => Right(n)
          }.map { c =>
            c.previous match {
              case Left(w) =>
                w.find(_._2 eq gate) match {
                  case Some((m,g)) => m
                  case None => 0
                }
              case Right(_) => 0
            }
          }
          scheduler.effect.delayCancellable(timedOut, timeout).flatMap { case (timer, cancelTimer) =>
            async.race(decrementByImpl(n, gate), timer).flatMap(_.fold(_ => cancelTimer.as(0), o => F.pure(o.getOrElse(0))))
          }
        }
      }

      def timedDecrement(timeout: FiniteDuration, scheduler: Scheduler): F[Boolean] =
        timedDecrementBy(1, timeout, scheduler).map(_ == 0)

      def clear: F[Long] =
        ref.modify {
          case Left(e) => throw new IllegalStateException("cannot clear a semaphore with negative count")
          case Right(n) => Right(0)
        }.flatMap { c => c.previous match {
          case Right(n) => F.pure(n)
          case Left(_) => sys.error("impossible, exception thrown above")
        }}

      private def count_(s: S): Long =
        s.fold(ws => -ws.map(_._1).sum, identity)

      def incrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else ref.modify {
          case Left(waiting) =>
            // just figure out how many to strip from waiting queue,
            // but don't run anything here inside the modify
            var m = n
            var waiting2 = waiting
            while (waiting2.nonEmpty && m > 0) {
              val (k, gate) = waiting2.head
              if (k > m) { waiting2 = (k-m, gate) +: waiting2.tail; m = 0; }
              else { m -= k; waiting2 = waiting2.tail }
            }
            if (waiting2.nonEmpty) Left(waiting2)
            else Right(m)
          case Right(m) => Right(m+n)
        }.flatMap { change =>
          // invariant: count_(change.now) == count_(change.previous) + n
          change.previous match {
          case Left(waiting) =>
            // now compare old and new sizes to figure out which actions to run
            val newSize = change.now.fold(_.size, _ => 0)
            val released = waiting.size - newSize
            waiting.take(released).foldRight(F.pure(())) { (hd,tl) => open(hd._2) *> tl }
          case Right(_) => F.pure(())
        }}
      }

      def tryDecrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(true)
        else ref.modify {
          case Right(m) if m >= n => Right(m-n)
          case w => w
        }.map { c => c.now.fold(_ => false, n => c.previous.fold(_ => false, m => n != m)) }
      }

      def available = ref.get.map {
        case Left(_) => 0
        case Right(n) => n
      }
  }}}

  /** Creates a `Semaphore` with 0 initial permits. */
  def empty[F[_]:Effect](implicit ec: ExecutionContext): F[Semaphore[F]] = apply(0)
}
