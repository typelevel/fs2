package fs2

import cats.implicits.{catsSyntaxEither => _, _}
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}

/** Provides utilities for concurrent computations. */
package object concurrent {

  /**
    * Lazily memoize `f`. For every time the returned `F[F[A]]` is
    * bound, the effect `f` will be performed at most once (when the
    * inner `F[A]` is bound the first time).
    */
  def once[F[_], A](f: F[A])(implicit F: Concurrent[F]): F[F[A]] =
    Ref.of[F, Option[Deferred[F, Either[Throwable, A]]]](None).map { ref =>
      Deferred[F, Either[Throwable, A]].flatMap { d =>
        ref
          .modify {
            case None =>
              Some(d) -> f.attempt.flatTap(d.complete)
            case s @ Some(other) => s -> other.get
          }
          .flatten
          .rethrow
      }
    }
}
