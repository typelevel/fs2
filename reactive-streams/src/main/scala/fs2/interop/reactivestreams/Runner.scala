package fs2
package interop
package reactivestreams

import cats.effect._
import cats.effect.implicits._

private[interop] class Runner[F[_]: ConcurrentEffect] {
  private def reportFailure(e: Throwable) =
    Thread.getDefaultUncaughtExceptionHandler match {
      case null => e.printStackTrace()
      case h    => h.uncaughtException(Thread.currentThread(), e)
    }

  def unsafeRunAsync[A](fa: F[A]): Unit =
    fa.runAsync {
      case Left(e)  => IO(reportFailure(e))
      case Right(_) => IO.unit
    }.unsafeRunSync

  def unsafeRunSync[A](fa: F[A]): Unit = {
    val io = ConcurrentEffect[F].toIO(fa)
    io.attempt.unsafeRunSync match {
      case Left(failure) => reportFailure(failure)
      case _             =>
    }
  }
}
