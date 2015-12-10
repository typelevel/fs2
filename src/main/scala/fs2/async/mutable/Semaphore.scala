package fs2.async.mutable

import fs2.async.AsyncExt

/**
 * An asynchronous semaphore, useful as a concurrency primitive.
 */
trait Semaphore[F[_]] {

  /** Returns the number of permits currently available for acquisition. Always nonnegative. */
  def available: F[Long]

  /**
   * Decrement the number of available permits by `n`, blocking until `n`
   * are available. Error if `n < 0`. The blocking is semantic; we do not
   * literally block a thread waiting for permits to become available.
   */
  def acquire(n: Long): F[Unit]

  /** Acquire `n` permits now and return `true`, or return `false` immediately. Error if `n < 0`. */
  def tryAcquire(n: Long): F[Boolean]

  /**
   * Increment the number of available permits by `n`. Error if `n < 0`.
   * This will have the effect of unblocking `n` acquisitions.
   */
  def release(n: Long): F[Unit]

  /** Acquire a single permit. Just calls `[[acquire]](1)`. */
  final def acquire1: F[Unit] = acquire(1)
  /** Release a single permit. Just calls `[[release]](1)`. */
  final def release1: F[Unit] = release(1)
}

object Semaphore {

  /** Create a new `Semaphore`, initialized with `n` available permits. */
  def apply[F[_]](n: Long)(implicit F: AsyncExt[F]): F[Semaphore[F]] = {
    def ensureNonneg(n: Long) = if (n < 0) throw new IllegalArgumentException("n must be nonnegative, was: " + n)
    ensureNonneg(n)
    // semaphore is either empty, and there are number of outstanding acquires (Left)
    // or it is nonempty, and there are n permits available (Right)
    type S = Either[Vector[(Long,F[Unit])], Long]
    F.map(F.refOf[S](Right(n))) { ref => new Semaphore[F] {
      private def open(gate: F.Ref[Unit]) = F.setPure(gate)(())

      def acquire(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else F.bind(F.modify(ref) {
          case Left(waiting) => F.map(F.ref[Unit]) { gate => Left(waiting :+ (n -> open(gate))) }
          case Right(m) =>
            if (n <= m) F.pure(Right(m-n))
            else F.map(F.ref[Unit]) { gate => Left(Vector((n-m) -> open(gate))) }
        }) { change => change.now match {
          case Left(waiting) => waiting.lastOption.map(_._2).getOrElse(F.pure(()))
          case Right(_) => F.pure(())
        }}
      }

      def release(n: Long) = { ensureNonneg(n)
        if (n == 0) F.pure(())
        else F.map(F.modify(ref) {
          case Left(waiting) =>
            def go(waiting: Vector[(Long,F[Unit])], n: Long): F[S] = waiting match {
              case Vector() => F.pure(Right(n))
              case v if v.head._1 <= n => F.bind(v.head._2) { _ => go(v.tail, n-v.head._1) }
              case v => if (n <= 0) F.pure(Left(waiting))
                        else F.pure(Left((v.head._1 - n -> v.head._2) +: v.tail))
            }
            go(waiting, n)
          case Right(m) => F.pure(Right(m+n))
        }) { change => () }
      }

      def tryAcquire(n: Long) = { ensureNonneg(n)
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
