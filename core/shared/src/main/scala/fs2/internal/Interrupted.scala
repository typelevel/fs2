package fs2.internal

private[fs2] final case object Interrupted extends Throwable { override def fillInStackTrace = this }
