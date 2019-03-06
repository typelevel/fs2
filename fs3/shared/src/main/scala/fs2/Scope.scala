package fs2

import cats._
import cats.data.Chain
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._

final class Scope[F[_]] private (
    private[fs2] val id: Token,
    private val parent: Option[Scope[F]],
    private[this] val state: Ref[F, Scope.State[F]],
    private[this] val interruptionContext: Option[Scope.InterruptionContext[F]]) {

  override def toString = s"Scope($id, $parent, $interruptionContext)"

  private[fs2] def acquire[R](fr: F[R], release: (R, ExitCase[Throwable]) => F[Unit])(
      implicit F: Sync[F]): F[Either[Throwable, R]] =
    fr.attempt.flatMap {
      case Right(r) =>
        val finalizer = (ec: ExitCase[Throwable]) => F.suspend(release(r, ec))
        val resource = ScopedResource(finalizer)
        state
          .modify { s =>
            if (s.open) (s.copy(resources = resource +: s.resources), true)
            else (s, false)
          }
          .flatMap { successful =>
            if (successful) F.pure(Right(r): Either[Throwable, R])
            else finalizer(ExitCase.Completed).attempt.map(_.as(r))
          }
      case Left(err) => F.pure(Left(err): Either[Throwable, R])
    }.uncancelable

  private[fs2] def open(interruptible: Option[Concurrent[F]])(implicit F: Sync[F]): F[Scope[F]] = {
    val newInterruptionContext: F[Option[Scope.InterruptionContext[F]]] =
      interruptionContext
        .map(_.open(interruptible).map(Option(_)))
        .getOrElse(Scope.InterruptionContext.unsafeFromInterruptible(interruptible).pure[F])
    newInterruptionContext.flatMap { ictx =>
      state
        .modify { s =>
          if (s.open) {
            val child = Scope.unsafe(Some(this), ictx)
            (s.copy(children = child +: s.children), Some(child))
          } else (s, None)
        }
        .flatMap {
          case Some(child) =>
            F.pure(child)
          case None =>
            parent match {
              case Some(p) => p.open(interruptible)
              case None =>
                F.raiseError(
                  new IllegalStateException(
                    "Root scope already closed so a new scope cannot be opened"))
            }
        }
    }
  }

  private[fs2] def closeAndThrow(ec: ExitCase[Throwable])(implicit F: Sync[F]): F[Unit] =
    close(ec)
      .flatMap(errs =>
        CompositeFailure.fromList(errs.toList).map(F.raiseError(_): F[Unit]).getOrElse(F.unit))
      .uncancelable

  private[fs2] def close(ec: ExitCase[Throwable])(
      implicit F: MonadError[F, Throwable]): F[Chain[Throwable]] =
    for {
      previous <- state.modify(s => (Scope.State.closed[F], s))
      resultsChildren <- previous.children.flatTraverse(_.close(ec))
      resultsResources <- previous.resources.traverse(_.release(ec))
      _ <- interruptionContext.map(_.cancelParent).getOrElse(F.unit)
      _ <- parent.fold(F.unit)(p => p.unregisterChild(id))
    } yield resultsChildren ++ resultsResources.collect { case Some(t) => t: Throwable }

  private def unregisterChild(id: Token): F[Unit] =
    state.update(
      state =>
        state.copy(
          children = state.children.deleteFirst(_.id == id).map(_._2).getOrElse(state.children)))

  /**
    * Finds the scope with the supplied identifier.
    *
    * The search strategy is:
    * - check if the target is this scope
    * - check if the target is a descendant of this scope
    * - repeat the search at the parent of this scope, excluding this scope when searching descendants
    */
  private[fs2] def findScope(scopeId: Token)(implicit F: Monad[F]): F[Option[Scope[F]]] =
    findLocalScope(scopeId, None).flatMap {
      case Some(scope) => F.pure(Some(scope))
      case None =>
        parent match {
          case Some(p) => p.findLocalScope(scopeId, Some(id))
          case None    => F.pure(None)
        }
    }

  /**
    * Finds the scope with the supplied identifier by checking the id of this scope
    * and by searching descendant scopes.
    */
  private def findLocalScope(scopeId: Token, excludedChild: Option[Token])(
      implicit F: Monad[F]): F[Option[Scope[F]]] =
    if (scopeId == id) F.pure(Some(this))
    else
      state.get
        .flatMap { s =>
          def loop(remaining: Chain[Scope[F]]): F[Option[Scope[F]]] =
            remaining.uncons match {
              case Some((hd, tl)) =>
                val exclude = excludedChild.map(_ == hd.id).getOrElse(false)
                if (exclude) loop(tl)
                else
                  hd.findLocalScope(scopeId, None).flatMap {
                    case None        => loop(tl)
                    case Some(scope) => F.pure(Some(scope))
                  }
              case None => F.pure(None)
            }
          loop(s.children)
        }

  def lease: F[Option[Scope.Lease[F]]] = ???

  def interrupt(cause: Option[Throwable])(implicit F: Sync[F]): F[Unit] =
    interruptionContext match {
      case Some(ictx) =>
        ictx.deferred.complete(cause).guarantee(ictx.interrupted.update(_.orElse(Some(cause))))
      // >> state.get.flatMap(_.children.traverse_(_.interrupt(cause))) TODO?
      case None =>
        F.raiseError(
          new IllegalStateException("cannot interrupt a scope that does not support interruption"))
    }

  /**
    * Checks if current scope is interrupted.
    * - `None` indicates the scope has not been interrupted.
    * - `Some(None)` indicates the scope has not been interrupted.
    * - `Some(Some(t))` indicates the scope was interrupted due to the exception `t`.
    */
  private[fs2] def isInterrupted(implicit F: Applicative[F]): F[Option[Option[Throwable]]] =
    interruptionContext match {
      case Some(ctx) => ctx.interrupted.get
      case None      => F.pure(None)
    }

  /**
    * Evaluates the supplied `fa` in a way that respects scope interruption.
    * If the supplied value completes, its result is returned wrapped in a `Right`.
    * If instead, the scope is interrupted while the task is running, a `Left` is returned
    * indicating whether interruption occurred due to an error or not.
    */
  private[fs2] def interruptibleEval[A](fa: F[A])(
      implicit F: MonadError[F, Throwable]): F[Either[Option[Throwable], Either[Throwable, A]]] =
    interruptionContext match {
      case None =>
        fa.attempt.map(Right(_))
      case Some(ictx) =>
        ictx.concurrent.race(ictx.deferred.get, fa.attempt)
    }
}

