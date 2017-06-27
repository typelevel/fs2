package fs2.internal

import scala.collection.mutable.LinkedHashMap
import scala.concurrent.ExecutionContext
import cats.~>
import cats.effect.{ Effect, IO, Sync }

import fs2.{ AsyncPull, Catenable, Segment }
import fs2.async

private[fs2] sealed trait Algebra[F[_],O,R]

private[fs2] object Algebra {

  final class Token {
    override def toString = s"Token(${##})"
  }

  final case class Output[F[_],O](values: Segment[O,Unit]) extends Algebra[F,O,Unit]
  final case class WrapSegment[F[_],O,R](values: Segment[O,R]) extends Algebra[F,O,R]
  final case class Eval[F[_],O,R](value: F[R]) extends Algebra[F,O,R]
  final case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends Algebra[F,O,(R,Token)]
  final case class Release[F[_],O](token: Token) extends Algebra[F,O,Unit]
  final case class UnconsAsync[F[_],X,Y,O](s: FreeC[Algebra[F,O,?],Unit], effect: Effect[F], ec: ExecutionContext)
    extends Algebra[F,X,AsyncPull[F,Option[(Segment[O,Unit],FreeC[Algebra[F,O,?],Unit])]]]
  final case class OpenScope[F[_],O]() extends Algebra[F,O,(Scope[F],Scope[F])]
  final case class CloseScope[F[_],O](toClose: Scope[F], scopeAfterClose: Scope[F]) extends Algebra[F,O,Unit]
  final case class Suspend[F[_],O,R](thunk: () => FreeC[Algebra[F,O,?],R]) extends Algebra[F,O,R]

  def output[F[_],O](values: Segment[O,Unit]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Output(values))

  def output1[F[_],O](value: O): FreeC[Algebra[F,O,?],Unit] =
    output(Segment.singleton(value))

  def segment[F[_],O,R](values: Segment[O,R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](WrapSegment(values))

  def eval[F[_],O,R](value: F[R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Eval(value))

  def acquire[F[_],O,R](resource: F[R], release: R => F[Unit]): FreeC[Algebra[F,O,?],(R,Token)] =
    FreeC.Eval[Algebra[F,O,?],(R,Token)](Acquire(resource, release))

  def release[F[_],O](token: Token): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Release(token))

  def unconsAsync[F[_],X,Y,O](s: FreeC[Algebra[F,O,?],Unit])(implicit F: Effect[F], ec: ExecutionContext): FreeC[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]] =
    FreeC.Eval[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit],FreeC[Algebra[F,O,?],Unit])]]](UnconsAsync(s, F, ec))

  private def openScope[F[_],O]: FreeC[Algebra[F,O,?],(Scope[F],Scope[F])] =
    FreeC.Eval[Algebra[F,O,?],(Scope[F],Scope[F])](OpenScope())

  private def closeScope[F[_],O](toClose: Scope[F], scopeAfterClose: Scope[F]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](CloseScope(toClose, scopeAfterClose))

  def scope[F[_],O,R](pull: FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    openScope flatMap { case (oldScope, newScope) =>
      FreeC.Bind(pull, (e: Either[Throwable,R]) => e match {
        case Left(e) => closeScope(newScope, oldScope) flatMap { _ => fail(e) }
        case Right(r) => closeScope(newScope, oldScope) map { _ => r }
      })
    }

  def pure[F[_],O,R](r: R): FreeC[Algebra[F,O,?],R] =
    FreeC.Pure[Algebra[F,O,?],R](r)

  def fail[F[_],O,R](t: Throwable): FreeC[Algebra[F,O,?],R] =
    FreeC.Fail[Algebra[F,O,?],R](t)

  def suspend[F[_],O,R](f: => FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Suspend(() => f))

  final class Scope[F[_]](implicit F: Sync[F]) {
    private val monitor = this
    private var closing: Boolean = false
    private var closed: Boolean = false
    private var midAcquires: Int = 0
    private var midAcquiresDone: () => Unit = () => ()
    private val resources: LinkedHashMap[Token, F[Unit]] = new LinkedHashMap[Token, F[Unit]]()
    private var spawns: Catenable[Scope[F]] = Catenable.empty

    def isClosed: Boolean = monitor.synchronized { closed }

    def beginAcquire: Boolean = monitor.synchronized {
      if (closed || closing) false
      else {
        midAcquires += 1; true
      }
    }

    def finishAcquire(t: Token, finalizer: F[Unit]): Unit = monitor.synchronized {
      if (closed) throw new IllegalStateException("FS2 bug: scope cannot be closed while acquire is outstanding")
      resources += (t -> finalizer)
      midAcquires -= 1
      if (midAcquires == 0) midAcquiresDone()
    }

    def cancelAcquire(): Unit = monitor.synchronized {
      midAcquires -= 1
      if (midAcquires == 0) midAcquiresDone()
    }

    def releaseResource(t: Token): Option[F[Unit]] = monitor.synchronized {
      resources.remove(t)
    }

    def open: Scope[F] = monitor.synchronized {
      if (closing || closed) throw new IllegalStateException("Cannot open new scope on a closed scope")
      else {
        val spawn = new Scope[F]()
        spawns = spawns :+ spawn
        spawn
      }
    }

    private def closeAndReturnFinalizers(asyncSupport: Option[(Effect[F], ExecutionContext)]): F[Catenable[(Token,F[Unit])]] = monitor.synchronized {
      if (closed || closing) {
        F.pure(Catenable.empty)
      } else {
        closing = true
        def finishClose: F[Catenable[(Token,F[Unit])]] = monitor.synchronized {
          import cats.syntax.traverse._
          import cats.syntax.functor._
          import cats.instances.vector._
          spawns.toVector.reverse.
            traverse(_.closeAndReturnFinalizers(asyncSupport)).
            map(_.foldLeft(Catenable.empty: Catenable[(Token,F[Unit])])(_ ++ _)).
            map { s =>
              monitor.synchronized {
                closed = true
                val result = s ++ Catenable.fromSeq(resources.toVector.reverse)
                resources.clear()
                result
              }
            }
        }
        if (midAcquires == 0) {
          finishClose
        } else {
          asyncSupport match {
            case None => throw new IllegalStateException(s"FS2 bug: closing a scope with midAcquires ${midAcquires} but no async steps")
            case Some((effect, ec)) =>
              val ref = new async.Ref[F,Unit]()(effect, ec)
              midAcquiresDone = () => {
                effect.runAsync(ref.setAsyncPure(()))(_ => IO.unit).unsafeRunSync
              }
              F.flatMap(ref.get) { _ => finishClose }
          }
        }
      }
    }

    def close(asyncSupport: Option[(Effect[F],ExecutionContext)]): F[Either[Throwable,Unit]] = {
      def runAll(sofar: Option[Throwable], finalizers: Catenable[F[Unit]]): F[Either[Throwable,Unit]] = finalizers.uncons match {
        case None => F.pure(sofar.toLeft(()))
        case Some((h, t)) => F.flatMap(F.attempt(h)) { res => runAll(sofar orElse res.fold(Some(_), _ => None), t) }
      }
      F.flatMap(closeAndReturnFinalizers(asyncSupport)) { finalizers =>
        runAll(None, finalizers.map(_._2))
      }
    }

    override def toString: String = ##.toString
  }

  def uncons[F[_],X,O](s: FreeC[Algebra[F,O,?],Unit], chunkSize: Int = 1024): FreeC[Algebra[F,X,?],Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] = {
    s.viewL.get match {
      case done: FreeC.Pure[Algebra[F,O,?], Unit] => pure(None)
      case failed: FreeC.Fail[Algebra[F,O,?], _] => fail(failed.error)
      case bound: FreeC.Bind[Algebra[F,O,?],_,Unit] =>
        val f = bound.f.asInstanceOf[Either[Throwable,Any] => FreeC[Algebra[F,O,?],Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case os: Algebra.Output[F, O] =>
            pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some((os.values, f(Right(())))))
          case os: Algebra.WrapSegment[F, O, x] =>
            try {
              def asSegment(c: Catenable[Segment[O,Unit]]): Segment[O,Unit] =
                c.uncons.flatMap { case (h1,t1) => t1.uncons.map(_ => Segment.catenated(c)).orElse(Some(h1)) }.getOrElse(Segment.empty)
              os.values.splitAt(chunkSize) match {
                case Left((r,segments,rem)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(segments) -> f(Right(r))))
                case Right((segments,tl)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(segments) -> FreeC.Bind[Algebra[F,O,?],x,Unit](segment(tl), f)))
              }
            } catch { case NonFatal(e) => FreeC.suspend(uncons(f(Left(e)), chunkSize)) }
          case Algebra.Suspend(thunk) =>
            try uncons(FreeC.Bind(thunk(), f), chunkSize)
            catch { case NonFatal(e) => FreeC.suspend(uncons(f(Left(e)), chunkSize)) }
          case algebra => // Eval, Acquire, Release, OpenScope, CloseScope, UnconsAsync
            FreeC.Bind[Algebra[F,X,?],Any,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](
              FreeC.Eval[Algebra[F,X,?],Any](algebra.asInstanceOf[Algebra[F,X,Any]]),
              f andThen (s => uncons[F,X,O](s, chunkSize))
            )
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  /** Left-fold the output of a stream. */
  def runFold[F2[_],O,B](stream: FreeC[Algebra[F2,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F2]): F2[B] =
    F.flatMap(F.delay(new Scope[F2]())) { scope =>
      F.flatMap(F.attempt(runFold_(stream, init)(f, scope))) {
        case Left(t) => F.flatMap(scope.close(None))(_ => F.raiseError(t))
        case Right(b) => F.flatMap(scope.close(None))(_ => F.pure(b))
      }
    }

  def runFold_[F2[_],O,B](stream: FreeC[Algebra[F2,O,?],Unit], init: B)(
      g: (B, O) => B, scope: Scope[F2])(implicit F: Sync[F2]): F2[B] = {
    type AlgebraF[x] = Algebra[F,O,x]
    type F[x] = F2[x] // scala bug, if don't put this here, inner function thinks `F` has kind *
    def go(scope: Scope[F], asyncSupport: Option[(Effect[F],ExecutionContext)], acc: B, v: FreeC.ViewL[AlgebraF, Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]): F[B] = {
      v.get match {
        case done: FreeC.Pure[AlgebraF, Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] => done.r match {
          case None => F.pure(acc)
          case Some((hd, tl)) =>
            F.suspend {
              try go(scope, asyncSupport, hd.fold(acc)(g).run, uncons(tl).viewL)
              catch { case NonFatal(e) => go(scope, asyncSupport, acc, uncons(tl.asHandler(e)).viewL) }
            }
        }
        case failed: FreeC.Fail[AlgebraF, _] => F.raiseError(failed.error)
        case bound: FreeC.Bind[AlgebraF, _, Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] =>
          val f = bound.f.asInstanceOf[
            Either[Throwable,Any] => FreeC[AlgebraF, Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]]
          val fx = bound.fx.asInstanceOf[FreeC.Eval[AlgebraF,_]].fr
          fx match {
            case wrap: Algebra.Eval[F, O, _] =>
              F.flatMap(F.attempt(wrap.value)) { e => go(scope, asyncSupport, acc, f(e).viewL) }

            case acquire: Algebra.Acquire[F2,_,_] =>
              val resource = acquire.resource
              val release = acquire.release
              if (scope.beginAcquire) {
                F.flatMap(F.attempt(resource)) {
                  case Left(err) =>
                    scope.cancelAcquire
                    go(scope, asyncSupport, acc, f(Left(err)).viewL)
                  case Right(r) =>
                    val token = new Token()
                    lazy val finalizer_ = release(r)
                    val finalizer = F.suspend { finalizer_ }
                    scope.finishAcquire(token, finalizer)
                    go(scope, asyncSupport, acc, f(Right((r, token))).viewL)
                }
              } else {
                F.raiseError(Interrupted)
              }

            case release: Algebra.Release[F2,_] =>
              scope.releaseResource(release.token) match {
                case None => F.suspend { go(scope, asyncSupport, acc, f(Right(())).viewL) }
                case Some(finalizer) => F.flatMap(F.attempt(finalizer)) { e =>
                  go(scope, asyncSupport, acc, f(e).viewL)
                }
              }

            case c: Algebra.CloseScope[F2,_] =>
              F.flatMap(c.toClose.close(asyncSupport)) { e =>
                go(c.scopeAfterClose, asyncSupport, acc, f(e).viewL)
              }

            case o: Algebra.OpenScope[F2,_] =>
              F.suspend {
                val innerScope = scope.open
                go(innerScope, asyncSupport, acc, f(Right(scope -> innerScope)).viewL)
              }

            case unconsAsync: Algebra.UnconsAsync[F2,_,_,_] =>
              val s = unconsAsync.s
              val effect = unconsAsync.effect
              val ec = unconsAsync.ec
              type UO = Option[(Segment[_,Unit], FreeC[Algebra[F,Any,?],Unit])]
              val asyncPull: F[AsyncPull[F,UO]] = F.flatMap(async.ref[F,Either[Throwable,UO]](effect, ec)) { ref =>
                F.map(async.fork {
                  F.flatMap(F.attempt(
                    runFold_(
                      uncons(s.asInstanceOf[FreeC[Algebra[F,Any,?],Unit]]).flatMap(output1(_)),
                      None: UO
                    )((o,a) => a, scope)
                  )) { o => ref.setAsyncPure(o) }
                }(effect, ec))(_ => AsyncPull.readAttemptRef(ref))
              }
              F.flatMap(asyncPull) { ap => go(scope, Some(effect -> ec), acc, f(Right(ap)).viewL) }

            case s: Algebra.Suspend[F2,O,_] =>
              F.suspend {
                try go(scope, asyncSupport, acc, FreeC.Bind(s.thunk(), f).viewL)
                catch { case NonFatal(e) => go(scope, asyncSupport, acc, f(Left(e)).viewL) }
              }

            case _ => sys.error("impossible Segment or Output following uncons")
          }
        case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
      }
    }
    F.suspend { go(scope, None, init, uncons(stream).viewL) }
  }

  def translate[F[_],G[_],O,R](fr: FreeC[Algebra[F,O,?],R], u: F ~> G, G: Option[Effect[G]]): FreeC[Algebra[G,O,?],R] = {
    type F2[x] = F[x] // nb: workaround for scalac kind bug, where in the unconsAsync case, scalac thinks F has kind 0
    def algFtoG[O2]: Algebra[F,O2,?] ~> Algebra[G,O2,?] = new (Algebra[F,O2,?] ~> Algebra[G,O2,?]) { self =>
      def apply[X](in: Algebra[F,O2,X]): Algebra[G,O2,X] = in match {
        case o: Output[F,O2] => Output[G,O2](o.values)
        case WrapSegment(values) => WrapSegment[G,O2,X](values)
        case Eval(value) => Eval[G,O2,X](u(value))
        case a: Acquire[F,O2,_] => Acquire(u(a.resource), r => u(a.release(r)))
        case r: Release[F,O2] => Release[G,O2](r.token)
        case os: OpenScope[F,O2] => os.asInstanceOf[Algebra[G,O2,X]]
        case cs: CloseScope[F,O2] => cs.asInstanceOf[CloseScope[G,O2]]
        case ua: UnconsAsync[F,_,_,_] =>
          val uu: UnconsAsync[F2,Any,Any,Any] = ua.asInstanceOf[UnconsAsync[F2,Any,Any,Any]]
          G match {
            case None => Algebra.Eval(u(uu.effect.raiseError[X](new IllegalStateException("unconsAsync encountered while translating synchronously"))))
            case Some(ef) => UnconsAsync(uu.s.translate[Algebra[G,Any,?]](algFtoG), ef, uu.ec).asInstanceOf[Algebra[G,O2,X]]
          }
        case s: Suspend[F,O2,X] => Suspend(() => s.thunk().translate(algFtoG))
      }
    }
    fr.translate[Algebra[G,O,?]](algFtoG)
  }
}
