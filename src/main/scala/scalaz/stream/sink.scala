package scalaz.stream

object sink {

  /** Promote an effectful function to a `Sink`. */
  def lift[F[_], A](f: A => F[Unit]): Sink[F, A] = channel lift f

}