package fs2

/**
  * Indicates that a stream evaluates no effects but unlike [[Pure]], may raise errors.
  *
  * Uninhabited.
  *
  * A `Stream[Fallible,O]` can be safely converted to a `Stream[F,O]` for all `F` via `s.lift[F]`,
  * provided an `ApplicativeError[F, Throwable]` is available.
  */
sealed trait Fallible[A]

object Fallible {
  implicit val raiseThrowableInstance: RaiseThrowable[Fallible] = new RaiseThrowable[Fallible] {}
}
