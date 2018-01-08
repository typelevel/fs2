package fs2.internal

final case object AcquireAfterScopeClosed extends Throwable {
  override def fillInStackTrace = this
}
