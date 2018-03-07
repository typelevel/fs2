package fs2.internal

final case object Canceled extends Throwable {
  override def fillInStackTrace = this
}
