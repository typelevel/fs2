package fs2

import cats.implicits._
import cats.effect.{Resource, Sync}
import cats.effect.concurrent.Ref
import cats.effect.Bracket

trait ResourceProxy[F[_], R] {
  def get: F[R]
  def swap(newValue: Resource[F, R]): F[R]
}

object ResourceProxy {
  def apply[F[_]: Sync, R](initialValue: Resource[F, R]): Resource[F, ResourceProxy[F, R]] = {
    val acquire = for {
      v <- initialValue.allocated
      state <- Ref.of[F, Option[(R, F[Unit])]](Some(v))
    } yield new ResourceProxyImpl(state)
    Resource.make(acquire)(_.runFinalizer).map { impl =>
      new ResourceProxy[F, R] {
        def get: F[R] = impl.get
        def swap(newValue: Resource[F, R]): F[R] = impl.swap(newValue)
      }
    }
  }

  final class ResourceProxyImpl[F[_], R](state: Ref[F, Option[(R, F[Unit])]])(
      implicit F: Bracket[F, Throwable]
  ) {
    def get: F[R] = state.get.flatMap {
      case None         => F.raiseError(new RuntimeException("Cannot get after proxy has been finalized"))
      case Some((r, _)) => F.pure(r)
    }

    // Runs the finalizer for the current proxy target and delays finalizer of newValue until this proxy is finalized
    def swap(newValue: Resource[F, R]): F[R] =
      F.uncancelable(newValue.allocated.flatMap {
        case nv =>
          def doSwap: F[R] =
            state.access.flatMap {
              case (current, setter) =>
                current match {
                  case None =>
                    nv._2 *> F.raiseError(
                      new RuntimeException("Cannot swap after proxy has been finalized")
                    )
                  case Some((_, oldFinalizer)) =>
                    setter(Some(nv)).ifM(oldFinalizer.as(nv._1), doSwap)
                }
            }
          doSwap
      })

    def runFinalizer: F[Unit] =
      state.access.flatMap {
        case (current, setter) =>
          current match {
            case None                 => F.raiseError(new RuntimeException("Finalizer already run"))
            case Some((_, finalizer)) => setter(None).ifM(finalizer, runFinalizer)
          }
      }
  }

}
