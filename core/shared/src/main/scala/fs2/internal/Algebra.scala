package fs2.internal

import cats.data.NonEmptyList
import cats.~>
import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2._
import fs2.async.{Promise, Ref}

import scala.concurrent.ExecutionContext

private[fs2] sealed trait Algebra[F[_],O,R]

private[fs2] object Algebra {

  final case class Output[F[_],O](values: Segment[O,Unit]) extends Algebra[F,O,Unit]
  final case class Run[F[_],O,R](values: Segment[O,R]) extends Algebra[F,O,R]
  final case class Eval[F[_],O,R](value: F[R]) extends Algebra[F,O,R]

  final case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends Algebra[F,O,(R,Token)]
  final case class Release[F[_],O](token: Token) extends Algebra[F,O,Unit]
  final case class OpenScope[F[_],O](interruptible: Option[(Effect[F], ExecutionContext, Promise[F, Throwable], Ref[F, (Option[Throwable], Boolean)])]) extends Algebra[F,O,RunFoldScope[F]]
  final case class CloseScope[F[_],O](toClose: RunFoldScope[F]) extends Algebra[F,O,Unit]
  final case class GetScope[F[_],O]() extends Algebra[F,O,RunFoldScope[F]]

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

  private def scope0[F[_], O, R](pull: FreeC[Algebra[F,O,?],R], interruptible: Option[(Effect[F], ExecutionContext)]): FreeC[Algebra[F,O,?],R] = {

    def openScope: FreeC[Algebra[F,O,?],RunFoldScope[F]] =
       FreeC.Eval[Algebra[F,O,?],RunFoldScope[F]](
         OpenScope[F, O](interruptible.map { case (effect, ec) =>
           (effect, ec, Promise.unsafeCreate(effect, ec), Ref.unsafeCreate((None: Option[Throwable], false))(effect))
         })
       )

    def closeScope(toClose: RunFoldScope[F]): FreeC[Algebra[F,O,?],Unit] =
      FreeC.Eval[Algebra[F,O,?],Unit](CloseScope(toClose))

    openScope flatMap { scope =>
      pull.flatMap2 {
        case Left(e) => closeScope(scope) flatMap { _ => raiseError(e) }
        case Right(r) => closeScope(scope) map { _ => r }
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

  def uncons[F[_],X,O](s: FreeC[Algebra[F,O,?],Unit], chunkSize: Int = 1024, maxSteps: Long = 10000): FreeC[Algebra[F,X,?],Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] = {
    s.viewL.get match {
      case done: FreeC.Pure[Algebra[F,O,?], Unit] => pure(None)
      case failed: FreeC.Fail[Algebra[F,O,?], _] => raiseError(failed.error)
      case bound: FreeC.Bind[Algebra[F,O,?],_,Unit] =>
        val f = bound.f.asInstanceOf[Either[Throwable,Any] => FreeC[Algebra[F,O,?],Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case os: Algebra.Output[F, O] =>
            pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some((os.values, f(Right(())))))
          case os: Algebra.Run[F, O, x] =>
            try {
              def asSegment(c: Catenable[Chunk[O]]): Segment[O,Unit] =
                c.uncons.flatMap { case (h1,t1) => t1.uncons.map(_ => Segment.catenated(c.map(Segment.chunk))).orElse(Some(Segment.chunk(h1))) }.getOrElse(Segment.empty)
              os.values.force.splitAt(chunkSize, Some(maxSteps)) match {
                case Left((r,chunks,rem)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(chunks) -> f(Right(r))))
                case Right((chunks,tl)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(chunks) -> FreeC.Bind[Algebra[F,O,?],x,Unit](segment(tl), f)))
              }
            } catch { case NonFatal(e) => FreeC.suspend(uncons(f(Left(e)), chunkSize)) }
          case algebra => // Eval, Acquire, Release, OpenScope, CloseScope, GetScope
            FreeC.Bind[Algebra[F,X,?],Any,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](
              FreeC.Eval[Algebra[F,X,?],Any](algebra.asInstanceOf[Algebra[F,X,Any]]),
              (x: Either[Throwable,Any]) => uncons[F,X,O](f(x), chunkSize)
            )
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }


  /** Left-folds the output of a stream. */
  def runFold[F[_],O,B](stream: FreeC[Algebra[F,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
    F.delay(RunFoldScope.newRoot).flatMap { scope =>
      runFoldScope[F,O,B](scope, stream, init)(f).attempt.flatMap {
        case Left(t) => scope.close *> F.raiseError(t)
        case Right(b) => scope.close as b
      }
    }

  private[fs2] def runFoldScope[F[_],O,B](scope: RunFoldScope[F], stream: FreeC[Algebra[F,O,?],Unit], init: B)(g: (B, O) => B)(implicit F: Sync[F]): F[B] =
    runFoldLoop[F,O,B](scope, init, g, uncons(stream).viewL)

  private def runFoldLoop[F[_],O,B](
    scope: RunFoldScope[F]
    , acc: B
    , g: (B, O) => B
    , v: FreeC.ViewL[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]
  )(implicit F: Sync[F]): F[B] = {
    v.get match {
      case done: FreeC.Pure[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] => done.r match {
        case None => F.pure(acc) // in case of interrupt we ignore interrupt when this is done.
        case Some((hd, tl)) =>
          def next = {
            try runFoldLoop[F,O,B](scope, hd.fold(acc)(g).force.run, g, uncons(tl).viewL)
            catch { case NonFatal(e) => runFoldLoop[F,O,B](scope, acc, g, uncons(tl.asHandler(e)).viewL) }
          }

          if (scope.interruptible.isEmpty) F.suspend(next)
          else {
            F.flatMap(scope.shallInterrupt) {
              case None => next
              case Some(rsn) => runFoldLoop[F,O,B](scope, acc, g, uncons(tl.asHandler(rsn)).viewL)
            }
          }

      }

      case failed: FreeC.Fail[Algebra[F,O,?], _] =>
        if (scope.interruptible.nonEmpty && failed.error == Interrupted) F.pure(acc)
        else F.raiseError(failed.error)

      case bound: FreeC.Bind[Algebra[F,O,?], _, Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] =>
        val f = bound.f.asInstanceOf[
          Either[Throwable,Any] => FreeC[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case wrap: Algebra.Eval[F, O, _] =>
            F.flatMap(scope.interruptibleEval(wrap.value)) { r => runFoldLoop(scope, acc, g, f(r).viewL) }


          case acquire: Algebra.Acquire[F,_,_] =>
            val acquireResource = acquire.resource
            val resource = Resource.create
            F.flatMap(scope.register(resource)) { mayAcquire =>
              if (!mayAcquire) F.raiseError(Interrupted)
              else {
                F.flatMap(F.attempt(acquireResource)) {
                  case Right(r) =>
                    val finalizer = F.suspend { acquire.release(r) }
                    F.flatMap(resource.acquired(finalizer)) { result =>
                      runFoldLoop(scope, acc, g, f(result.right.map { _ => (r, resource.id) }).viewL)
                    }

                  case Left(err) =>
                    F.flatMap(scope.releaseResource(resource.id)) { result =>
                      val failedResult: Either[Throwable, Unit] =
                        result.left.toOption.map { err0 =>
                          Left(new CompositeFailure(err, NonEmptyList.of(err0)))
                         }.getOrElse(Left(err))
                      runFoldLoop(scope, acc, g, f(failedResult).viewL)
                    }
                }
              }
            }


          case release: Algebra.Release[F,_] =>
            F.flatMap(scope.releaseResource(release.token))  { result =>
              runFoldLoop(scope, acc, g, f(result).viewL)
            }

          case c: Algebra.CloseScope[F,_] =>
            F.flatMap(c.toClose.close) { result =>
              F.flatMap(c.toClose.openAncestor) { scopeAfterClose =>
                runFoldLoop(scopeAfterClose, acc, g, f(result).viewL)
              }
            }

          case o: Algebra.OpenScope[F,_] =>
            F.flatMap(scope.open(o.interruptible)) { innerScope =>
              runFoldLoop(innerScope, acc, g, f(Right(innerScope)).viewL)
            }

          case e: GetScope[F,_] =>
            F.suspend {
              runFoldLoop(scope, acc, g, f(Right(scope)).viewL)
            }

          case other => sys.error(s"impossible Segment or Output following uncons: $other")
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  def translate[F[_],G[_],O,R](fr: FreeC[Algebra[F,O,?],R], u: F ~> G): FreeC[Algebra[G,O,?],R] = {
    def algFtoG[O2]: Algebra[F,O2,?] ~> Algebra[G,O2,?] = new (Algebra[F,O2,?] ~> Algebra[G,O2,?]) { self =>
      def apply[X](in: Algebra[F,O2,X]): Algebra[G,O2,X] = in match {
        case o: Output[F,O2] => Output[G,O2](o.values)
        case Run(values) => Run[G,O2,X](values)
        case Eval(value) => Eval[G,O2,X](u(value))
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
    * Specific version of `Free.asHandler` that will output any CloseScope that may have
    * been opened before the `rsn` was yielded
    */
  def asHandler[F[_], O](free: FreeC[Algebra[F,O,?],Unit], e: Throwable): FreeC[Algebra[F,O,?],Unit] = {
    free.viewL.get match {
      case FreeC.Pure(_) => FreeC.Fail(e)
      case FreeC.Fail(e2) => FreeC.Fail(e)
      case bound: FreeC.Bind[Algebra[F,O,?], _, Unit] =>
        val f = bound.f.asInstanceOf[
          Either[Throwable,Any] => FreeC[Algebra[F,O,?], Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case _: Algebra.CloseScope[F,_] => FreeC.Bind(FreeC.Eval(fx), { (_: Either[Throwable, Any]) => f(Left(e)) })
          case _ => f(Left(e))
        }
      case FreeC.Eval(_) => sys.error("impossible")
    }
  }
}
