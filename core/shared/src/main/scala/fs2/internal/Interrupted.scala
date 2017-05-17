package fs2.internal

final case object Interrupted extends Throwable { override def fillInStackTrace = this }
