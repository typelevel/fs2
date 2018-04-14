package fs2.internal

private[fs2] final case object Canceled extends Throwable {
  override def fillInStackTrace = this
}
