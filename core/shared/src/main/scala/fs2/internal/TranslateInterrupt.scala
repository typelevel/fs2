package fs2.internal

import cats.effect.Concurrent

trait TranslateInterrupt[F[_]] {
  def effectInstance: Option[Concurrent[F]]
}

trait TranslateInterruptLowPriorityImplicits {

  implicit def unInterruptibleInstance[F[_]]: TranslateInterrupt[F] = new TranslateInterrupt[F] {
    def effectInstance: Option[Concurrent[F]] = None
  }

}

object TranslateInterrupt extends TranslateInterruptLowPriorityImplicits {
  implicit def interruptibleInstance[F[_]: Concurrent]: TranslateInterrupt[F] =
    new TranslateInterrupt[F] {
      def effectInstance: Option[Concurrent[F]] = Some(implicitly[Concurrent[F]])
    }
}
