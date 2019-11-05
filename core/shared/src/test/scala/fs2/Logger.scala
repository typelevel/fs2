package fs2

import cats.Functor
import cats.effect.{Resource, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._

trait Logger[F[_]] {
  def log(e: LogEvent): F[Unit]

  def logInfo(msg: String): Stream[F, Nothing] = Stream.eval_(log(LogEvent.Info(msg)))

  def logLifecycle(tag: String)(implicit F: Functor[F]): Stream[F, Unit] =
    Stream.resource(logLifecycleR(tag))

  def logLifecycleR(tag: String)(implicit F: Functor[F]): Resource[F, Unit] =
    Resource.make(log(LogEvent.Acquired(tag)))(_ => log(LogEvent.Released(tag)))

  def get: F[List[LogEvent]]
}

object Logger {
  def apply[F[_]: Sync]: F[Logger[F]] =
    Ref.of(Nil: List[LogEvent]).map { ref =>
      new Logger[F] {
        def log(e: LogEvent): F[Unit] = ref.update(acc => e :: acc)
        def get: F[List[LogEvent]] = ref.get.map(_.reverse)
      }
    }
}

sealed trait LogEvent
object LogEvent {
  final case class Acquired(tag: String) extends LogEvent
  final case class Released(tag: String) extends LogEvent
  final case class Info(message: String) extends LogEvent
}
