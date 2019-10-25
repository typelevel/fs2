package fs2

import cats.implicits._
import cats.effect.{Resource, Sync}
import cats.effect.concurrent.Ref
import cats.effect.implicits._

trait ResourceProxy[F[_], R] {
  def swap(next: Resource[F, R]): F[R]
}

/*
Notes:
1)it's not possible to safely guard swap
one can swap (i.e. close) a resource used by something else (e.g. in
another fiber) from under the consumer's fit. Problem is that the
whole point of this PR is avoiding `Pull.bracket`, which means `swap`
cannot return a resource.

2) Is it worth guarding against the case where someone tries to swap
in another fiber after the resourceProxy's lifetime is over: probably
yes, because the cost is not an error like in 1), but a resource leak
 */

object ResourceProxy {
  def create[F[_]: Sync, R]: Resource[F, ResourceProxy[F, R]] = {
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
          next.allocated.flatMap {
            case (newValue, newFinalizer) =>
              state.modify {
                case Some(oldFinalizer) =>
                  newFinalizer.some -> oldFinalizer.as(newValue)
                case None =>
                  None -> (newFinalizer *> raise[R]("Cannot swap after proxy has been finalized"))
              }.flatten
          }.uncancelable

      }
    }
  }
}
