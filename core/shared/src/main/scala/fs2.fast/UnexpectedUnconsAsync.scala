package fs2
package fast

final case object UnexpectedUnconsAsync extends RuntimeException("Unexpected unconsAsync encountered when running stream synchronously") {
  override def fillInStackTrace = this
}
