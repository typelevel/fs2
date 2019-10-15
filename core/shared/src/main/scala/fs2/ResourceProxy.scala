package fs2

import cats.implicits._
import cats.effect.{Resource, Sync}
import cats.effect.concurrent.Ref

final class ResourceProxy[F[_], R](state: Ref[F, Option[(R, F[Unit])]])(implicit F: Sync[F]) {
  def get: F[R] = state.get.flatMap {
    case None         => F.raiseError(new RuntimeException("Resource already finalized"))
    case Some((r, _)) => F.pure(r)
  }

  // Runs the finalizer for the current proxy target and delays finalizer of newValue until this proxy is finalized
  def swap(newValue: Resource[F, R]): F[Unit] =
    newValue.allocated.flatMap {
      case nv =>
        def doSwap: F[Unit] =
          state.access.flatMap {
            case (current, setter) =>
              current match {
                case None =>
                  nv._2 *> F.raiseError(
                    new RuntimeException("Resource proxy already finalized at time of swap")
                  )
                case Some((_, oldFinalizer)) =>
                  setter(Some(nv)).ifM(oldFinalizer, doSwap)
                  oldFinalizer
              }
          }
        doSwap
    }

  private def runFinalizer: F[Unit] =
    state.access.flatMap {
      case (current, setter) =>
        current match {
          case None                 => F.raiseError(new RuntimeException("Finalizer already run"))
          case Some((_, finalizer)) => setter(None).ifM(finalizer, runFinalizer)
        }
    }
}

object ResourceProxy {
  def apply[F[_]: Sync, R](initialValue: Resource[F, R]): Resource[F, ResourceProxy[F, R]] = {
    val acquire = initialValue.allocated.flatMap { v =>
      Ref.of[F, Option[(R, F[Unit])]](Some(v)).map { state =>
        new ResourceProxy(state)
      }
    }
    Resource.make(acquire)(_.runFinalizer)
  }
}