object Scope {

  private[fs2] def unsafe[F[_]: Sync](
      parent: Option[Scope[F]],
      interruptionContext: Option[InterruptionContext[F]]): Scope[F] = {
    val state = Ref.unsafe[F, State[F]](State.initial[F])
    new Scope(new Token, parent, state, interruptionContext)
  }

  private case class State[F[_]](
      open: Boolean,
      resources: Chain[ScopedResource[F]],
      children: Chain[Scope[F]]
  )

  private object State {
    private val initial_ =
      State[Pure](open = true, resources = Chain.empty, children = Chain.empty)
    def initial[F[_]]: State[F] = initial_.asInstanceOf[State[F]]

    private val closed_ =
      State[Pure](open = false, resources = Chain.empty, children = Chain.empty)
    def closed[F[_]]: State[F] = closed_.asInstanceOf[State[F]]
  }

  private case class InterruptionContext[F[_]](
      concurrent: Concurrent[F],
      interrupted: Ref[F, Option[Option[Throwable]]],
      deferred: Deferred[F, Option[Throwable]],
      cancelParent: F[Unit]
  ) {
    def open(interruptible: Option[Concurrent[F]])(implicit F: Sync[F]): F[InterruptionContext[F]] =
      interruptible
        .map { concurrent =>
          concurrent.start(deferred.get).flatMap { fiber =>
            val context = InterruptionContext[F](
              concurrent = concurrent,
              interrupted = Ref.unsafe[F, Option[Option[Throwable]]](None),
              deferred = Deferred.unsafe[F, Option[Throwable]](concurrent),
              cancelParent = fiber.cancel
            )
            val x = fiber.join.flatMap { interrupt =>
              context.interrupted.update(_.orElse(Some(interrupt))) >>
                context.deferred.complete(interrupt).attempt.void
            }
            concurrent.start(x).as(context)
          }
        }
        .getOrElse(F.pure(copy(cancelParent = F.unit)))
  }

  private object InterruptionContext {
    def unsafeFromInterruptible[F[_]](interruptible: Option[Concurrent[F]])(
        implicit F: Sync[F]): Option[InterruptionContext[F]] =
      interruptible.map { concurrent =>
        InterruptionContext[F](
          concurrent = concurrent,
          interrupted = Ref.unsafe(None),
          deferred = Deferred.unsafe(concurrent),
          cancelParent = F.unit
        )
      }
  }

  abstract class Lease[F[_]] {
    def cancel: F[Either[Throwable, Unit]]
  }
}

private[fs2] final class ScopedResource[F[_]](finalizer: ExitCase[Throwable] => F[Unit]) {
  def release(ec: ExitCase[Throwable])(
      implicit F: ApplicativeError[F, Throwable]): F[Option[Throwable]] =
    finalizer(ec).attempt.map(_.swap.toOption)
}

private[fs2] object ScopedResource {
  def apply[F[_]](finalizer: ExitCase[Throwable] => F[Unit]): ScopedResource[F] =
    new ScopedResource(finalizer)
}
