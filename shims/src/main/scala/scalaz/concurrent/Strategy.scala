package scalaz.concurrent

trait Strategy {
  def apply[A](a: A): () => A
}

object Strategy {
  implicit val DefaultStrategy: Strategy = ???
}
