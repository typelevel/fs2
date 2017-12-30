package fs2.internal

import cats.~>
import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2._

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

private[fs2] sealed trait Algebra[F[_],O,R]

private[fs2] sealed trait AlgebraEffect[F[_], O, R] extends Algebra[F, O, R]

private[fs2] object Algebra {

  final case class Output[F[_],O](values: Segment[O,Unit]) extends Algebra[F,O,Unit]
  final case class Run[F[_],O,R](values: Segment[O,R]) extends Algebra[F,O,R]
  final case class Uncons[F[_], X, O](s: FreeC[Algebra[F,X,?],Unit], chunkSize: Int, maxSteps: Long) extends Algebra[F, O, Option[(Segment[X,Unit], FreeC[Algebra[F,X,?],Unit])]]

  final case class Eval[F[_],O,R](value: F[R]) extends AlgebraEffect[F,O,R]
  final case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends AlgebraEffect[F,O,(R,Token)]
  final case class Release[F[_],O](token: Token) extends AlgebraEffect[F,O,Unit]
  final case class OpenScope[F[_],O](interruptible: Option[(Effect[F], ExecutionContext)]) extends AlgebraEffect[F,O,RunFoldScope[F]]
  final case class CloseScope[F[_],O](toClose: RunFoldScope[F]) extends AlgebraEffect[F,O,Unit]
  final case class GetScope[F[_],O]() extends AlgebraEffect[F,O,RunFoldScope[F]]

  def output[F[_],O](values: Segment[O,Unit]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Output(values))

  def output1[F[_],O](value: O): FreeC[Algebra[F,O,?],Unit] =
    output(Segment.singleton(value))

  def segment[F[_],O,R](values: Segment[O,R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Run(values))

  def eval[F[_],O,R](value: F[R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Eval(value))

  def acquire[F[_],O,R](resource: F[R], release: R => F[Unit]): FreeC[Algebra[F,O,?],(R,Token)] =
    FreeC.Eval[Algebra[F,O,?],(R,Token)](Acquire(resource, release))

  def release[F[_],O](token: Token): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Release(token))

  /**
    *  Wraps supplied pull in new scope, that will be opened before this pull is evaluated
    *  and closed once this pull either finishes its evaluation or when it fails.
    */
  def scope[F[_],O,R](pull: FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    scope0(pull, None)

  /**
    * Like `scope` but allows this scope to be interrupted.
    * Note that this may fail with `Interrupted` when interruption occurred
    */
  private[fs2] def interruptScope[F[_], O, R](pull: FreeC[Algebra[F,O,?],R])(implicit effect: Effect[F], ec: ExecutionContext): FreeC[Algebra[F,O,?],R] =
    scope0(pull, Some((effect, ec)))


  private[fs2] def openScope[F[_], O](interruptible: Option[(Effect[F], ExecutionContext)]): FreeC[Algebra[F,O,?],RunFoldScope[F]] =
    FreeC.Eval[Algebra[F,O,?],RunFoldScope[F]](OpenScope[F, O](interruptible))

  private[fs2] def closeScope[F[_], O](toClose: RunFoldScope[F]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](CloseScope(toClose))

  private def scope0[F[_], O, R](pull: FreeC[Algebra[F,O,?],R], interruptible: Option[(Effect[F], ExecutionContext)]): FreeC[Algebra[F,O,?],R] = {
    openScope(interruptible) flatMap { scope =>
      pull.transformWith {
        case Right(r) => closeScope(scope) map { _ => r }
        case Left(e) => closeScope(scope) flatMap { _ => raiseError(e) }
      }
    }
  }

  def getScope[F[_],O]: FreeC[Algebra[F,O,?],RunFoldScope[F]] =
    FreeC.Eval[Algebra[F,O,?],RunFoldScope[F]](GetScope())

  def pure[F[_],O,R](r: R): FreeC[Algebra[F,O,?],R] =
    FreeC.Pure[Algebra[F,O,?],R](r)

  def raiseError[F[_],O,R](t: Throwable): FreeC[Algebra[F,O,?],R] =
    FreeC.Fail[Algebra[F,O,?],R](t)

  def suspend[F[_],O,R](f: => FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    FreeC.suspend(f)

  def uncons[F[_],X,O](s: FreeC[Algebra[F,O,?],Unit], chunkSize: Int = 1024, maxSteps: Long = 10000): FreeC[Algebra[F,X,?],Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] =
    FreeC.Eval[Algebra[F,X,?],Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Algebra.Uncons[F,O,X](s, chunkSize, maxSteps))


  /** Left-folds the output of a stream. */
  def runFold[F[_],O,B](stream: FreeC[Algebra[F,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
    F.delay(RunFoldScope.newRoot).flatMap { scope =>
      runFoldScope[F,O,B](scope, stream, init)(f).attempt.flatMap {
        case Left(t) => scope.close *> F.raiseError(t)
        case Right(b) => scope.close as b
      }
    }

  private[fs2] def runFoldScope[F[_],O,B](scope: RunFoldScope[F], stream: FreeC[Algebra[F,O,?],Unit], init: B)(g: (B, O) => B)(implicit F: Sync[F]): F[B] =
    runFoldLoop[F,O,B](scope, init, g, stream)

  private def runUncons[F[_],X,O](
    scope: RunFoldScope[F]
    , s: FreeC[Algebra[F,O,?],Unit]
    , chunkSize: Int
    , maxSteps: Long
  )(implicit F: Sync[F]): F[(RunFoldScope[F], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])])] = {
    F.delay(s.viewL.get) flatMap { x => /* println(s"UNCS(${scope.id}): $x"); */ x match {
      case done: FreeC.Pure[Algebra[F,O,?], Unit] => F.pure((scope, None))
      case failed: FreeC.Fail[Algebra[F,O,?], Unit] => F.raiseError(failed.error)
      case bound: FreeC.Bind[Algebra[F,O,?], _, Unit] =>
        val f = bound.f.asInstanceOf[Either[Throwable,Any] => FreeC[Algebra[F,O,?], Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case output: Algebra.Output[F, O] =>
            F.pure((scope, Some((output.values, f(Right(()))))))

          case run: Algebra.Run[F, O, r] =>
            val (h, t) =
            run.values.force.splitAt(chunkSize, Some(maxSteps)) match {
              case Left((r, chunks, _)) => (chunks, f(Right(r)))
              case Right((chunks, tail)) => (chunks, segment(tail).transformWith(f))
            }
            F.pure((scope, Some((Segment.catenatedChunks(h), t))))

          case uncons: Algebra.Uncons[F, x, O] =>
            F.flatMap(F.attempt(runUncons(scope, uncons.s, uncons.chunkSize, uncons.maxSteps))) {
              case Right((scope, u)) => runUncons(scope, FreeC.suspend(f(Right(u))), chunkSize, maxSteps)
              case Left(err) => runUncons(scope, FreeC.suspend(f(Left(err))), chunkSize, maxSteps)
            }

          case eff: AlgebraEffect[F, O, _] =>
            F.flatMap(runAlgebraEffect(scope, eff)) { case (scope, r) =>
              runUncons(scope, f(r), chunkSize, maxSteps)
            }

        }

      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was (unconcs): " + e)
    }}

  }



  private def runFoldLoop[F[_],O,B](
    scope: RunFoldScope[F]
    , acc: B
    , g: (B, O) => B
    , v: FreeC[Algebra[F, O, ?], Unit]
  )(implicit F: Sync[F]): F[B] = {
    //println(s"RFLD(${scope.id}) : ${scope.interruptible.nonEmpty}")
    F.flatMap(F.delay { v.viewL.get }) { x =>
   // println(s"RFLD(${scope.id}) : $x")
    x match {
      case done: FreeC.Pure[Algebra[F,O,?], Unit] =>
        F.pure(acc)

      case failed: FreeC.Fail[Algebra[F,O,?], Unit] =>
        F.raiseError(failed.error)

      case bound: FreeC.Bind[Algebra[F,O,?], _, Unit] =>
        val f = bound.f.asInstanceOf[Either[Throwable,Any] => FreeC[Algebra[F,O,?], Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        F.flatMap(scope.shallInterrupt) {
          case None =>
            fx match {
              case output: Algebra.Output[F, O] =>
                try {
                  runFoldLoop(scope, output.values.fold(acc)(g).force.run, g, f(Right(())))
                }
                catch {
                  case err: Throwable => runFoldLoop(scope, acc, g, f(Left(err)))
                }

              case run: Algebra.Run[F, O, r] =>
                try {
                  @tailrec
                  def go(values: Segment[O, r], acc: B): (B, r) = {
                    //println(s">>> $values")
                    values.force.uncons match {
                      case Left(r) => (acc, r)
                      case Right((h, t)) => go(t, h.fold(acc)(g).force.run)
                    }
                  }

                  val (b,r) = go(run.values, acc)
                  runFoldLoop(scope, b, g, f(Right(r)))
                } catch {
                  case err: Throwable => runFoldLoop(scope, acc, g, f(Left(err)))
                }


              case uncons: Algebra.Uncons[F, x, O] =>
                F.flatMap(F.attempt(runUncons(scope, uncons.s, uncons.chunkSize, uncons.maxSteps))) {
                  case Right((scope, u)) => runFoldLoop(scope, acc, g,
                    f(Right(u))
                  )
                  case Left(err) => runFoldLoop(scope, acc, g, f(Left(err)))
                }

              case eff: AlgebraEffect[F, O, _] =>
                F.flatMap(runAlgebraEffect(scope, eff)) { case (scope, r) =>
                  runFoldLoop(scope, acc, g, f(r))
                }

            }
          case Some(rsn) => runFoldLoop(scope, acc, g, f(Left(rsn)))
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }}
  }

  def runAlgebraEffect[F[_], O](
    scope: RunFoldScope[F]
    , eff: AlgebraEffect[F, O, _]
  )(implicit F: Sync[F]): F[(RunFoldScope[F], Either[Throwable,Any])] = {
    eff match {
      case eval: Algebra.Eval[F, O, _] =>
        F.map(scope.interruptibleEval(eval.value)) { (scope, _) }

      case acquire: Algebra.Acquire[F, O, _] =>
        F.map(scope.acquireResource(acquire.resource, acquire.release)) { (scope, _) }

      case release: Algebra.Release[F,O] =>
        F.map(scope.releaseResource(release.token)) { (scope, _) }

      case c: Algebra.CloseScope[F,_] =>
        F.flatMap(c.toClose.close) { result =>
          F.map(c.toClose.openAncestor) { scopeAfterClose => (scopeAfterClose, result) }
        }

      case o: Algebra.OpenScope[F,_] =>
        F.map(scope.open(o.interruptible)) { innerScope => (innerScope, Right(innerScope)) }

      case e: GetScope[F,_] =>
        F.delay((scope, Right(scope)))
    }
  }

  def translate[F[_],G[_],O,R](fr: FreeC[Algebra[F,O,?],R], u: F ~> G): FreeC[Algebra[G,O,?],R] = {
    def algFtoG[O2]: Algebra[F,O2,?] ~> Algebra[G,O2,?] = new (Algebra[F,O2,?] ~> Algebra[G,O2,?]) { self =>
      def apply[X](in: Algebra[F,O2,X]): Algebra[G,O2,X] = in match {
        case o: Output[F,O2] => Output[G,O2](o.values)
        case Run(values) => Run[G,O2,X](values)
        case Eval(value) => Eval[G,O2,X](u(value))
        case un:Uncons[F,x,O2] => Uncons[G,x,O2](FreeC.suspend(un.s.translate(algFtoG)), un.chunkSize, un.maxSteps).asInstanceOf[Algebra[G,O2,X]]
        case a: Acquire[F,O2,_] => Acquire(u(a.resource), r => u(a.release(r)))
        case r: Release[F,O2] => Release[G,O2](r.token)
        case os: OpenScope[F,O2] => os.asInstanceOf[Algebra[G,O2,X]]
        case cs: CloseScope[F,O2] => cs.asInstanceOf[CloseScope[G,O2]]
        case gs: GetScope[F,O2] => gs.asInstanceOf[Algebra[G,O2,X]]
      }
    }
    fr.translate[Algebra[G,O,?]](algFtoG)
  }

  /**
    * If the current stream evaluation scope is scope defined in `interrupted` or any ancestors of current scope
    * then this will continue with evaluation of `s` as interrupted stream.
    * Otherwise, this continues with `s` normally, and `interrupted` is ignored.
    */
  private[fs2] def interruptEventually[F[_], O](s: FreeC[Algebra[F,O,?],Unit], interrupted: Interrupted): FreeC[Algebra[F,O,?],Unit] = {
    Algebra.getScope.flatMap { scope =>
      def loopsExceeded: Boolean = interrupted.loop >= scope.interruptible.map(_.maxInterruptDepth).getOrElse(0)
      if (scope.id == interrupted.scopeId) {
        if (loopsExceeded) raiseError(interrupted)
        else s.asHandler(interrupted.copy(loop = interrupted.loop + 1))
      } else {
        Algebra.eval(scope.hasAncestor(interrupted.scopeId)).flatMap { hasAncestor =>
          if (!hasAncestor) s
          else if (loopsExceeded) raiseError(interrupted.copy(loop = 0))
          else s.asHandler(interrupted.copy(loop = interrupted.loop + 1))
        }
      }
    }
  }

}
