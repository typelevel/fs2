package fs2

import cats.implicits._
import cats.effect.{Resource, Sync}
import cats.effect.concurrent.Ref
import cats.effect.implicits._

trait ResourceProxy[F[_], R] {
  def get: F[R]
  def swap(next: Resource[F, R]): F[R]
}

object ResourceProxy {
  def apply[F[_]: Sync, R](initial: Resource[F, R]): Resource[F, ResourceProxy[F, R]] = {

    val acquire = for {
      v <- initial.allocated
      state <- Ref.of[F, Option[(R, F[Unit])]](v.some)
    } yield state

    def runFinalizer(state: Ref[F, Option[(R, F[Unit])]]): F[Unit] =
      state.modify {
        case None                 => None -> raise[Unit]("Finalizer already run")
        case Some((_, finalizer)) => None -> finalizer
      }.flatten

    def raise[A](msg: String): F[A] = Sync[F].raiseError(new RuntimeException(msg))

    Resource.make(acquire)(runFinalizer(_)).map { state =>
      new ResourceProxy[F, R] {
        def get: F[R] = state.get.flatMap {
          case None         => raise("Cannot get after proxy has been finalized")
          case Some((r, _)) => r.pure[F]
        }

        // Runs the finalizer for the current proxy target and delays finalizer of newValue until this proxy is finalized
        def swap(next: Resource[F, R]): F[R] =
          next.allocated.flatMap {
            case next @ (newValue, newFinalizer) =>
              state.modify {
                case Some((_, oldFinalizer)) =>
                  next.some -> oldFinalizer.as(newValue)
                case None =>
                  None -> (newFinalizer *> raise[R]("Cannot swap after proxy has been finalized"))
              }.flatten
          }.uncancelable

      }
    }
  }
}
