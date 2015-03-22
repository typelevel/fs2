package scalaz.stream

object channel {

  /** Promote an effectful function to a `Channel`. */
  def lift[F[_],A,B](f: A => F[B]): Channel[F, A, B] =
    Process constant f

}
