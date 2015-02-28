package scalaz.concurrent

case class Actor[A](handler: A => Unit)(implicit s: Strategy) {
  def !(a: A): Unit = ???
}
