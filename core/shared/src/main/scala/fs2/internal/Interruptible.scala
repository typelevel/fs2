package fs2.internal

import cats.effect.ConcurrentThrow
import cats.effect.concurrent.Deferred

final class Interruptible[F[_]](implicit val concurrentThrow: ConcurrentThrow[F],
    val mkDeferred: Deferred.Mk[F]
)

object Interruptible {
  implicit def instance[F[_]: ConcurrentThrow: Deferred.Mk]: Interruptible[F] = new Interruptible[F]
}
