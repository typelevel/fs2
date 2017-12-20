package fs2

final case object Interrupted extends Throwable { override def fillInStackTrace = this }
