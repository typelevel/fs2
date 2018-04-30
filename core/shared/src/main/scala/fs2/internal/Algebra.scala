package fs2.internal

import cats.~>
import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2._

import scala.concurrent.ExecutionContext

private[fs2] sealed trait Algebra[F[_], O, R]

private[fs2] object Algebra {

  final case class Output[F[_], O](values: Segment[O, Unit]) extends Algebra[F, O, Unit]

  final case class Run[F[_], O, R](values: Segment[O, R]) extends Algebra[F, O, R]

  final case class Step[F[_], X, O](
      stream: FreeC[Algebra[F, X, ?], Unit],
      scope: Option[Token]
  ) extends Algebra[F, O, Option[(Segment[X, Unit], Token, FreeC[Algebra[F, X, ?], Unit])]]

  final case class Eval[F[_], O, R](value: F[R]) extends AlgEffect[F, O, R]

  final case class Acquire[F[_], O, R](resource: F[R], release: R => F[Unit])
      extends AlgEffect[F, O, (R, Token)]

  final case class Release[F[_], O](token: Token) extends AlgEffect[F, O, Unit]

  final case class OpenScope[F[_], O](interruptible: Option[(Effect[F], ExecutionContext)])
      extends AlgScope[F, O, Option[Token]]

  final case class CloseScope[F[_], O](scopeId: Token, interruptFallback: Boolean)
      extends AlgScope[F, O, Unit]

  final case class GetScope[F[_], O, X]() extends AlgEffect[F, O, CompileScope[F, X]]

  sealed trait AlgEffect[F[_], O, R] extends Algebra[F, O, R]

  implicit class AlgEffectSyntax[F[_], O, R](val self: AlgEffect[F, O, R]) extends AnyVal {

    // safe to cast, used in translate only
    // if interruption has to be supported effect for G has to be passed
    private[internal] def translate[G[_]](effect: Option[Effect[G]],
                                          fK: F ~> G): AlgEffect[G, O, R] =
      self match {
        case a: Acquire[F, O, r] =>
          Acquire[G, O, r](fK(a.resource), r => fK(a.release(r))).asInstanceOf[AlgEffect[G, O, R]]
        case e: Eval[F, O, R] => Eval[G, O, R](fK(e.value))
        case o: OpenScope[F, O] =>
          OpenScope[G, O](
            o.interruptible.flatMap {
              case (_, ec) =>
                effect.map { eff =>
                  (eff, ec)
                }
            }
          ).asInstanceOf[AlgEffect[G, O, R]]

        case r: Release[F, O]     => r.asInstanceOf[AlgEffect[G, O, R]]
        case c: CloseScope[F, O]  => c.asInstanceOf[AlgEffect[G, O, R]]
        case g: GetScope[F, O, x] => g.asInstanceOf[AlgEffect[G, O, R]]
      }
  }

  sealed trait AlgScope[F[_], O, R] extends AlgEffect[F, O, R]

  implicit class AlgScopeSyntax[F[_], O, R](val self: AlgScope[F, O, R]) extends AnyVal {
    // safe to typecast no output from open/close
    private[internal] def covaryOutput[O2]: AlgScope[F, O2, R] =
      self.asInstanceOf[AlgScope[F, O2, R]]
  }

  def output[F[_], O](values: Segment[O, Unit]): FreeC[Algebra[F, O, ?], Unit] =
    FreeC.Eval[Algebra[F, O, ?], Unit](Output(values))

  def output1[F[_], O](value: O): FreeC[Algebra[F, O, ?], Unit] =
    output(Segment.singleton(value))

  def segment[F[_], O, R](values: Segment[O, R]): FreeC[Algebra[F, O, ?], R] =
    FreeC.Eval[Algebra[F, O, ?], R](Run(values))

  def eval[F[_], O, R](value: F[R]): FreeC[Algebra[F, O, ?], R] =
    FreeC.Eval[Algebra[F, O, ?], R](Eval(value))

  def acquire[F[_], O, R](resource: F[R],
                          release: R => F[Unit]): FreeC[Algebra[F, O, ?], (R, Token)] =
    FreeC.Eval[Algebra[F, O, ?], (R, Token)](Acquire(resource, release))

  def release[F[_], O](token: Token): FreeC[Algebra[F, O, ?], Unit] =
    FreeC.Eval[Algebra[F, O, ?], Unit](Release(token))

  /**
    * Steps through the stream, providing either `uncons` or `stepLeg`.
    * Yields to head in form of segment, then id of the scope that was active after step evaluated and tail of the `stream`.
    *
    * @param stream             Stream to step
    * @param scopeId            If scope has to be changed before this step is evaluated, id of the scope must be supplied
    */
  private def step[F[_], O, X](
      stream: FreeC[Algebra[F, O, ?], Unit],
      scopeId: Option[Token]
  ): FreeC[Algebra[F, X, ?], Option[(Segment[O, Unit], Token, FreeC[Algebra[F, O, ?], Unit])]] =
    FreeC
      .Eval[Algebra[F, X, ?], Option[(Segment[O, Unit], Token, FreeC[Algebra[F, O, ?], Unit])]](
        Algebra.Step[F, O, X](stream, scopeId))

  def stepLeg[F[_], O](
      leg: Stream.StepLeg[F, O]): FreeC[Algebra[F, Nothing, ?], Option[Stream.StepLeg[F, O]]] =
    step[F, O, Nothing](
      leg.next,
      Some(leg.scopeId)
    ).map {
      _.map { case (h, id, t) => new Stream.StepLeg[F, O](h, id, t) }
    }

  /**
    * Wraps supplied pull in new scope, that will be opened before this pull is evaluated
    * and closed once this pull either finishes its evaluation or when it fails.
    */
  def scope[F[_], O](s: FreeC[Algebra[F, O, ?], Unit]): FreeC[Algebra[F, O, ?], Unit] =
    scope0(s, None)

  /**
    * Like `scope` but allows this scope to be interrupted.
    * Note that this may fail with `Interrupted` when interruption occurred
    */
  private[fs2] def interruptScope[F[_], O](s: FreeC[Algebra[F, O, ?], Unit])(
      implicit effect: Effect[F],
      ec: ExecutionContext): FreeC[Algebra[F, O, ?], Unit] =
    scope0(s, Some((effect, ec)))

  private[fs2] def openScope[F[_], O](interruptible: Option[(Effect[F], ExecutionContext)])
    : FreeC[Algebra[F, O, ?], Option[Token]] =
    FreeC.Eval[Algebra[F, O, ?], Option[Token]](OpenScope(interruptible))

  private[fs2] def closeScope[F[_], O](token: Token,
                                       interruptFallBack: Boolean): FreeC[Algebra[F, O, ?], Unit] =
    FreeC.Eval[Algebra[F, O, ?], Unit](CloseScope(token, interruptFallBack))

  private def scope0[F[_], O](
      s: FreeC[Algebra[F, O, ?], Unit],
      interruptible: Option[(Effect[F], ExecutionContext)]): FreeC[Algebra[F, O, ?], Unit] =
    openScope(interruptible).flatMap {
      case None =>
        pure(()) // in case of interruption the scope closure is handled by scope itself, before next step is returned
      case Some(scopeId) =>
        s.transformWith {
          case Right(_) => closeScope(scopeId, interruptFallBack = false)
          case Left(err) =>
            closeScope(scopeId, interruptFallBack = false).transformWith {
              case Right(_)   => raiseError(err)
              case Left(err0) => raiseError(CompositeFailure(err, err0, Nil))
            }
        }
    }

  def getScope[F[_], O, X]: FreeC[Algebra[F, O, ?], CompileScope[F, X]] =
    FreeC.Eval[Algebra[F, O, ?], CompileScope[F, X]](GetScope())

  def pure[F[_], O, R](r: R): FreeC[Algebra[F, O, ?], R] =
    FreeC.Pure[Algebra[F, O, ?], R](r)

  def raiseError[F[_], O, R](t: Throwable): FreeC[Algebra[F, O, ?], R] =
    FreeC.Fail[Algebra[F, O, ?], R](t)

  def suspend[F[_], O, R](f: => FreeC[Algebra[F, O, ?], R]): FreeC[Algebra[F, O, ?], R] =
    FreeC.suspend(f)

  def translate[F[_], G[_], O](
      s: FreeC[Algebra[F, O, ?], Unit],
      u: F ~> G
  )(implicit G: TranslateInterrupt[G]): FreeC[Algebra[G, O, ?], Unit] =
    translate0[F, G, O](u, s, G.effectInstance)

  def uncons[F[_], X, O](s: FreeC[Algebra[F, O, ?], Unit])
    : FreeC[Algebra[F, X, ?], Option[(Segment[O, Unit], FreeC[Algebra[F, O, ?], Unit])]] =
    step(s, None).map { _.map { case (h, _, t) => (h, t) } }

  /** Left-folds the output of a stream. */
  def compile[F[_], O, B](stream: FreeC[Algebra[F, O, ?], Unit], init: B)(f: (B, O) => B)(
      implicit F: Sync[F]): F[B] =
    F.delay(CompileScope.newRoot[F, O]).flatMap { scope =>
      compileScope[F, O, B](scope, stream, init)(f).attempt.flatMap {
        case Left(t) =>
          scope.close.flatMap {
            case Left(err) => F.raiseError(CompositeFailure(t, err, Nil))
            case _         => F.raiseError(t)
          }

        case Right(b) =>
          scope.close.flatMap {
            case Left(err) => F.raiseError(err)
            case _         => F.pure(b)
          }
      }
    }

  private[fs2] def compileScope[F[_], O, B](scope: CompileScope[F, O],
                                            stream: FreeC[Algebra[F, O, ?], Unit],
                                            init: B)(g: (B, O) => B)(implicit F: Sync[F]): F[B] =
    compileLoop[F, O](scope, stream).flatMap {
      case Some((output, scope, tail)) =>
        try {
          val b = output.fold(init)(g).force.run._2
          compileScope(scope, tail, b)(g)
        } catch {
          case NonFatal(err) =>
            compileScope(scope, tail.asHandler(err), init)(g)
        }
      case None =>
        F.pure(init)
    }

  private[fs2] def compileLoop[F[_], O](
      scope: CompileScope[F, O],
      stream: FreeC[Algebra[F, O, ?], Unit]
  )(implicit F: Sync[F])
    : F[Option[(Segment[O, Unit], CompileScope[F, O], FreeC[Algebra[F, O, ?], Unit])]] = {

    case class Done[X](scope: CompileScope[F, O]) extends R[X]
    case class Out[X](head: Segment[X, Unit],
                      scope: CompileScope[F, O],
                      tail: FreeC[Algebra[F, X, ?], Unit])
        extends R[X]
    case class Interrupted[X](nextScope: CompileScope[F, O], next: FreeC[Algebra[F, O, ?], Unit])
        extends R[X]
    case class OpenInterruptibly[X](
        scope: CompileScope[F, O],
        effect: Effect[F],
        ec: ExecutionContext,
        onInterrupt: FreeC[Algebra[F, X, ?], Unit],
        next: Either[Throwable, CompileScope[F, O]] => FreeC[Algebra[F, X, ?], Unit]
    ) extends R[X]

    sealed trait R[X]

    def go[X](
        scope: CompileScope[F, O],
        stream: FreeC[Algebra[F, X, ?], Unit]
    ): F[R[X]] = {
      F.flatMap(F.delay(stream.viewL.get)) {
        case _: FreeC.Pure[Algebra[F, X, ?], Unit] =>
          F.pure(Done(scope))

        case failed: FreeC.Fail[Algebra[F, X, ?], Unit] =>
          F.raiseError(failed.error)

        case e: FreeC.Eval[Algebra[F, X, ?], Unit] =>
          F.raiseError(
            new Throwable(
              s"FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k) was: $e"))

        case bound: FreeC.Bind[Algebra[F, X, ?], y, Unit] =>
          val f = bound.f
            .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[F, X, ?], Unit]]
          val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F, X, ?], y]].fr

          def interruptGuard(next: => F[R[X]]): F[R[X]] =
            F.flatMap(scope.isInterrupted) {
              case None => next
              case Some(Left(err)) =>
                go(scope, f(Left(err)))
              case Some(Right(scopeId)) =>
                scope.whenInterrupted(scopeId).map {
                  case (scope0, next) => Interrupted[X](scope0, next)
                }

            }

          fx match {
            case output: Algebra.Output[F, X] =>
              interruptGuard(
                F.pure(Out(output.values, scope, f(Right(()))))
              )

            case run: Algebra.Run[F, X, r] =>
              interruptGuard {
                val (h, t) =
                  // Values hardcoded here until we figure out how to properly expose them
                  run.values.force.splitAt(1024, Some(10000)) match {
                    case Left((r, chunks, _)) => (chunks, f(Right(r)))
                    case Right((chunks, tail)) =>
                      (chunks, segment(tail).transformWith(f))
                  }

                F.pure(Out(Segment.catenatedChunks(h), scope, t))
              }

            case u: Algebra.Step[F, y, X] =>
              // if scope was specified in step, try to find it, otherwise use the current scope.
              F.flatMap(u.scope.fold[F[Option[CompileScope[F, O]]]](F.pure(Some(scope))) {
                scopeId =>
                  scope.findStepScope(scopeId)
              }) {
                case Some(stepScope) =>
                  F.flatMap(F.attempt(go[y](stepScope, u.stream))) {
                    case Right(Done(scope)) =>
                      interruptGuard(
                        go(scope, f(Right(None)))
                      )
                    case Right(Out(head, outScope, tail)) =>
                      // if we originally swapped scopes we want to return the original
                      // scope back to the go as that is the scope that is expected to be here.
                      val nextScope = u.scope.fold(outScope)(_ => scope)
                      interruptGuard(
                        go(nextScope, f(Right(Some((head, outScope.id, tail)))))
                      )
                    case Right(Interrupted(scope, next)) => F.pure(Interrupted(scope, next))
                    case Right(OpenInterruptibly(scope, effect, ec, onInterrupt, next)) =>
                      def transform(s: FreeC[Algebra[F, y, ?], Unit]) =
                        step(s, None).transformWith(f)
                      F.pure(
                        OpenInterruptibly(scope, effect, ec, transform(onInterrupt), next.andThen {
                          s =>
                            transform(s)
                        }))
                    case Left(err) =>
                      go(scope, f(Left(err)))
                  }
                case None =>
                  F.raiseError(
                    new Throwable(
                      s"Fail to find scope for next step: current: ${scope.id}, step: $u"))
              }

            case eval: Algebra.Eval[F, X, r] =>
              F.flatMap(scope.interruptibleEval(eval.value)) {
                case Right(r)        => go[X](scope, f(Right(r)))
                case Left(Left(err)) => go[X](scope, f(Left(err)))
                case Left(Right(token)) =>
                  scope.whenInterrupted(token).map {
                    case (scope0, next) => Interrupted[X](scope0, next)
                  }
              }

            case acquire: Algebra.Acquire[F, X, r] =>
              interruptGuard {
                F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
                  go[X](scope, f(r))
                }
              }

            case release: Algebra.Release[F, X] =>
              F.flatMap(scope.releaseResource(release.token)) { r =>
                go[X](scope, f(r))
              }

            case _: Algebra.GetScope[F, X, y] =>
              go(scope, f(Right(scope)))

            case open: Algebra.OpenScope[F, X] =>
              interruptGuard {
                open.interruptible match {
                  case None =>
                    F.flatMap(scope.open(None)) { childScope =>
                      go(childScope, f(Right(Some(childScope.id))))
                    }

                  case Some((effect, ec)) =>
                    F.pure(
                      OpenInterruptibly(scope,
                                        effect,
                                        ec,
                                        FreeC.suspend(f(Right(None))),
                                        r => f(r.right.map(scope => Some(scope.id)))))
                }
              }

            case close: Algebra.CloseScope[F, X] =>
              if (close.interruptFallback) {
                scope.whenInterrupted(close.scopeId).map {
                  case (scope0, next) => Interrupted[X](scope0, next)
                }
              } else {
                def closeAndGo(toClose: CompileScope[F, O]) =
                  F.flatMap(toClose.close) { r =>
                    F.flatMap(toClose.openAncestor) { ancestor =>
                      go(ancestor, f(r))
                    }
                  }
                scope.findSelfOrAncestor(close.scopeId) match {
                  case Some(toClose) => closeAndGo(toClose)
                  case None =>
                    scope.findSelfOrChild(close.scopeId).flatMap {
                      case Some(toClose) =>
                        closeAndGo(toClose)
                      case None =>
                        // scope already closed, continue with current scope
                        go(scope, f(Right(())))
                    }
                }
              }

          }

      }
    }

    F.flatMap(go(scope, stream)) {
      case Done(_)                  => F.pure(None)
      case Out(head, scope, tail)   => F.pure(Some((head, scope, tail)))
      case Interrupted(scope, next) => compileLoop(scope, next)
      case OpenInterruptibly(scope, effect, ec, onInterrupt, next) =>
        F.flatMap(scope.open(Some((effect, ec, onInterrupt)))) { childScope =>
          compileLoop(childScope, next(Right(childScope)))
        }
    }
  }

  private def translateStep[F[_], G[_], X](
      fK: F ~> G,
      next: FreeC[Algebra[F, X, ?], Unit],
      effect: Option[Effect[G]]
  ): FreeC[Algebra[G, X, ?], Unit] =
    next.viewL.get match {
      case _: FreeC.Pure[Algebra[F, X, ?], Unit] =>
        FreeC.Pure[Algebra[G, X, ?], Unit](())

      case failed: FreeC.Fail[Algebra[F, X, ?], Unit] =>
        Algebra.raiseError(failed.error)

      case bound: FreeC.Bind[Algebra[F, X, ?], _, Unit] =>
        val f = bound.f
          .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[F, X, ?], Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F, X, ?], _]].fr

        fx match {
          case output: Algebra.Output[F, X] =>
            Algebra.output[G, X](output.values).transformWith {
              case Right(v) =>
                // Cast is safe here, as at this point the evaluation of this Step will end
                // and the remainder of the free will be passed as a result in Bind. As such
                // next Step will have this to evaluate, and will try to translate again.
                f(Right(v))
                  .asInstanceOf[FreeC[Algebra[G, X, ?], Unit]]
              case Left(err) => translateStep(fK, f(Left(err)), effect)
            }

          case run: Algebra.Run[F, X, r] =>
            Algebra.segment[G, X, r](run.values).transformWith {
              case Right(v) =>
                // Cast is safe here, as at this point the evaluation of this Step will end
                // and the remainder of the free will be passed as a result in Bind. As such
                // next Step will have this to evaluate, and will try to translate again.
                f(Right(v))
                  .asInstanceOf[FreeC[Algebra[G, X, ?], Unit]]
              case Left(err) => translateStep(fK, f(Left(err)), effect)
            }

          case step: Algebra.Step[F, x, X] =>
            FreeC
              .Eval[Algebra[G, X, ?],
                    Option[(Segment[x, Unit], Token, FreeC[Algebra[G, x, ?], Unit])]](
                Algebra.Step[G, x, X](
                  stream = translateStep[F, G, x](fK, step.stream, effect),
                  scope = step.scope
                ))
              .transformWith { r =>
                translateStep[F, G, X](fK, f(r), effect)
              }

          case alg: Algebra.AlgEffect[F, X, r] =>
            FreeC.Eval[Algebra[G, X, ?], r](alg.translate[G](effect, fK)).transformWith { r =>
              translateStep(fK, f(r), effect)
            }

        }

      case e =>
        sys.error(
          "FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), (translateLeg) was: " + e)
    }

  private def translate0[F[_], G[_], O](
      fK: F ~> G,
      s: FreeC[Algebra[F, O, ?], Unit],
      effect: Option[Effect[G]]
  ): FreeC[Algebra[G, O, ?], Unit] =
    s.viewL.get match {
      case _: FreeC.Pure[Algebra[F, O, ?], Unit] =>
        FreeC.Pure[Algebra[G, O, ?], Unit](())

      case failed: FreeC.Fail[Algebra[F, O, ?], Unit] =>
        Algebra.raiseError(failed.error)

      case bound: FreeC.Bind[Algebra[F, O, ?], _, Unit] =>
        val f = bound.f
          .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[F, O, ?], Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F, O, ?], _]].fr

        fx match {
          case output: Algebra.Output[F, O] =>
            Algebra.output[G, O](output.values).transformWith { r =>
              translate0(fK, f(r), effect)
            }

          case run: Algebra.Run[F, O, r] =>
            Algebra.segment[G, O, r](run.values).transformWith { r =>
              translate0(fK, f(r), effect)
            }

          case step: Algebra.Step[F, x, O] =>
            FreeC
              .Eval[Algebra[G, O, ?],
                    Option[(Segment[x, Unit], Token, FreeC[Algebra[G, x, ?], Unit])]](
                Algebra.Step[G, x, O](
                  stream = translateStep[F, G, x](fK, step.stream, effect),
                  scope = step.scope
                ))
              .transformWith { r =>
                translate0(fK, f(r), effect)
              }

          case alg: Algebra.AlgEffect[F, O, r] =>
            FreeC.Eval[Algebra[G, O, ?], r](alg.translate[G](effect, fK)).transformWith { r =>
              translate0(fK, f(r), effect)
            }

        }

      case e =>
        sys.error(
          "FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), (translateLeg) was: " + e)
    }

}
