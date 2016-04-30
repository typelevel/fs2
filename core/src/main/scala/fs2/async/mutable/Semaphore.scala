package fs2.async.mutable

import fs2.Async

/**
 * An asynchronous semaphore, useful as a concurrency primitive.
 */
trait Semaphore[F[_]] {

  /** Returns the number of permits currently available. Always nonnegative. */
  def available: F[Long]

  /**
   * Decrement the number of available permits by `n`, blocking until `n`
   * are available. Error if `n < 0`. The blocking is semantic; we do not
   * literally block a thread waiting for permits to become available.
   * Note that decrements are satisfied in strict FIFO order, so given
   * `s: Semaphore[F]` with 2 permits available, a `decrementBy(3)` will
   * always be satisfied before a later call to `decrementBy(1)`.
   */
  def decrementBy(n: Long): F[Unit]

  /** Acquire `n` permits now and return `true`, or return `false` immediately. Error if `n < 0`. */
  def tryDecrementBy(n: Long): F[Boolean]
  /** Just calls `[[tryDecrementBy]](1)`. */
  def tryDecrement: F[Boolean] = tryDecrementBy(1)

  /**
   * Increment the number of available permits by `n`. Error if `n < 0`.
   * This will have the effect of unblocking `n` acquisitions.
   */
  def incrementBy(n: Long): F[Unit]

  /**
   * Obtain a snapshot of the current count. May be out of date the instant
   * after it is retrieved. Use `[[tryDecrement]]` or `[[tryDecrementBy]]`
   * if you wish to attempt a decrement and return immediately if the
   * current count is not high enough to satisfy the request.
   */
  def count: F[Long]

  /**
   * Reset the count of this semaphore back to zero, and return the previous count.
   * Throws an `IllegalArgumentException` if count is below zero (due to pending
   * decrements).
   */
  def clear: F[Long]

  /** Decrement the number of permits by 1. Just calls `[[decrementBy]](1)`. */
  final def decrement: F[Unit] = decrementBy(1)
  /** Increment the number of permits by 1. Just calls `[[incrementBy]](1)`. */
  final def increment: F[Unit] = incrementBy(1)
}

object Semaphore {

  /** Create a new `Semaphore`, initialized with `n` available permits. */
  def apply[F[_]](n: Long)(implicit F: Async[F]): F[Semaphore[F]] = {
    def ensureNonneg(n: Long) = assert(n >= 0, s"n must be nonnegative, was: $n ")

    ensureNonneg(n)
    // semaphore is either empty, and there are number of outstanding acquires (Left)
    // or it is nonempty, and there are n permits available (Right)
    type S = Either[Vector[(Long,F.Ref[Unit])], Long]
    F.map(F.refOf[S](Right(n))) { ref => new Semaphore[F] {
      private def open(gate: F.Ref[Unit]): F[Unit] =
        F.setPure(gate)(())

      def count = F.map(F.get(ref))(count_)
      def decrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else F.bind(F.ref[Unit]) { gate => F.bind(F.modify(ref) {
          case Left(waiting) => Left(waiting :+ (n -> gate))
          case Right(m) =>
            if (n <= m) Right(m-n)
            else Left(Vector((n-m) -> gate))
        }) { c => c.now match {
          case Left(waiting) =>
            def err = sys.error("FS2 bug: Semaphore has empty waiting queue rather than 0 count")
            F.get(waiting.lastOption.getOrElse(err)._2)
          case Right(_) => F.pure(())
        }}}
      }

      def clear: F[Long] =
        F.bind(F.modify(ref) {
        case Left(e) => throw new IllegalStateException("cannot clear a semaphore with negative count")
        case Right(n) => Right(0)
        }) { c => c.previous match {
          case Right(n) => F.pure(n)
          case Left(_) => sys.error("impossible, exception thrown above")
        }}

      private def count_(s: S): Long =
        s.fold(ws => -ws.map(_._1).sum, identity)

      def incrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else F.bind(F.modify(ref) {
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
        }) { change =>
          // invariant: count_(change.now) == count_(change.previous) + n
          change.previous match {
          case Left(waiting) =>
            // now compare old and new sizes to figure out which actions to run
            val newSize = change.now.fold(_.size, _ => 0)
            val released = waiting.size - newSize
            // just using Chunk for its stack-safe foldRight
            fs2.Chunk.indexedSeq(waiting.take(released))
                     .foldRight(F.pure(())) { (hd,tl) => F.bind(open(hd._2)){ _ => tl }}
          case Right(_) => F.pure(())
        }}
      }

      def tryDecrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(true)
        else F.map(F.modify(ref) {
          case Right(m) if m >= n => Right(m-n)
          case w => w
        }) { c => c.now.fold(_ => false, n => c.previous.fold(_ => false, m => n != m)) }
      }

      def available = F.map(F.get(ref)) {
        case Left(_) => 0
        case Right(n) => n
      }
  }}}

  /** Create a `Semaphore` with 0 initial permits. */
  def empty[F[_]:Async]: F[Semaphore[F]] = apply(0)
}
