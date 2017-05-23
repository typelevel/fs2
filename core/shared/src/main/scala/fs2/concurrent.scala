package fs2

import cats.effect.Effect

import scala.concurrent.ExecutionContext

object concurrent {

  /** Deprecated alias for [[Stream.join]]. */
  @deprecated("Use Stream#join instead", "0.10")
  def join[F[_],O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,O] =
    Stream.join(maxOpen)(outer)
}
