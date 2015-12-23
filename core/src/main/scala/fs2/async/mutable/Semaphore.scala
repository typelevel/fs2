package fs2.async.mutable

import fs2.async.AsyncExt

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

  /** Decrement the number of permits by 1. Just calls `[[decrementBy]](1)`. */
  final def decrement: F[Unit] = decrementBy(1)
  /** Increment the number of permits by 1. Just calls `[[incrementBy]](1)`. */
  final def increment: F[Unit] = incrementBy(1)
}

object Semaphore {

  /** Create a new `Semaphore`, initialized with `n` available permits. */
  def apply[F[_]](n: Long)(implicit F: AsyncExt[F]): F[Semaphore[F]] = {
    def ensureNonneg(n: Long) = if (n < 0) throw new IllegalArgumentException("n must be nonnegative, was: " + n)
    ensureNonneg(n)
    // semaphore is either empty, and there are number of outstanding acquires (Left)
    // or it is nonempty, and there are n permits available (Right)
    type S = Either[Vector[(Long,F.Ref[Unit])], Long]
    F.map(F.refOf[S](Right(n))) { ref => new Semaphore[F] {
      private def open(gate: F.Ref[Unit]) = F.setPure(gate)(())

      def decrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else F.bind(F.modify(ref) {
          case Left(waiting) => F.map(F.ref[Unit]) { gate => Left(waiting :+ (n -> gate)) }
          case Right(m) =>
            if (n <= m) F.pure(Right(m-n))
            else F.map(F.ref[Unit]) { gate => Left(Vector((n-m) -> gate)) }
        }) { change => change.now match {
          case Left(waiting) => waiting.lastOption.map(p => F.get(p._2)).getOrElse(F.pure(()))
          case Right(_) => F.pure(())
        }}
      }

      def incrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else F.map(F.modify(ref) {
          case Left(waiting) =>
            // just figure out how many to strip from waiting queue,
            // but don't run anything here inside the modify
            var m = n
            var waiting2 = waiting
            while (waiting2.nonEmpty && m >= waiting2.head._1) {
              m -= waiting2.head._1
              waiting2 = waiting2.tail
            }
            if (waiting2.nonEmpty) F.pure(Left(waiting2))
            else F.pure(Right(m))
          case Right(m) => F.pure(Right(m+n))
        }) { change => change.previous match {
          case Left(waiting) =>
            // now compare old and new sizes to figure out which actions to run
            val newSize = change.now.fold(_.size, _ => 0)
            // just using Chunk for its stack-safe foldRight
            fs2.Chunk.indexedSeq(waiting.take(waiting.size - newSize))
                     .foldRight(F.pure(()))((hd,tl) => F.bind(open(hd._2))(_ => tl))
          case Right(_) => F.pure(())
        }}
      }

      def tryDecrementBy(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(true)
        else F.map(F.modify(ref) {
          case Right(m) if m >= n => F.pure(Right(m-n))
          case w => F.pure(w)
        })(_.now.isRight)
      }

      def available = F.map(F.get(ref)) {
        case Left(_) => 0
        case Right(n) => n
      }
  }}}

  /** Create a `Semaphore` with 0 initial permits. */
  def empty[F[_]:AsyncExt]: F[Semaphore[F]] = apply(0)
}
