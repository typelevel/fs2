package scalaz.stream2

/**
 * Unfortunately this can't reside on object with spec due to
 * restriction on concurrent initialization of objects
 */
object Bwahahaa extends java.lang.Exception("you are Any => Unit Reactive!")  {
  override def fillInStackTrace(): Throwable = this
}
