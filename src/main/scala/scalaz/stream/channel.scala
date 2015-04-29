package scalaz.stream

import scalaz.Functor

object channel {

  /** Promote an effectful function to a `Channel`. */
  def lift[F[_],A,B](f: A => F[B]): Channel[F, A, B] =
    Process constant f

}

final class ChannelSyntax[F[_], I, O](val self: Channel[F, I, O]) extends AnyVal {

  /** Transform the input of this `Channel`. */
  def contramap[I0](f: I0 => I): Channel[F, I0, O] =
    self.map(f andThen _)

  /** Transform the output of this `Channel` */
  def mapOut[O2](f: O => O2)(implicit F: Functor[F]): Channel[F, I, O2] =
    self.map(_ andThen F.lift(f))

}
