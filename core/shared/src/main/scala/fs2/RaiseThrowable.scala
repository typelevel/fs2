package fs2

import cats.ApplicativeError
import scala.annotation.implicitNotFound

/**
  * Witnesses that `F` supports raising throwables.
  *
  * An instance of `RaiseThrowable` is available for any `F` which has an
  * `ApplicativeError[F, Throwable]` instance. Alternatively, an instance
  * is available for the uninhabited type `Fallible`.
  */
@implicitNotFound(
  "Cannot find an implicit value for RaiseThrowable[${F}]: an instance is available for any F which has an ApplicativeError[F, Throwable] instance or for F = Fallible. If getting this error for a non-specific F, try manually supplying the type parameter (e.g., Stream.raiseError[IO](t) instead of Stream.raiseError(t)). If getting this error when working with pure streams, use F = Fallible."
)
trait RaiseThrowable[F[_]]

object RaiseThrowable {
  implicit def fromApplicativeError[F[_]](
      implicit F: ApplicativeError[F, Throwable]
  ): RaiseThrowable[F] = {
    val _ = F
    new RaiseThrowable[F] {}
  }
}
