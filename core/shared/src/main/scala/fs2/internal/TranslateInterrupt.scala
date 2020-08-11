package fs2.internal

private[fs2] trait TranslateInterrupt[F[_]] {
  def interruptible: Option[Interruptible[F]]
}

private[fs2] trait TranslateInterruptLowPriorityImplicits {
  implicit def unInterruptibleInstance[F[_]]: TranslateInterrupt[F] =
    new TranslateInterrupt[F] {
      def interruptible: Option[Interruptible[F]] = None
    }
}

private[fs2] object TranslateInterrupt extends TranslateInterruptLowPriorityImplicits {
  implicit def interruptibleInstance[F[_]: Interruptible]: TranslateInterrupt[F] =
    new TranslateInterrupt[F] {
      def interruptible: Option[Interruptible[F]] = Some(implicitly[Interruptible[F]])
    }
}
