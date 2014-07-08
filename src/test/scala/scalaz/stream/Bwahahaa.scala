package scalaz.stream

/**
 * Unfortunately this can't reside on object with spec due to
 * restriction on concurrent initialization of objects
 */
object Bwahahaa extends java.lang.Exception("you are Any => Unit Reactive!")  {
  override def fillInStackTrace(): Throwable = this
}

object Bwahahaa2 extends java.lang.Exception("you are A => Unit Reactive!")  {
  override def fillInStackTrace(): Throwable = this
}

