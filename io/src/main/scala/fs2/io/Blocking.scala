package fs2.io

import cats.effect.{ContextShift, Sync}
import scala.concurrent.ExecutionContext

private[io] trait Blocking[F[_]] {

  /**
    * Like `Sync#delay` but evaluates the thunk on the supplied execution context
    * and then shifts back to the default thread pool.
    */
  def blocking[A](blockingExecutionContext: ExecutionContext)(thunk: => A): F[A]
}

private[io] object Blocking {

  implicit def fromSyncAndContextShift[F[_]: Sync: ContextShift]: Blocking[F] = new Blocking[F] {
    def blocking[A](blockingExecutionContext: ExecutionContext)(thunk: => A): F[A] =
      ContextShift[F].evalOn(blockingExecutionContext)(Sync[F].delay(thunk))
  }
}
