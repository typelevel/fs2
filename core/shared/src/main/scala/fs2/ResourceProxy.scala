package fs2

import cats.implicits._
import cats.effect.{Bracket, Resource, Sync}
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
      state <- Ref.of[F, Option[(R, F[Unit])]](Some(v))
    } yield new ResourceProxyImpl(state)

    Resource.make(acquire)(_.runFinalizer).map { impl =>
      new ResourceProxy[F, R] {
        def get: F[R] = impl.get
        def swap(next: Resource[F, R]): F[R] = impl.swap(next)
      }
    }
  }

  final class ResourceProxyImpl[F[_], R](state: Ref[F, Option[(R, F[Unit])]])(
      implicit F: Bracket[F, Throwable]
  ) {

    def raise[A](msg: String): F[A] = F.raiseError(new RuntimeException(msg))

    def get: F[R] = state.get.flatMap {
      case None         => raise("Cannot get after proxy has been finalized")
      case Some((r, _)) => r.pure[F]
    }

    // Runs the finalizer for the current proxy target and delays finalizer of newValue until this proxy is finalized
    def swap(next: Resource[F, R]): F[R] =
      next.allocated.flatMap {
        case next @ (newValue, newFinalizer) =>
          def doSwap: F[R] =
            state.access.flatMap {
              case (current, setter) =>
                current match {
                  case None =>
                    newFinalizer *> raise("Cannot swap after proxy has been finalized")
                  case Some((_, oldFinalizer)) =>
                    setter(next.some).ifM(oldFinalizer.as(newValue), doSwap)
                }
            }
          doSwap
      }.uncancelable

    def runFinalizer: F[Unit] =
      state.access.flatMap {
        case (current, setter) =>
          current match {
            case None                 => raise("Finalizer already run")
            case Some((_, finalizer)) => setter(None).ifM(finalizer, runFinalizer)
          }
      }
  }
}
