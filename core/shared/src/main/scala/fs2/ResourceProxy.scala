package fs2

import cats.implicits._
import cats.effect.{Concurrent, Resource, Sync}
import cats.effect.concurrent.Ref
import cats.effect.implicits._

trait ResourceProxy[F[_], R] {

  /**
    * Allocates a new resource, closes the last one if present, and
    * returns the newly allocated `R`.
    *
    * If there are no further calls to `swap`, the resource created by
    * the last call will be finalized when the lifetime of
    * `ResourceProxy` (which is itself tracked by `Resource`) is over.
    *
    * Since `swap` closes the old resource immediately, you need to
    * ensure that no code is using the old `R` when `swap` is called.
    * Failing to do so is likely to result in an error on the
    * _consumer_ side. In any case, no resources will be leaked by
    * `swap`
    *
    * If you try to call swap after the lifetime of `ResourceProxy` is
    * over, `swap` will fail, but it will ensure all resources are
    * closed, and never leak any.
    */
  def swap(next: Resource[F, R]): F[R]
}

object ResourceProxy {

  /**
    * Creates a new `ResourceProxy`, which represents a `Resource`
    * that can be swapped during the lifetime of this `ResourceProxy`.
    * See `io.file.writeRotate` for an example of usage.
    */
  def create[F[_]: Concurrent, R]: Resource[F, ResourceProxy[F, R]] = {
    def raise[A](msg: String): F[A] =
      Sync[F].raiseError(new RuntimeException(msg))

    def initialize = Ref[F].of(().pure[F].some)
    def finalize(state: Ref[F, Option[F[Unit]]]): F[Unit] =
      state
        .getAndSet(None)
        .flatMap {
          case None            => raise[Unit]("Finalizer already run")
          case Some(finalizer) => finalizer
        }

    Resource.make(initialize)(finalize(_)).map { state =>
      new ResourceProxy[F, R] {
        // Runs the finalizer for the current proxy target and delays
        // finalizer of newValue until this proxy is finalized
        def swap(next: Resource[F, R]): F[R] =
          (next <* ().pure[Resource[F, ?]]) // workaround for https://github.com/typelevel/cats-effect/issues/579
          .allocated
            .continual { r => // this whole block is inside continual and cannot be canceled
              Sync[F].fromEither(r).flatMap {
                case (newValue, newFinalizer) =>
                  state.modify {
                    case Some(oldFinalizer) =>
                      newFinalizer.some -> oldFinalizer.as(newValue)
                    case None =>
                      None -> (newFinalizer *> raise[R](
                        "Cannot swap after proxy has been finalized"
                      ))
                  }.flatten
              }
            }
      }
    }
  }
}
