package fs2

/**
  * Indicates that a stream evaluates no effects.
  *
  * A `Stream[Pure,O]` can be safely converted to a `Stream[F,O]` for all `F`.
  */
sealed trait Pure[+A]
