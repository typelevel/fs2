package fs2.internal

import cats.effect.Effect

trait TranslateInterrupt[F[_]] {
  def effectInstance: Option[Effect[F]]
}

trait TranslateInterruptLowPriorityImplicits {

  implicit def unInterruptibleInstance[F[_]]: TranslateInterrupt[F] = new TranslateInterrupt[F] {
    def effectInstance: Option[Effect[F]] = None
  }

}

object TranslateInterrupt extends TranslateInterruptLowPriorityImplicits {
  implicit def interruptibleInstance[F[_]: Effect]: TranslateInterrupt[F] =
    new TranslateInterrupt[F] {
      def effectInstance: Option[Effect[F]] = Some(implicitly[Effect[F]])
    }
}
