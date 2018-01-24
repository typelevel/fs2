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
  final case class Uncons[F[_], X, O](s: FreeC[Algebra[F, X, ?], Unit],
                                      chunkSize: Int,
                                      maxSteps: Long)
      extends Algebra[F, O, Option[(Segment[X, Unit], FreeC[Algebra[F, X, ?], Unit])]]

  final case class Eval[F[_], O, R](value: F[R]) extends AlgEffect[F, O, R]
  final case class Acquire[F[_], O, R](resource: F[R], release: R => F[Unit])
      extends AlgEffect[F, O, (R, Token)]
  final case class Release[F[_], O](token: Token) extends AlgEffect[F, O, Unit]
  final case class OpenScope[F[_], O](interruptible: Option[(Effect[F], ExecutionContext)])
      extends AlgScope[F, O, Option[CompileScope[F, O]]]
  final case class CloseScope[F[_], O](scopeId: Token) extends AlgScope[F, O, Unit]
  final case class GetScope[F[_], O]() extends AlgEffect[F, O, CompileScope[F, O]]
  final case class Translate[F[_], G[_], O](s: FreeC[Algebra[G, O, ?], Unit],
                                            fK: G ~> F,
                                            effect: Option[Effect[F]])
      extends Algebra[F, O, Unit]

  sealed trait AlgEffect[F[_], O, R] extends Algebra[F, O, R]

  implicit class AlgEffectSyntax[F[_], O, R](val self: AlgEffect[F, O, R]) extends AnyVal {
    // safe to typecast no output from open/close
    private[internal] def covaryOutput[O2]: AlgEffect[F, O2, R] =
      self.asInstanceOf[AlgEffect[F, O2, R]]

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

        case r: Release[F, O]    => r.asInstanceOf[AlgEffect[G, O, R]]
        case c: CloseScope[F, O] => c.asInstanceOf[AlgEffect[G, O, R]]
        case g: GetScope[F, O]   => g.asInstanceOf[AlgEffect[G, O, R]]
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
    : FreeC[Algebra[F, O, ?], Option[CompileScope[F, O]]] =
    FreeC.Eval[Algebra[F, O, ?], Option[CompileScope[F, O]]](OpenScope(interruptible))

  private[fs2] def closeScope[F[_], O](token: Token): FreeC[Algebra[F, O, ?], Unit] =
    FreeC.Eval[Algebra[F, O, ?], Unit](CloseScope(token))

  private def scope0[F[_], O](
      s: FreeC[Algebra[F, O, ?], Unit],
      interruptible: Option[(Effect[F], ExecutionContext)]): FreeC[Algebra[F, O, ?], Unit] =
    openScope(interruptible).flatMap {
      case None =>
        pure(()) // in case of interruption the scope closure is handled by scope itself, before next step is returned
      case Some(scope) =>
        s.transformWith {
          case Right(_) => closeScope(scope.id)
          case Left(err) =>
            closeScope(scope.id).transformWith {
              case Right(_)   => raiseError(err)
              case Left(err0) => raiseError(CompositeFailure(err, err0, Nil))
            }
        }
    }
  // FreeC.Eval[Algebra[F, O, ?], Unit](OpenScope(s, interruptible))

  def getScope[F[_], O]: FreeC[Algebra[F, O, ?], CompileScope[F, O]] =
    FreeC.Eval[Algebra[F, O, ?], CompileScope[F, O]](GetScope())

  def pure[F[_], O, R](r: R): FreeC[Algebra[F, O, ?], R] =
    FreeC.Pure[Algebra[F, O, ?], R](r)

  def raiseError[F[_], O, R](t: Throwable): FreeC[Algebra[F, O, ?], R] =
    FreeC.Fail[Algebra[F, O, ?], R](t)

  def suspend[F[_], O, R](f: => FreeC[Algebra[F, O, ?], R]): FreeC[Algebra[F, O, ?], R] =
    FreeC.suspend(f)

  def uncons[F[_], X, O](s: FreeC[Algebra[F, O, ?], Unit],
                         chunkSize: Int = 1024,
                         maxSteps: Long = 10000)
    : FreeC[Algebra[F, X, ?], Option[(Segment[O, Unit], FreeC[Algebra[F, O, ?], Unit])]] =
    FreeC.Eval[Algebra[F, X, ?], Option[(Segment[O, Unit], FreeC[Algebra[F, O, ?], Unit])]](
      Algebra.Uncons[F, O, X](s, chunkSize, maxSteps))

  /** Left-folds the output of a stream. */
  def compile[F[_], O, B](stream: FreeC[Algebra[F, O, ?], Unit], init: B)(f: (B, O) => B)(
      implicit F: Sync[F]): F[B] =
    F.delay(CompileScope.newRoot[F, O]).flatMap { scope =>
      compileScope[F, O, B](scope, stream, init)(f).attempt.flatMap {
        case Left(t)  => scope.close *> F.raiseError(t)
        case Right(b) => scope.close.as(b)
      }
    }

  private[fs2] def compileScope[F[_], O, B](scope: CompileScope[F, O],
                                            stream: FreeC[Algebra[F, O, ?], Unit],
                                            init: B)(g: (B, O) => B)(implicit F: Sync[F]): F[B] =
    compileFoldLoop[F, O, B](scope, init, g, stream)

  private def compileFoldLoop[F[_], O, B](
      scope: CompileScope[F, O],
      acc: B,
      g: (B, O) => B,
      v: FreeC[Algebra[F, O, ?], Unit]
  )(implicit F: Sync[F]): F[B] = {

    // Uncons is interrupted, fallback on `compileFoldLoop` has to be invoked
    // The token is a scope from which we should recover.
    case class Interrupted[X](token: Token) extends UR[X]
    // uncons is done
    case class Done[X](result: Option[(Segment[X, Unit], FreeC[Algebra[F, X, ?], Unit])])
        extends UR[X]
    // uncons shall continue with result of `f` once `alg` is evaluated
    // used in OpenScope and CloseScope, to assure scopes are opened and closed only on main Loop.
    case class Continue[X, R](alg: AlgScope[F, X, R],
                              f: Either[Throwable, R] => FreeC[Algebra[F, X, ?], Unit])
        extends UR[X]
    sealed trait UR[X]

    def uncons[X](
        xs: FreeC[Algebra[F, X, ?], Unit],
        chunkSize: Int,
        maxSteps: Long
    ): F[UR[X]] =
      F.flatMap(F.delay(xs.viewL.get)) {
        case done: FreeC.Pure[Algebra[F, X, ?], Unit] =>
          F.pure(Done(None))

        case failed: FreeC.Fail[Algebra[F, X, ?], Unit] =>
          F.raiseError(failed.error)

        case bound: FreeC.Bind[Algebra[F, X, ?], y, Unit] =>
          val f = bound.f
            .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[F, X, ?], Unit]]
          val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F, X, ?], y]].fr
          fx match {
            case output: Algebra.Output[F, X] =>
              F.pure(Done(Some((output.values, f(Right(()))))))

            case run: Algebra.Run[F, X, r] =>
              F.flatMap(scope.isInterrupted) {
                case None =>
                  val (h, t) =
                    run.values.force.splitAt(chunkSize, Some(maxSteps)) match {
                      case Left((r, chunks, _)) => (chunks, f(Right(r)))
                      case Right((chunks, tail)) =>
                        (chunks, segment(tail).transformWith(f))
                    }
                  F.pure(Done(Some((Segment.catenatedChunks(h), t))))

                case Some(interrupted) =>
                  interrupted.fold(F.raiseError, token => F.pure(Interrupted[X](token)))
              }

            case u: Algebra.Uncons[F, y, X] =>
              F.flatMap(scope.isInterrupted) {
                case None =>
                  F.flatMap(F.attempt(uncons[y](u.s, u.chunkSize, u.maxSteps))) {
                    case Left(err)                 => uncons(f(Left(err)), chunkSize, maxSteps)
                    case Right(Done(r))            => uncons(f(Right(r)), chunkSize, maxSteps)
                    case Right(Interrupted(token)) => F.pure(Interrupted(token))
                    case Right(cont: Continue[y, r]) =>
                      F.pure(
                        Continue[X, r](
                          cont.alg.covaryOutput[X],
                          r =>
                            Algebra
                              .uncons[F, X, y](cont.f(r), u.chunkSize, u.maxSteps)
                              .transformWith(f)
                        ))

                  }
                case Some(interrupted) =>
                  interrupted.fold(F.raiseError, token => F.pure(Interrupted[X](token)))
              }

            case eval: Algebra.Eval[F, X, _] =>
              F.flatMap(scope.interruptibleEval(eval.value)) {
                case Right(r)           => uncons(f(Right(r)), chunkSize, maxSteps)
                case Left(Right(token)) => F.pure(Interrupted(token))
                case Left(Left(err))    => uncons(f(Left(err)), chunkSize, maxSteps)

              }

            case acquire: Algebra.Acquire[F, X, _] =>
              F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
                uncons(f(r), chunkSize, maxSteps)
              }

            case release: Algebra.Release[F, X] =>
              F.flatMap(scope.releaseResource(release.token)) { r =>
                uncons(f(r), chunkSize, maxSteps)
              }

            case get: Algebra.GetScope[F, X] =>
              uncons(f(Right(scope)), chunkSize, maxSteps)

            case scope: AlgScope[F, X, r] =>
              F.pure(Continue(scope, f))

            case translate: Translate[F, h, X] @unchecked =>
              uncons(compileTranslate[F, h, X](translate.fK, translate.s, translate.effect)
                       .transformWith(f),
                     chunkSize,
                     maxSteps)

          }

        case e =>
          sys.error(
            "compile#uncons: FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was (unconcs): " + e)

      }

    F.flatMap(F.delay(v.viewL.get)) {
      case done: FreeC.Pure[Algebra[F, O, ?], Unit] =>
        F.pure(acc)

      case failed: FreeC.Fail[Algebra[F, O, ?], Unit] =>
        F.raiseError(failed.error)

      case bound: FreeC.Bind[Algebra[F, O, ?], _, Unit] =>
        val f = bound.f
          .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[F, O, ?], Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F, O, ?], _]].fr

        def onInterrupt(r: Either[Throwable, Token]) =
          r.fold(
            err => compileFoldLoop(scope, acc, g, f(Left(err))),
            token =>
              F.flatMap(scope.whenInterrupted(token)) {
                case (scope, next) => compileFoldLoop(scope, acc, g, next)
            }
          )

        fx match {
          case output: Algebra.Output[F, O] =>
            F.flatMap(scope.isInterrupted) {
              case None =>
                try {
                  val b = output.values.fold(acc)(g).force.run._2
                  compileFoldLoop(scope, b, g, f(Right(())))
                } catch {
                  case NonFatal(err) =>
                    compileFoldLoop(scope, acc, g, f(Left(err)))
                }
              case Some(interrupted) =>
                onInterrupt(interrupted)

            }

          case run: Algebra.Run[F, O, r] =>
            F.flatMap(scope.isInterrupted) {
              case None =>
                try {
                  val (r, b) = run.values.fold(acc)(g).force.run
                  compileFoldLoop(scope, b, g, f(Right(r)))
                } catch {
                  case NonFatal(err) =>
                    compileFoldLoop(scope, acc, g, f(Left(err)))
                }
              case Some(interrupted) =>
                onInterrupt(interrupted)

            }

          case u: Algebra.Uncons[F, x, O] =>
            F.flatMap(scope.isInterrupted) {
              case None =>
                F.flatMap(F.attempt(uncons[x](u.s, u.chunkSize, u.maxSteps))) {
                  case Left(err) =>
                    compileFoldLoop(scope, acc, g, f(Left(err)))

                  case Right(Done(r)) =>
                    compileFoldLoop(scope, acc, g, f(Right(r)))

                  case Right(Interrupted(token)) =>
                    onInterrupt(Right(token))

                  case Right(cont: Continue[x, r]) =>
                    val next =
                      FreeC
                        .Eval[Algebra[F, O, ?], r](cont.alg.covaryOutput[O])
                        .transformWith { r =>
                          Algebra
                            .uncons[F, O, x](cont.f(r), u.chunkSize, u.maxSteps)
                            .transformWith(f)
                        }

                    compileFoldLoop(scope, acc, g, next)

                }
              case Some(interrupted) =>
                onInterrupt(interrupted)
            }

          case eval: Algebra.Eval[F, O, _] =>
            F.flatMap(scope.interruptibleEval(eval.value)) {
              case Right(r)           => compileFoldLoop(scope, acc, g, f(Right(r)))
              case Left(Right(token)) => onInterrupt(Right(token))
              case Left(Left(err))    => compileFoldLoop(scope, acc, g, f(Left(err)))

            }

          case acquire: Algebra.Acquire[F, O, _] =>
            F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
              compileFoldLoop(scope, acc, g, f(r))
            }

          case release: Algebra.Release[F, O] =>
            F.flatMap(scope.releaseResource(release.token)) { r =>
              compileFoldLoop(scope, acc, g, f(r))
            }

          case open: Algebra.OpenScope[F, O] =>
            val interruptible =
              open.interruptible.map {
                case (effect, ec) => (effect, ec, f(Right(None)))
              }
            F.flatMap(scope.open(interruptible)) { childScope =>
              compileFoldLoop(childScope, acc, g, f(Right(Some(childScope))))
            }

          case close: Algebra.CloseScope[F, O] =>
            scope.findSelfOrAncestor(close.scopeId) match {
              case Some(toClose) =>
                F.flatMap(toClose.close) { r =>
                  F.flatMap(toClose.openAncestor) { ancestor =>
                    compileFoldLoop(ancestor, acc, g, f(r))
                  }
                }
              case None =>
                val rsn = new IllegalStateException(
                  "Failed to close scope that is not active scope or ancestor")
                compileFoldLoop(scope, acc, g, f(Left(rsn)))
            }

          case e: GetScope[F, O] =>
            compileFoldLoop(scope, acc, g, f(Right(scope)))

          case translate: Translate[F, h, O] @unchecked =>
            compileFoldLoop(scope,
                            acc,
                            g,
                            compileTranslate[F, h, O](translate.fK, translate.s, translate.effect)
                              .transformWith(f))

        }

      case e =>
        sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  def compileTranslate[F[_], G[_], O](
      fK: G ~> F,
      s: FreeC[Algebra[G, O, ?], Unit],
      effect: Option[Effect[F]]
  ): FreeC[Algebra[F, O, ?], Unit] = {

    // Uncons is interrupted, fallback on `compileFoldLoop` has to be invoked
    // The token is a scope from which we should recover.
    case class Interrupted[X](token: Token) extends UR[X]
    // uncons is done
    case class Done[X](result: Option[(Segment[X, Unit], FreeC[Algebra[G, X, ?], Unit])])
        extends UR[X]
    // uncons shall continue with result of `f` once `alg` is evaluated
    // used in OpenScope and CloseScope, to assure scopes are opened and closed only on main Loop.
    case class Continue[X, R](alg: AlgEffect[G, X, R],
                              f: Either[Throwable, R] => FreeC[Algebra[G, X, ?], Unit])
        extends UR[X]

    case class Error[X](rsn: Throwable) extends UR[X]

    sealed trait UR[X]

    def uncons[X](
        xs: FreeC[Algebra[G, X, ?], Unit],
        chunkSize: Int,
        maxSteps: Long
    ): UR[X] =
      xs.viewL.get match {
        case done: FreeC.Pure[Algebra[G, X, ?], Unit] =>
          Done(None)

        case failed: FreeC.Fail[Algebra[G, X, ?], Unit] =>
          Error(failed.error)

        case bound: FreeC.Bind[Algebra[G, X, ?], y, Unit] =>
          val f = bound.f
            .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[G, X, ?], Unit]]
          val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[G, X, ?], y]].fr

          fx match {
            case output: Algebra.Output[G, X] =>
              Done(Some((output.values, f(Right(())))))

            case run: Algebra.Run[G, X, r] =>
              val (h, t) =
                run.values.force.splitAt(chunkSize, Some(maxSteps)) match {
                  case Left((r, chunks, _)) => (chunks, f(Right(r)))
                  case Right((chunks, tail)) =>
                    (chunks, segment(tail).transformWith(f))
                }

              Done(Some((Segment.catenatedChunks(h), t)))

            case u: Algebra.Uncons[G, y, X] =>
              uncons[y](u.s, u.chunkSize, u.maxSteps) match {
                case Done(r)            => uncons(f(Right(r)), chunkSize, maxSteps)
                case Interrupted(token) => Interrupted[X](token)
                case cont: Continue[y, r] =>
                  Continue[X, r](
                    cont.alg.covaryOutput[X],
                    r =>
                      Algebra
                        .uncons[G, X, y](cont.f(r), u.chunkSize, u.maxSteps)
                        .transformWith(f)
                  )
                case Error(err) => uncons(f(Left(err)), chunkSize, maxSteps)
              }

            case effect: Algebra.AlgEffect[G, X, r] =>
              Continue(effect, f)

            case translate: Algebra.Translate[G, h, X] @unchecked =>
              uncons(compileTranslate[G, h, X](translate.fK, translate.s, translate.effect)
                       .transformWith(f),
                     chunkSize,
                     maxSteps)

          }

        case e =>
          sys.error(
            "FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), (compileTranslate) was: " + e)
      }

    s.viewL.get match {
      case done: FreeC.Pure[Algebra[G, O, ?], Unit] =>
        FreeC.Pure[Algebra[F, O, ?], Unit](())

      case failed: FreeC.Fail[Algebra[G, O, ?], Unit] =>
        Algebra.raiseError(failed.error)

      case bound: FreeC.Bind[Algebra[G, O, ?], _, Unit] =>
        val f = bound.f
          .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[G, O, ?], Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[G, O, ?], _]].fr

        fx match {
          case output: Algebra.Output[G, O] =>
            Algebra.output(output.values).transformWith { r =>
              compileTranslate(fK, f(r), effect)
            }

          case run: Algebra.Run[G, O, r] =>
            Algebra.segment(run.values).transformWith { r =>
              compileTranslate(fK, f(r), effect)
            }

          case u: Algebra.Uncons[G, x, O] =>
            uncons[x](u.s, u.chunkSize, u.maxSteps) match {
              case Done(r) => compileTranslate(fK, f(Right(r)), effect)
              case Interrupted(_) =>
                compileTranslate(fK,
                                 f(Left(new Throwable("Translated scope cannot be interrupted"))),
                                 effect)
              case cont: Continue[x, r] =>
                FreeC
                  .Eval[Algebra[F, O, ?], r](cont.alg.covaryOutput[O].translate[F](None, fK))
                  .transformWith { r =>
                    val next: FreeC[Algebra[G, O, ?], Unit] =
                      Algebra.uncons(cont.f(r), u.chunkSize, u.maxSteps).transformWith(f)
                    compileTranslate(fK, next, effect)
                  }

              case Error(rsn) => compileTranslate(fK, f(Left(rsn)), effect)
            }

          case alg: Algebra.AlgEffect[G, O, r] =>
            FreeC.Eval[Algebra[F, O, ?], r](alg.translate[F](effect, fK)).transformWith { r =>
              compileTranslate(fK, f(r), effect)
            }

          case translate: Algebra.Translate[G, h, O] @unchecked =>
            // nested translate is not supported hence we don't have a way to build Sync[G] from Sync[F] and G ~> F
            val sg = compileTranslate(translate.fK, translate.s, translate.effect).transformWith(f)
            compileTranslate(fK, sg, effect)
        }

      case e =>
        sys.error(
          "FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), (compileTranslate) was: " + e)
    }
  }
  def translate[F[_], G[_], O](
      s: FreeC[Algebra[F, O, ?], Unit],
      u: F ~> G
  )(implicit G: TranslateInterrupt[G]): FreeC[Algebra[G, O, ?], Unit] =
    FreeC.Eval[Algebra[G, O, ?], Unit](Algebra.Translate[G, F, O](s, u, G.effectInstance))

}
