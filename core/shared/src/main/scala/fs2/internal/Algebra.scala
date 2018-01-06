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

  final case class Eval[F[_], O, R](value: F[R]) extends Algebra[F, O, R]
  final case class Acquire[F[_], O, R](resource: F[R], release: R => F[Unit])
      extends Algebra[F, O, (R, Token)]
  final case class Release[F[_], O](token: Token) extends Algebra[F, O, Unit]
  final case class OpenScope[F[_], O](s: FreeC[Algebra[F, O, ?], Unit],
                                      interruptible: Option[(Effect[F], ExecutionContext)])
      extends Algebra[F, O, Unit]
  final case class CloseScope[F[_], O](toClose: CompileScope[F, O]) extends Algebra[F, O, Unit]
  final case class GetScope[F[_], O]() extends Algebra[F, O, CompileScope[F, O]]

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

  private[fs2] def closeScope[F[_], O](toClose: CompileScope[F, O]): FreeC[Algebra[F, O, ?], Unit] =
    FreeC.Eval[Algebra[F, O, ?], Unit](CloseScope(toClose))

  private def scope0[F[_], O](
      s: FreeC[Algebra[F, O, ?], Unit],
      interruptible: Option[(Effect[F], ExecutionContext)]): FreeC[Algebra[F, O, ?], Unit] =
    FreeC.Eval[Algebra[F, O, ?], Unit](OpenScope(s, interruptible))

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
    type UnconsF[X] =
      Function2[
        CompileScope[F, O],
        Either[Throwable, Option[(Segment[X, Unit], FreeC[Algebra[F, X, ?], Unit])]],
        F[(CompileScope[F, O], FreeC[Algebra[F, O, ?], Unit])]
      ]

    def uncons[X](
        scope: CompileScope[F, O],
        xs: FreeC[Algebra[F, X, ?], Unit],
        unconsF: UnconsF[X],
        chunkSize: Int,
        maxSteps: Long
    ): F[(CompileScope[F, O], FreeC[Algebra[F, O, ?], Unit])] =
      F.flatMap(F.delay(xs.viewL.get)) {
        case done: FreeC.Pure[Algebra[F, X, ?], Unit]   => unconsF(scope, Right(None))
        case failed: FreeC.Fail[Algebra[F, X, ?], Unit] => unconsF(scope, Left(failed.error))
        case bound: FreeC.Bind[Algebra[F, X, ?], y, Unit] =>
          val f = bound.f
            .asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[F, X, ?], Unit]]
          val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F, X, ?], y]].fr

          fx match {
            case output: Algebra.Output[F, X] =>
              unconsF(scope, Right(Some((output.values, f(Right(()))))))

            case run: Algebra.Run[F, X, r] =>
              F.flatMap(scope.isInterrupted) {
                case None =>
                  val (h, t) =
                    run.values.force.splitAt(chunkSize, Some(maxSteps)) match {
                      case Left((r, chunks, _)) => (chunks, f(Right(r)))
                      case Right((chunks, tail)) =>
                        (chunks, segment(tail).transformWith(f))
                    }
                  unconsF(scope, Right(Some((Segment.catenatedChunks(h), t))))

                case Some(Right((scope, next))) =>
                  F.pure((scope, next))

                case Some(Left(err)) =>
                  uncons[X](scope, f(Left(err)), unconsF, chunkSize, maxSteps)
              }

            case u: Algebra.Uncons[F, y, X] =>
              F.flatMap(scope.isInterrupted) {
                case None =>
                  val unconsFy: UnconsF[y] = { (scope, ry) =>
                    uncons[X](scope, f(ry), unconsF, chunkSize, maxSteps)
                  }
                  uncons[y](scope, u.s, unconsFy, u.chunkSize, u.maxSteps)
                case Some(Right((scope, next))) =>
                  F.pure((scope, next))
                case Some(Left(err)) =>
                  uncons[X](scope, f(Left(err)), unconsF, chunkSize, maxSteps)
              }

            case eval: Algebra.Eval[F, X, _] =>
              F.flatMap(scope.interruptibleEval(eval.value)) {
                case Right(a)                   => uncons(scope, f(Right(a)), unconsF, chunkSize, maxSteps)
                case Left(Right((scope, next))) => F.pure((scope, next))
                case Left(Left(err))            => uncons(scope, f(Left(err)), unconsF, chunkSize, maxSteps)
              }

            case acquire: Algebra.Acquire[F, X, _] =>
              F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
                uncons(scope, f(r), unconsF, chunkSize, maxSteps)
              }

            case release: Algebra.Release[F, X] =>
              F.flatMap(scope.releaseResource(release.token)) { r =>
                uncons(scope, f(r), unconsF, chunkSize, maxSteps)
              }

            case c: Algebra.CloseScope[F, X] =>
              F.flatMap(c.toClose.close) { r =>
                F.flatMap(c.toClose.openAncestor) { scopeAfterClose =>
                  uncons(scopeAfterClose.asInstanceOf[CompileScope[F, O]],
                         f(r),
                         unconsF,
                         chunkSize,
                         maxSteps)
                }
              }

            case o: Algebra.OpenScope[F, X] =>
              val interruptible =
                o.interruptible.map {
                  case (effect, ec) =>
                    def onInterrupt(scope: CompileScope[F, O])
                      : F[(CompileScope[F, O], FreeC[Algebra[F, O, ?], Unit])] =
                      unconsF(scope, Right(None))
                    (effect, ec, onInterrupt _)
                }

              F.flatMap(scope.open(interruptible)) { innerScope =>
                val next: FreeC[Algebra[F, X, ?], Unit] = o.s.transformWith { r =>
                  Algebra
                    .closeScope[F, X](innerScope.asInstanceOf[CompileScope[F, X]]) // safe to cast no values are emitted
                    .transformWith { cr =>
                      f(CompositeFailure.fromResults(r, cr))
                    }
                }

                uncons[X](innerScope, next, unconsF, chunkSize, maxSteps)
              }

            case e: GetScope[F, X] =>
              uncons(scope, f(Right(scope)), unconsF, chunkSize, maxSteps)

          }

        case e =>
          sys.error(
            "FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was (unconcs): " + e)
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
              case Some(Left(err)) =>
                compileFoldLoop(scope, acc, g, f(Left(err)))
              case Some(Right((scope, next))) =>
                compileFoldLoop(scope, acc, g, next)
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
              case Some(Left(err)) =>
                compileFoldLoop(scope, acc, g, f(Left(err)))
              case Some(Right((scope, next))) =>
                compileFoldLoop(scope, acc, g, next)

            }

          case u: Algebra.Uncons[F, x, O] =>
            F.flatMap(scope.isInterrupted) {
              case None =>
                val unconsF: UnconsF[x] = (scope, r) => F.pure((scope, f(r)))
                F.flatMap(uncons[x](scope, u.s, unconsF, u.chunkSize, u.maxSteps)) {
                  case (scope, next) =>
                    compileFoldLoop(scope, acc, g, next)
                }
              case Some(Right((scope, next))) =>
                compileFoldLoop(scope, acc, g, next)

              case Some(Left(err)) =>
                compileFoldLoop(scope, acc, g, f(Left(err)))

            }

          case eval: Algebra.Eval[F, O, _] =>
            F.flatMap(scope.interruptibleEval(eval.value)) {
              case Right(a)                   => compileFoldLoop(scope, acc, g, f(Right(a)))
              case Left(Right((scope, next))) => compileFoldLoop(scope, acc, g, next)
              case Left(Left(err))            => compileFoldLoop(scope, acc, g, f(Left(err)))
            }

          case acquire: Algebra.Acquire[F, O, _] =>
            F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
              compileFoldLoop(scope, acc, g, f(r))
            }

          case release: Algebra.Release[F, O] =>
            F.flatMap(scope.releaseResource(release.token)) { r =>
              compileFoldLoop(scope, acc, g, f(r))
            }

          case c: Algebra.CloseScope[F, O] =>
            F.flatMap(c.toClose.close) { r =>
              F.flatMap(c.toClose.openAncestor) { scopeAfterClose =>
                compileFoldLoop(scopeAfterClose, acc, g, f(r))
              }
            }

          case o: Algebra.OpenScope[F, O] =>
            val interruptible = o.interruptible.map {
              case (effect, ec) =>
                def onInterrupt(scope: CompileScope[F, O])
                  : F[(CompileScope[F, O], FreeC[Algebra[F, O, ?], Unit])] =
                  F.pure((scope, f(Right(()))))
                (effect, ec, onInterrupt _)
            }

            F.flatMap(scope.open(interruptible)) { innerScope =>
              val next = o.s.transformWith { r =>
                Algebra.closeScope(innerScope).transformWith { cr =>
                  f(CompositeFailure.fromResults(r, cr))
                }
              }
              compileFoldLoop(innerScope, acc, g, next)
            }

          case e: GetScope[F, O] =>
            compileFoldLoop(scope, acc, g, f(Right(scope)))

        }

      case e =>
        sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  def translate[F[_], G[_], O, R](fr: FreeC[Algebra[F, O, ?], R],
                                  u: F ~> G): FreeC[Algebra[G, O, ?], R] = {
    def algFtoG[O2]: Algebra[F, O2, ?] ~> Algebra[G, O2, ?] =
      new (Algebra[F, O2, ?] ~> Algebra[G, O2, ?]) { self =>
        def apply[X](in: Algebra[F, O2, X]): Algebra[G, O2, X] = in match {
          case o: Output[F, O2] => Output[G, O2](o.values)
          case Run(values)      => Run[G, O2, X](values)
          case Eval(value)      => Eval[G, O2, X](u(value))
          case un: Uncons[F, x, O2] =>
            Uncons[G, x, O2](un.s.translate(algFtoG), un.chunkSize, un.maxSteps)
              .asInstanceOf[Algebra[G, O2, X]]
          case a: Acquire[F, O2, _] =>
            Acquire(u(a.resource), r => u(a.release(r)))
          case r: Release[F, O2]     => Release[G, O2](r.token)
          case os: OpenScope[F, O2]  => os.asInstanceOf[Algebra[G, O2, X]]
          case cs: CloseScope[F, O2] => cs.asInstanceOf[CloseScope[G, O2]]
          case gs: GetScope[F, O2]   => gs.asInstanceOf[Algebra[G, O2, X]]
        }
      }
    fr.translate[Algebra[G, O, ?]](algFtoG)
  }

}
