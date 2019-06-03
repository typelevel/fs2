package fs2

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

final class Counter[F[_]](private val ref: Ref[F, Long]) {
  def increment: F[Unit] = ref.update(_ + 1)
  def decrement: F[Unit] = ref.update(_ - 1)
  def get: F[Long] = ref.get
}

object Counter {
  def apply[F[_]: Sync]: F[Counter[F]] = Ref.of[F, Long](0L).map(new Counter(_))
}