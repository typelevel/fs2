package fs2.internal

import cats.{MonadError, ~>}
import cats.effect.{Concurrent, ExitCase}
import cats.implicits._
import fs2._
import fs2.internal.FreeC.{Result, ViewL}

import scala.util.control.NonFatal

/* `Eval[F[_], O, R]` is a Generalised Algebraic Data Type (GADT)
 * of atomic instructions that can be evaluated in the effect `F`
 * to generate by-product outputs of type `O`.
 *
 * Each operation also generates an output of type `R` that is used
 * as control information for the rest of the interpretation or compilation.
 */
private[fs2] object Algebra {

  final case class Output[F[_], O](values: Chunk[O]) extends FreeC.Eval[F, O, Unit] {
    override def mapOutput[P](f: O => P): FreeC[F, P, Unit] =
      FreeC.suspend {
        try Output(values.map(f))
        catch { case NonFatal(t) => Result.Fail[F](t) }
      }
  }

  /**
    * Steps through the stream, providing either `uncons` or `stepLeg`.
    * Yields to head in form of chunk, then id of the scope that was active after step evaluated and tail of the `stream`.
    *
    * @param stream             Stream to step
    * @param scopeId            If scope has to be changed before this step is evaluated, id of the scope must be supplied
    */
  final case class Step[F[_], X](stream: FreeC[F, X, Unit], scope: Option[Token])
      extends FreeC.Eval[F, INothing, Option[(Chunk[X], Token, FreeC[F, X, Unit])]] {
    override def mapOutput[P](
        f: INothing => P
    ): FreeC[F, INothing, Option[(Chunk[X], Token, FreeC[F, X, Unit])]] = this
  }

  /* The `AlgEffect` trait is for operations on the `F` effect that create no `O` output.
   * They are related to resources and scopes. */
  sealed abstract class AlgEffect[F[_], R] extends FreeC.Eval[F, INothing, R] {
    final def mapOutput[P](f: INothing => P): FreeC[F, P, R] = this
  }

  final case class Eval[F[_], R](value: F[R]) extends AlgEffect[F, R]

  final case class Acquire[F[_], R](
      resource: F[R],
      release: (R, ExitCase[Throwable]) => F[Unit]
  ) extends AlgEffect[F, R]
  final case class OpenScope[F[_]](interruptible: Option[Concurrent[F]]) extends AlgEffect[F, Token]

  // `InterruptedScope` contains id of the scope currently being interrupted
  // together with any errors accumulated during interruption process
  final case class CloseScope[F[_]](
      scopeId: Token,
      interruptedScope: Option[(Token, Option[Throwable])],
      exitCase: ExitCase[Throwable]
  ) extends AlgEffect[F, Unit]

  final case class GetScope[F[_]]() extends AlgEffect[F, CompileScope[F]]

  def output1[F[_], O](value: O): FreeC[F, O, Unit] = Output(Chunk.singleton(value))

  def stepLeg[F[_], O](leg: Stream.StepLeg[F, O]): FreeC[F, Nothing, Option[Stream.StepLeg[F, O]]] =
    Step[F, O](leg.next, Some(leg.scopeId)).map {
      _.map { case (h, id, t) => new Stream.StepLeg[F, O](h, id, t) }
    }

  /**
    * Wraps supplied pull in new scope, that will be opened before this pull is evaluated
    * and closed once this pull either finishes its evaluation or when it fails.
    */
  def scope[F[_], O](s: FreeC[F, O, Unit]): FreeC[F, O, Unit] =
    scope0(s, None)

  /**
    * Like `scope` but allows this scope to be interrupted.
    * Note that this may fail with `Interrupted` when interruption occurred
    */
  private[fs2] def interruptScope[F[_], O](
      s: FreeC[F, O, Unit]
  )(implicit F: Concurrent[F]): FreeC[F, O, Unit] =
    scope0(s, Some(F))

  private def scope0[F[_], O](
      s: FreeC[F, O, Unit],
      interruptible: Option[Concurrent[F]]
  ): FreeC[F, O, Unit] =
    OpenScope(interruptible).flatMap { scopeId =>
      s.transformWith {
        case Result.Pure(_) => CloseScope(scopeId, interruptedScope = None, ExitCase.Completed)
        case Result.Interrupted(interruptedScopeId: Token, err) =>
          CloseScope(scopeId, interruptedScope = Some((interruptedScopeId, err)), ExitCase.Canceled)
        case Result.Fail(err) =>
          CloseScope(scopeId, interruptedScope = None, ExitCase.Error(err)).transformWith {
            case Result.Pure(_)    => Result.Fail(err)
            case Result.Fail(err0) => Result.Fail(CompositeFailure(err, err0, Nil))
            case Result.Interrupted(interruptedScopeId, _) =>
              sys.error(
                s"Impossible, cannot interrupt when closing failed scope: $scopeId, $interruptedScopeId, $err"
              )
          }

        case Result.Interrupted(ctx, _) => sys.error(s"Impossible context: $ctx")
      }
    }

  def translate[F[_], G[_], O](
      s: FreeC[F, O, Unit],
      u: F ~> G
  )(implicit G: TranslateInterrupt[G]): FreeC[G, O, Unit] =
    translate0[F, G, O](u, s, G.concurrentInstance)

  def uncons[F[_], X, O](s: FreeC[F, O, Unit]): FreeC[F, X, Option[(Chunk[O], FreeC[F, O, Unit])]] =
    Step(s, None).map { _.map { case (h, _, t) => (h, t) } }

  /** Left-folds the output of a stream. */
  def compile[F[_], O, B](
      stream: FreeC[F, O, Unit],
      scope: CompileScope[F],
      extendLastTopLevelScope: Boolean,
      init: B
  )(g: (B, Chunk[O]) => B)(implicit F: MonadError[F, Throwable]): F[B] =
    compileLoop[F, O](scope, extendLastTopLevelScope, stream).flatMap {
      case Some((output, scope, tail)) =>
        try {
          val b = g(init, output)
          compile(tail, scope, extendLastTopLevelScope, b)(g)
        } catch {
          case NonFatal(err) =>
            compile(tail.asHandler(err), scope, extendLastTopLevelScope, init)(g)
        }
      case None =>
        F.pure(init)
    }

  /*
   * Interruption of the stream is tightly coupled between FreeC, Algebra and CompileScope
   * Reason for this is unlike interruption of `F` type (i.e. IO) we need to find
   * recovery point where stream evaluation has to continue in Stream algebra
   *
   * As such the `Token` is passed to FreeC.Interrupted as glue between FreeC/Algebra that allows pass-along
   * information for Algebra and scope to correctly compute recovery point after interruption was signalled via `CompilerScope`.
   *
   * This token indicates scope of the computation where interruption actually happened.
   * This is used to precisely find most relevant interruption scope where interruption shall be resumed
   * for normal continuation of the stream evaluation.
   *
   * Interpreter uses this to find any parents of this scope that has to be interrupted, and guards the
   * interruption so it won't propagate to scope that shall not be anymore interrupted.
   */
  private[this] def compileLoop[F[_], O](
      scope: CompileScope[F],
      extendLastTopLevelScope: Boolean,
      stream: FreeC[F, O, Unit]
  )(
      implicit F: MonadError[F, Throwable]
  ): F[Option[(Chunk[O], CompileScope[F], FreeC[F, O, Unit])]] = {

    case class Done[X](scope: CompileScope[F]) extends R[X]
    case class Out[X](head: Chunk[X], scope: CompileScope[F], tail: FreeC[F, X, Unit]) extends R[X]
    case class Interrupted[X](scopeId: Token, err: Option[Throwable]) extends R[X]
    sealed trait R[X]

    def go[X](
        scope: CompileScope[F],
        extendedTopLevelScope: Option[CompileScope[F]],
        stream: FreeC[F, X, Unit]
    ): F[R[X]] =
      stream.viewL match {
        case _: FreeC.Result.Pure[F, Unit] =>
          F.pure(Done(scope))

        case failed: FreeC.Result.Fail[F] =>
          F.raiseError(failed.error)

        case interrupted: FreeC.Result.Interrupted[F, _] =>
          interrupted.context match {
            case scopeId: Token => F.pure(Interrupted(scopeId, interrupted.deferredError))
            case other          => sys.error(s"Unexpected interruption context: $other (compileLoop)")
          }

        case view: ViewL.View[F, X, y, Unit] =>
          def resume(res: Result[y]): F[R[X]] =
            go[X](scope, extendedTopLevelScope, view.next(res))

          def interruptGuard(scope: CompileScope[F])(next: => F[R[X]]): F[R[X]] =
            F.flatMap(scope.isInterrupted) {
              case None => next
              case Some(Left(err)) =>
                go(scope, extendedTopLevelScope, view.next(Result.Fail(err)))
              case Some(Right(scopeId)) =>
                go(scope, extendedTopLevelScope, view.next(Result.Interrupted(scopeId, None)))
            }
          view.step match {
            case output: Output[F, X] =>
              interruptGuard(scope)(
                F.pure(Out(output.values, scope, view.next(FreeC.Result.unit)))
              )

            case u: Step[F, y] =>
              // if scope was specified in step, try to find it, otherwise use the current scope.
              F.flatMap(u.scope.fold[F[Option[CompileScope[F]]]](F.pure(Some(scope))) { scopeId =>
                scope.findStepScope(scopeId)
              }) {
                case Some(stepScope) =>
                  F.flatMap(F.attempt(go[y](stepScope, extendedTopLevelScope, u.stream))) {
                    case Right(Done(scope)) =>
                      interruptGuard(scope)(
                        go(scope, extendedTopLevelScope, view.next(Result.Pure(None)))
                      )
                    case Right(Out(head, outScope, tail)) =>
                      // if we originally swapped scopes we want to return the original
                      // scope back to the go as that is the scope that is expected to be here.
                      val nextScope = if (u.scope.isEmpty) outScope else scope
                      val result = Result.Pure(Some((head, outScope.id, tail)))
                      interruptGuard(nextScope)(
                        go(nextScope, extendedTopLevelScope, view.next(result))
                      )

                    case Right(Interrupted(scopeId, err)) =>
                      go(scope, extendedTopLevelScope, view.next(Result.Interrupted(scopeId, err)))

                    case Left(err) =>
                      go(scope, extendedTopLevelScope, view.next(Result.Fail(err)))
                  }

                case None =>
                  F.raiseError(
                    new Throwable(
                      s"Fail to find scope for next step: current: ${scope.id}, step: $u"
                    )
                  )
              }

            case eval: Eval[F, r] =>
              F.flatMap(scope.interruptibleEval(eval.value)) {
                case Right(r)           => resume(Result.Pure(r))
                case Left(Left(err))    => resume(Result.Fail(err))
                case Left(Right(token)) => resume(Result.Interrupted(token, None))
              }

            case acquire: Acquire[F, r] =>
              interruptGuard(scope) {
                F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
                  resume(Result.fromEither(r))
                }
              }

            case _: GetScope[F] =>
              resume(Result.Pure(scope.asInstanceOf[y]))

            case open: OpenScope[F] =>
              interruptGuard(scope) {
                val maybeCloseExtendedScope: F[Boolean] =
                  // If we're opening a new top-level scope (aka, direct descendant of root),
                  // close the current extended top-level scope if it is defined.
                  if (scope.parent.isEmpty)
                    extendedTopLevelScope match {
                      case None    => false.pure[F]
                      case Some(s) => s.close(ExitCase.Completed).rethrow.as(true)
                    }
                  else F.pure(false)
                maybeCloseExtendedScope.flatMap { closedExtendedScope =>
                  val newExtendedScope = if (closedExtendedScope) None else extendedTopLevelScope
                  F.flatMap(scope.open(open.interruptible)) {
                    case Left(err) =>
                      go(scope, newExtendedScope, view.next(Result.Fail(err)))
                    case Right(childScope) =>
                      go(childScope, newExtendedScope, view.next(Result.Pure(childScope.id)))
                  }
                }
              }

            case close: CloseScope[F] =>
              def closeAndGo(toClose: CompileScope[F], ec: ExitCase[Throwable]) =
                F.flatMap(toClose.close(ec)) { r =>
                  F.flatMap(toClose.openAncestor) { ancestor =>
                    val res = close.interruptedScope match {
                      case None => Result.fromEither(r)
                      case Some((interruptedScopeId, err)) =>
                        def err1 = CompositeFailure.fromList(r.swap.toOption.toList ++ err.toList)
                        if (ancestor.findSelfOrAncestor(interruptedScopeId).isDefined) {
                          // we still have scopes to interrupt, lets build interrupted tail
                          Result.Interrupted(interruptedScopeId, err1)
                        } else {
                          // interrupts scope was already interrupted, resume operation
                          err1 match {
                            case None      => Result.unit
                            case Some(err) => Result.Fail(err)
                          }

                        }
                    }
                    go(ancestor, extendedTopLevelScope, view.next(res))
                  }
                }

              val scopeToClose = scope
                .findSelfOrAncestor(close.scopeId)
                .pure[F]
                .orElse(scope.findSelfOrChild(close.scopeId))
              scopeToClose.flatMap {
                case Some(toClose) =>
                  if (toClose.parent.isEmpty) {
                    // Impossible - don't close root scope as a result of a `CloseScope` call
                    go(scope, extendedTopLevelScope, view.next(Result.unit))
                  } else if (extendLastTopLevelScope && toClose.parent.flatMap(_.parent).isEmpty) {
                    // Request to close the current top-level scope - if we're supposed to extend
                    // it instead, leave the scope open and pass it to the continuation
                    extendedTopLevelScope.traverse_(_.close(ExitCase.Completed).rethrow) *>
                      toClose.openAncestor.flatMap(
                        ancestor => go(ancestor, Some(toClose), view.next(Result.unit))
                      )
                  } else closeAndGo(toClose, close.exitCase)
                case None =>
                  // scope already closed, continue with current scope
                  val result = close.interruptedScope match {
                    case Some((x, y)) => Result.Interrupted(x, y)
                    case None         => Result.unit
                  }
                  go(scope, extendedTopLevelScope, view.next(result))
              }
          }
      }

    F.flatMap(go(scope, None, stream)) {
      case Done(_)                => F.pure(None)
      case Out(head, scope, tail) => F.pure(Some((head, scope, tail)))
      case Interrupted(_, err) =>
        err match {
          case None      => F.pure(None)
          case Some(err) => F.raiseError(err)
        }
    }
  }

  /**
    * Inject interruption to the tail used in flatMap.
    * Assures that close of the scope is invoked if at the flatMap tail, otherwise switches evaluation to `interrupted` path
    *
    * @param stream             tail to inject interruption into
    * @param interruptedScope   scopeId to interrupt
    * @param interruptedError   Additional finalizer errors
    * @tparam F
    * @tparam O
    * @return
    */
  def interruptBoundary[F[_], O](
      stream: FreeC[F, O, Unit],
      interruptedScope: Token,
      interruptedError: Option[Throwable]
  ): FreeC[F, O, Unit] =
    stream.viewL match {
      case _: FreeC.Result.Pure[F, Unit] =>
        Result.Interrupted(interruptedScope, interruptedError)
      case failed: FreeC.Result.Fail[F] =>
        Result.Fail(
          CompositeFailure
            .fromList(interruptedError.toList :+ failed.error)
            .getOrElse(failed.error)
        )
      case interrupted: Result.Interrupted[F, _] =>
        // impossible
        Result.Interrupted(interrupted.context, interrupted.deferredError)

      case view: ViewL.View[F, O, _, Unit] =>
        view.step match {
          case close: CloseScope[F] =>
            CloseScope(
              close.scopeId,
              Some((interruptedScope, interruptedError)),
              ExitCase.Canceled
            ) // Inner scope is getting closed b/c a parent was interrupted
              .transformWith(view.next)
          case _ =>
            // all other cases insert interruption cause
            view.next(Result.Interrupted(interruptedScope, interruptedError))

        }

    }

  private def translate0[F[_], G[_], O](
      fK: F ~> G,
      stream: FreeC[F, O, Unit],
      concurrent: Option[Concurrent[G]]
  ): FreeC[G, O, Unit] = {

    def translateStep[X](next: FreeC[F, X, Unit], isMainLevel: Boolean): FreeC[G, X, Unit] =
      next.viewL match {
        case _: FreeC.Result.Pure[F, Unit] =>
          Result.Pure[G, Unit](())

        case failed: FreeC.Result.Fail[F] =>
          Result.Fail(failed.error)

        case interrupted: FreeC.Result.Interrupted[F, _] =>
          Result.Interrupted(interrupted.context, interrupted.deferredError)

        case view: ViewL.View[F, X, y, Unit] =>
          view.step match {
            case output: Output[F, X] =>
              Output[G, X](output.values).transformWith {
                case r @ Result.Pure(_) if isMainLevel =>
                  translateStep(view.next(r), isMainLevel)

                case r @ Result.Pure(_) if !isMainLevel =>
                  // Cast is safe here, as at this point the evaluation of this Step will end
                  // and the remainder of the free will be passed as a result in Bind. As such
                  // next Step will have this to evaluate, and will try to translate again.
                  view.next(r).asInstanceOf[FreeC[G, X, Unit]]

                case r @ Result.Fail(_) => translateStep(view.next(r), isMainLevel)

                case r @ Result.Interrupted(_, _) => translateStep(view.next(r), isMainLevel)
              }

            case step: Step[F, x] =>
              Step[G, x](
                stream = translateStep[x](step.stream, false),
                scope = step.scope
              ).transformWith { r =>
                translateStep[X](view.next(r.asInstanceOf[Result[y]]), isMainLevel)
              }

            case alg: AlgEffect[F, r] =>
              translateAlgEffect(alg, concurrent, fK)
                .transformWith(r => translateStep(view.next(r), isMainLevel))

          }
      }

    translateStep[O](stream, true)
  }

  private[this] def translateAlgEffect[F[_], G[_], R](
      self: AlgEffect[F, R],
      concurrent: Option[Concurrent[G]],
      fK: F ~> G
  ): AlgEffect[G, R] = self match {
    // safe to cast, used in translate only
    // if interruption has to be supported concurrent for G has to be passed
    case a: Acquire[F, r] =>
      Acquire[G, r](fK(a.resource), (r, ec) => fK(a.release(r, ec)))
        .asInstanceOf[AlgEffect[G, R]]
    case e: Eval[F, R]    => Eval[G, R](fK(e.value))
    case _: OpenScope[F]  => OpenScope[G](concurrent).asInstanceOf[AlgEffect[G, R]]
    case c: CloseScope[F] => c.asInstanceOf[AlgEffect[G, R]]
    case g: GetScope[F]   => g.asInstanceOf[AlgEffect[G, R]]
  }

}
