package fs2.internal

private[internal] final case object Interrupted extends Throwable { override def fillInStackTrace = this }
