package fs2.internal

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic._
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import cats.~>
import cats.effect.{ Effect, Sync }

import fs2.{ AsyncPull, Catenable, Segment }
import fs2.async

private[fs2] sealed trait Algebra[F[_],O,R]

private[fs2] object Algebra {

  private val tokenNonce: AtomicLong = new AtomicLong(Long.MinValue)
  class Token extends java.lang.Comparable[Token] {
    val nonce: Long = tokenNonce.incrementAndGet
    def compareTo(s2: Token) = nonce compareTo s2.nonce
    // override def toString = s"Token(${##}/${nonce})"
    override def toString = s"#${nonce + Long.MaxValue}"
  }

  case class Scope(tokens: Vector[Token]) extends java.lang.Comparable[Scope] {
    def compareTo(s2: Scope) = math.Ordering.Iterable[Token].compare(tokens,s2.tokens)
    def :+(t: Token): Scope = Scope(tokens :+ t)
    def isParentOf(s2: Scope): Boolean = s2.tokens.startsWith(tokens)
    override def toString = tokens.mkString("Scope(", ", ", ")")
  }
  object Scope { val bottom = Scope(Vector()) }

  final case class Output[F[_],O](values: Segment[O,Unit]) extends Algebra[F,O,Unit]
  final case class WrapSegment[F[_],O,R](values: Segment[O,R]) extends Algebra[F,O,R]
  final case class Eval[F[_],O,R](value: F[R]) extends Algebra[F,O,R]
  final case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends Algebra[F,O,(R,Token)]
  final case class Release[F[_],O](token: Token) extends Algebra[F,O,Unit]
  final case class UnconsAsync[F[_],X,Y,O](s: Free[Algebra[F,O,?],Unit], effect: Effect[F], ec: ExecutionContext)
    extends Algebra[F,X,AsyncPull[F,Option[(Segment[O,Unit],Free[Algebra[F,O,?],Unit])]]]
  final case class Interrupt[F[_],O]() extends Algebra[F,O,() => Boolean]
  // note - OpenScope returns (new-scope, current-scope), which should be passed to `CloseScope`
  final case class OpenScope[F[_],O]() extends Algebra[F,O,(Scope,Scope)]
  final case class CloseScope[F[_],O](toClose: Scope, scopeAfterClose: Scope) extends Algebra[F,O,Unit]

  def output[F[_],O](values: Segment[O,Unit]): Free[Algebra[F,O,?],Unit] =
    Free.Eval[Algebra[F,O,?],Unit](Output(values))

  def output1[F[_],O](value: O): Free[Algebra[F,O,?],Unit] =
    output(Segment.singleton(value))

  def segment[F[_],O,R](values: Segment[O,R]): Free[Algebra[F,O,?],R] =
    Free.Eval[Algebra[F,O,?],R](WrapSegment(values))

  def eval[F[_],O,R](value: F[R]): Free[Algebra[F,O,?],R] =
    Free.Eval[Algebra[F,O,?],R](Eval(value))

  def acquire[F[_],O,R](resource: F[R], release: R => F[Unit]): Free[Algebra[F,O,?],(R,Token)] =
    Free.Eval[Algebra[F,O,?],(R,Token)](Acquire(resource, release))

  def release[F[_],O](token: Token): Free[Algebra[F,O,?],Unit] =
    Free.Eval[Algebra[F,O,?],Unit](Release(token))

  def unconsAsync[F[_],X,Y,O](s: Free[Algebra[F,O,?],Unit])(implicit F: Effect[F], ec: ExecutionContext): Free[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]] =
    Free.Eval[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit],Free[Algebra[F,O,?],Unit])]]](UnconsAsync(s, F, ec))

  def interrupt[F[_],O]: Free[Algebra[F,O,?],() => Boolean] =
    Free.Eval[Algebra[F,O,?],() => Boolean](Interrupt())

  def openScope[F[_],O]: Free[Algebra[F,O,?],(Scope,Scope)] =
    Free.Eval[Algebra[F,O,?],(Scope,Scope)](OpenScope())

  def closeScope[F[_],O](toClose: Scope, scopeAfterClose: Scope): Free[Algebra[F,O,?],Unit] =
    Free.Eval[Algebra[F,O,?],Unit](CloseScope(toClose, scopeAfterClose))

  def pure[F[_],O,R](r: R): Free[Algebra[F,O,?],R] =
    Free.Pure[Algebra[F,O,?],R](r)

  def fail[F[_],O,R](t: Throwable): Free[Algebra[F,O,?],R] =
    Free.Fail[Algebra[F,O,?],R](t)

  def suspend[F[_],O,R](f: => Free[Algebra[F,O,?],R]): Free[Algebra[F,O,?],R] =
    Free.suspend[Algebra[F,O,?],R](f)

  def rethrow[F[_],O,R](f: Free[Algebra[F,O,?],Either[Throwable,R]]): Free[Algebra[F,O,?],R] = f flatMap {
    case Left(err) => fail(err)
    case Right(r) => pure(r)
  }

  def uncons[F[_],X,O](s: Free[Algebra[F,O,?],Unit], chunkSize: Int = 1024): Free[Algebra[F,X,?],Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] =
    Algebra.interrupt[F,X].flatMap { interrupt => uncons_(s, chunkSize, interrupt) }

  private def uncons_[F[_],X,O](s: Free[Algebra[F,O,?],Unit], chunkSize: Int, interrupt: () => Boolean): Free[Algebra[F,X,?],Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] = {
    s.viewL(interrupt).get match {
      case done: Free.Pure[Algebra[F,O,?], Unit] => pure(None)
      case failed: Free.Fail[Algebra[F,O,?], _] => fail(failed.error)
      case bound: Free.Bind[Algebra[F,O,?],_,Unit] =>
        val f = bound.f.asInstanceOf[Either[Throwable,Any] => Free[Algebra[F,O,?],Unit]]
        val fx = bound.fx.asInstanceOf[Free.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case os : Algebra.Output[F, O] =>
            pure[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]](Some((os.values, f(Right(())))))
          case os : Algebra.WrapSegment[F, O, x] =>
            try {
              def asSegment(c: Catenable[Segment[O,Unit]]): Segment[O,Unit] =
                c.uncons.flatMap { case (h1,t1) => t1.uncons.map(_ => Segment.catenated(c)).orElse(Some(h1)) }.getOrElse(Segment.empty)
              os.values.splitAt(chunkSize) match {
                case Left((r,segments,rem)) =>
                  pure[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]](Some(asSegment(segments) -> f(Right(r))))
                case Right((segments,tl)) =>
                  pure[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]](Some(asSegment(segments) -> Free.Bind[Algebra[F,O,?],x,Unit](segment(tl), f)))
              }
            } catch { case e: Throwable => suspend[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] {
              uncons_(f(Left(e)), chunkSize, interrupt)
            }}
          case algebra => // Eval, Acquire, Release, OpenScope, CloseScope, UnconsAsync
            Free.Bind[Algebra[F,X,?],Any,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]](
              Free.Eval[Algebra[F,X,?],Any](algebra.asInstanceOf[Algebra[F,X,Any]]),
              f andThen (s => uncons_[F,X,O](s, chunkSize, interrupt))
            )
        }
      case e => sys.error("Free.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  /** Left-fold the output of a stream, supporting `unconsAsync`. */
  def runFold[F2[_],O,B](stream: Free[Algebra[F2,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F2]): F2[B] =
    runFoldInterruptibly(stream, () => false, init)(f)

  /** Left-fold the output of a stream, supporting `unconsAsync`. */
  def runFoldInterruptibly[F2[_],O,B](stream: Free[Algebra[F2,O,?],Unit], interrupt: () => Boolean, init: B)(f: (B, O) => B)(implicit F: Sync[F2]): F2[B] =
    F.flatMap(F.delay {
      val b = new AtomicBoolean(false)
      (TwoWayLatch(0), () => interrupt() || b.get, () => b.set(true), new CMap[Token,F2[Unit]]())
    }) { tup =>
      val scopes = new ScopeMap[F2]()
      scopes.put(Scope(Vector()), tup)
      runFold_(stream, init)(f, Scope(Vector()), scopes)
    }

  private type ScopeMap[F[_]] = CMap[Scope, (TwoWayLatch, () => Boolean, () => Unit, CMap[Token, F[Unit]])]

  private type CMap[K,V] = ConcurrentSkipListMap[K,V]

  def scope[F[_],O,R](pull: Free[Algebra[F,O,?],R]): Free[Algebra[F,O,?],R] =
    openScope flatMap { case (toClose,scopeAfterClose) =>
      Free.Bind(pull, (e: Either[Throwable,R]) => e match {
        case Left(e) => closeScope(toClose, scopeAfterClose) flatMap { _ => fail(e) }
        case Right(r) => closeScope(toClose, scopeAfterClose) map { _ => r }
      })
    }

  /**
   * Left-fold the output of a stream. For each scope, we have 3 key pieces of state:
   *
   *    `interrupt` is used to control whether scope completion has begun.
   *    `midAcquires` tracks number of resources that are in the middle of being acquired.
   *    `resources` tracks the current set of resources in use by the scope, keyed by `Token`.
   *
   * When `interrupt` becomes `true`, if `midAcquires` has 0 count, `F.raiseError(Interrupted)`
   * is returned.
   *
   * If `midAcquires` has nonzero count, we `midAcquires.waitUntil0`, then `F.raiseError(Interrupted)`.
   *
   * Before starting an acquire, we `midAcquires.increment`. We then check `interrupt`:
   *   If false, proceed with the acquisition, update `resources`, and call `midAcquires.decrement` when done.
   *   If true, `midAcquire.decrement` and F.raiseError(Interrupted) immediately.
   *
   * No new resource acquisitions can begin after `interrupt` becomes true, and because
   * we are conservative about incrementing the `midAcquires` latch, doing so even before we
   * know we will acquire a resource, we are guaranteed that doing `midAcquires.waitUntil0`
   * will unblock only when `resources` is no longer growing.
   */
  private def runFold_[F2[_],O,B](stream: Free[Algebra[F2,O,?],Unit], init: B)(
      g: (B, O) => B, root: Scope, scopes: ScopeMap[F2])(implicit F: Sync[F2]): F2[B] = {
    type AlgebraF[x] = Algebra[F,O,x]
    type F[x] = F2[x] // scala bug, if don't put this here, inner function thinks `F` has kind *
    def go(root: Scope, acc: B, v: Free.ViewL[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]): F[B] = {
      val interrupt: () => Boolean = scopes.get(root)._2
      val midAcquires: TwoWayLatch = scopes.get(root)._1
      val resources: CMap[Token,F2[Unit]] = scopes.get(root)._4
      def removeToken(t: Token): Option[F2[Unit]] = {
        var f = resources.remove(t)
        if (!(f.asInstanceOf[AnyRef] eq null)) Some(f)
        else {
          val i = scopes.values.iterator; while (i.hasNext) {
            f = i.next._4.remove(t)
            if (!(f.asInstanceOf[AnyRef] eq null)) return Some(f)
          }
          None
        }
      }
      v.get match {
        case done: Free.Pure[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] => done.r match {
          case None => F.pure(acc)
          case Some((hd, tl)) =>
            F.suspend {
              try go(root, hd.fold(acc)(g).run, uncons(tl).viewL(interrupt))
              catch { case NonFatal(e) => go(root, acc, uncons(tl.asHandler(e, interrupt)).viewL(interrupt)) }
            }
        }
        case failed: Free.Fail[AlgebraF, _] => F.raiseError(failed.error)
        case bound: Free.Bind[AlgebraF, _, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] =>
          val f = bound.f.asInstanceOf[
            Either[Throwable,Any] => Free[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]]
          val fx = bound.fx.asInstanceOf[Free.Eval[AlgebraF,_]].fr
          fx match {
            case wrap: Algebra.Eval[F, O, _] =>
              F.flatMap(F.attempt(wrap.value)) { e => go(root, acc, f(e).viewL(interrupt)) }
            case acquire: Algebra.Acquire[F2,_,_] =>
              val resource = acquire.resource
              val release = acquire.release
              midAcquires.increment
              if (interrupt()) { midAcquires.decrement; F.raiseError(Interrupted) }
              else
                F.flatMap(F.attempt(resource)) {
                  case Left(err) => go(root, acc, f(Left(err)).viewL(interrupt))
                  case Right(r) =>
                    val token = new Token()
                    // println("Acquired resource: " + token)
                    lazy val finalizer_ = release(r)
                    val finalizer = F.suspend { finalizer_ }
                    resources.put(token, finalizer)
                    midAcquires.decrement
                    go(root, acc, f(Right((r, token))).viewL(interrupt))
                }
            case release: Algebra.Release[F2,_] =>
            // println("Releasing " + release.token)
              removeToken(release.token) match {
              case None => go(root, acc, f(Right(())).viewL(interrupt))
              case Some(finalizer) => F.flatMap(F.attempt(finalizer)) { e =>
                go(root, acc, f(e).viewL(interrupt))
              }
            }
            case c: Algebra.CloseScope[F2,_] =>
              def closeScope(root: Scope): F2[(() => Unit, Either[Throwable,Unit])] = {
                val tup = scopes.remove(root)
                if (tup eq null) F.pure((() => (), Right(())))
                else tup match { case (midAcquires,_,setInterrupt,acquired) =>
                  midAcquires.waitUntil0
                  F.map(releaseAll(Right(()), acquired.asScala.values.map(F.attempt).toList.reverse)) { e =>
                    setInterrupt -> e
                  }
                }
              }
              // p.scope.scope
              // println("Closing scope: " + c.toClose)
              if (scopes.get(c.scopeAfterClose) eq null) println("!!!!! " + c.scopeAfterClose)
              //val toClose = scopes.asScala.keys.filter(c.toClose isParentOf _).toList
              // println("  live scopes " + scopes.asScala.keys.toList.mkString(" "))
              // println("  scopes being closed " + toClose.mkString(" "))
              // println("  c.scopeAfterClose " + c.scopeAfterClose)
              // println toClose, we are closing scopes too early
              F.flatMap(closeScope(c.toClose)) { case (interrupt, e) =>
                go(c.scopeAfterClose, acc, f(e).map { x => interrupt(); x }.viewL(scopes.get(c.scopeAfterClose)._2))
              }
              // F.flatMap(releaseAll(Right(()), toClose map closeScope)) { e =>
              //   go(c.scopeAfterClose, acc, f(e).viewL(scopes.get(c.scopeAfterClose)._2))
              // }
            case o: Algebra.OpenScope[F2,_] =>
              val innerScope = root :+ new Token()
              // println(s"Opening scope: ${innerScope}")
              val b = new AtomicBoolean(false)
              val innerInterrupt = () => interrupt() || b.get // incorporate parent interrupt
              val tup = (TwoWayLatch(0), innerInterrupt, () => b.set(true), new ConcurrentSkipListMap[Token,F2[Unit]]())
              scopes.put(innerScope, tup)
              go(innerScope, acc, f(Right(innerScope -> root)).viewL(interrupt))
            case _: Algebra.Interrupt[F2,_] =>
              go(root, acc, f(Right(interrupt)).viewL(interrupt))
            case unconsAsync: Algebra.UnconsAsync[F2,_,_,_] =>
              val s = unconsAsync.s
              val effect = unconsAsync.effect
              val ec = unconsAsync.ec
              type UO = Option[(Segment[_,Unit], Free[Algebra[F,Any,?],Unit])]
              val asyncPull: F[AsyncPull[F,UO]] = F.flatMap(async.ref[F,Either[Throwable,UO]](effect, ec)) { ref =>
                F.map(async.fork {
                  F.flatMap(F.attempt(
                    runFold_(
                      uncons_(s.asInstanceOf[Free[Algebra[F,Any,?],Unit]], 1024, interrupt).flatMap(output1(_)),
                      None: UO
                    )((o,a) => a, root, scopes)
                  )) { o => ref.setAsyncPure(o) }
                }(effect, ec))(_ => AsyncPull.readAttemptRef(ref))
              }
              F.flatMap(asyncPull) { ap => go(root, acc, f(Right(ap)).viewL(interrupt)) }
            case _ => sys.error("impossible Segment or Output following uncons")
          }
        case e => sys.error("Free.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
      }
    }
    F.suspend { go(root, init, uncons(stream).viewL(scopes.get(root)._2)) }
  }

  def translate[F[_],G[_],O,R](fr: Free[Algebra[F,O,?],R], u: F ~> G, G: Option[Effect[G]]): Free[Algebra[G,O,?],R] = {
    type F2[x] = F[x] // nb: workaround for scalac kind bug, where in the unconsAsync case, scalac thinks F has kind 0
    def algFtoG[O2]: Algebra[F,O2,?] ~> Algebra[G,O2,?] = new (Algebra[F,O2,?] ~> Algebra[G,O2,?]) { self =>
      def apply[X](in: Algebra[F,O2,X]): Algebra[G,O2,X] = in match {
        case o: Output[F,O2] => Output[G,O2](o.values)
        case WrapSegment(values) => WrapSegment[G,O2,X](values)
        case Eval(value) => Eval[G,O2,X](u(value))
        case a: Acquire[F,O2,_] => Acquire(u(a.resource), r => u(a.release(r)))
        case r: Release[F,O2] => Release[G,O2](r.token)
        case i: Interrupt[F,O2] => Interrupt[G,O2]()
        case os: OpenScope[F,O2] => os.asInstanceOf[OpenScope[G,O2]]
        case cs: CloseScope[F,O2] => cs.asInstanceOf[CloseScope[G,O2]]
        case ua: UnconsAsync[F,_,_,_] =>
          val uu: UnconsAsync[F2,Any,Any,Any] = ua.asInstanceOf[UnconsAsync[F2,Any,Any,Any]]
          G match {
            case None => Algebra.Eval(u(uu.effect.raiseError[X](new IllegalStateException("unconsAsync encountered while translating synchronously"))))
            case Some(ef) => UnconsAsync(uu.s.translate[Algebra[G,Any,?]](algFtoG), ef, uu.ec).asInstanceOf[Algebra[G,O2,X]]
          }
      }
    }
    fr.translate[Algebra[G,O,?]](algFtoG)
  }

  private def releaseAll[F[_]](sofar: Either[Throwable,Unit], finalizers: List[F[Either[Throwable,Unit]]])(
    implicit F: cats.Monad[F])
    : F[Either[Throwable,Unit]] = finalizers match {
      case Nil => F.pure(sofar)
      case h :: t => F.flatMap(h) {
        case Left(e) => sofar.fold(_ => releaseAll(sofar, t), _ => releaseAll(Left(e), t))
        case _ => releaseAll(sofar, t)
      }
    }

}
