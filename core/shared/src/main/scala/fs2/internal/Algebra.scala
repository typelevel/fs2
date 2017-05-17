package fs2.internal

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import cats.~>
import cats.effect.{ Effect, Sync }

import fs2.{ AsyncPull, Segment }
import fs2.concurrent

private[fs2] sealed trait Algebra[F[_],O,R]

private[fs2] object Algebra {

  private val tokenNonce: AtomicLong = new AtomicLong(Long.MinValue)
  class Token {
    val nonce: Long = tokenNonce.incrementAndGet
    override def toString = s"Token(${##}/${nonce})"
  }

  final case class Output[F[_],O](values: Segment[O,Unit]) extends Algebra[F,O,Unit]
  final case class WrapSegment[F[_],O,R](values: Segment[O,R]) extends Algebra[F,O,R]
  final case class Eval[F[_],O,R](value: F[R]) extends Algebra[F,O,R]
  final case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends Algebra[F,O,(R,Token)]
  final case class Release[F[_],O](token: Token) extends Algebra[F,O,Unit]
  final case class Snapshot[F[_],O]() extends Algebra[F,O,LinkedSet[Token]]
  final case class UnconsAsync[F[_],X,Y,O](s: Free[Algebra[F,O,?],Unit], effect: Effect[F], ec: ExecutionContext)
    extends Algebra[F,X,AsyncPull[F,Option[(Segment[O,Unit],Free[Algebra[F,O,?],Unit])]]]
  final case class Interrupt[F[_],O]() extends Algebra[F,O,() => Boolean]

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

  def snapshot[F[_],O]: Free[Algebra[F,O,?],LinkedSet[Token]] =
    Free.Eval[Algebra[F,O,?],LinkedSet[Token]](Snapshot())

  def unconsAsync[F[_],X,Y,O](s: Free[Algebra[F,O,?],Unit])(implicit F: Effect[F], ec: ExecutionContext): Free[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]] =
    Free.Eval[Algebra[F,X,?],AsyncPull[F,Option[(Segment[O,Unit],Free[Algebra[F,O,?],Unit])]]](UnconsAsync(s, F, ec))

  def interrupt[F[_],O]: Free[Algebra[F,O,?],() => Boolean] =
    Free.Eval[Algebra[F,O,?],() => Boolean](Interrupt())

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
    Algebra.suspend[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] {
      Algebra.interrupt[F,X].flatMap { interrupt => uncons_(s, chunkSize, interrupt) }
    }

  private def uncons_[F[_],X,O](s: Free[Algebra[F,O,?],Unit], chunkSize: Int, interrupt: () => Boolean): Free[Algebra[F,X,?],Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] =
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
              val (hd, cnt, tl) = os.values.splitAt(chunkSize)
              val hdAsSegment: Segment[O,Unit] = hd.uncons.flatMap { case (h1,t1) =>
                t1.uncons.flatMap(_ => Segment.catenated(hd)).orElse(Some(h1))
              }.getOrElse(Segment.empty)
              pure[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]](Some(
                hdAsSegment -> { tl match {
                  case Left(r) => f(Right(r))
                  case Right(seg) => Free.Bind[Algebra[F,O,?],x,Unit](segment(seg), f)
                }})
              )
            }
            catch { case e: Throwable => suspend[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] {
              uncons_(f(Left(e)), chunkSize, interrupt)
            }}
          case algebra => // Eval, Acquire, Release, Snapshot, UnconsAsync
            Free.Bind[Algebra[F,X,?],Any,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]](
              Free.Eval[Algebra[F,X,?],Any](algebra.asInstanceOf[Algebra[F,X,Any]]),
              f andThen (s => uncons_[F,X,O](s, chunkSize, interrupt))
            )
        }
      case e => sys.error("Free.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }

  /** Left-fold the output of a stream, supporting `unconsAsync`. */
  def runFold[F2[_],O,B](stream: Free[Algebra[F2,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F2]): F2[B] = {
    val startCompletion = new AtomicBoolean(false)
    F.flatMap(F.attempt(runFold_(stream, init)(f, startCompletion, TwoWayLatch(0), new ConcurrentSkipListMap(new java.util.Comparator[Token] {
      def compare(x: Token, y: Token) = x.nonce compare y.nonce
    })))) { res => F.flatMap(F.delay(startCompletion.set(true))) { _ => res.fold(F.raiseError, F.pure) } }
  }

  /**
   * Left-fold the output of a stream.
   *
   *    `startCompletion` is used to control whether stream completion has begun.
   *    `midAcquires` tracks number of resources that are in the middle of being acquired.
   *    `resources` tracks the current set of resources in use by the stream, keyed by `Token`.
   *
   * When `startCompletion` becomes `true`, if `midAcquires` has 0 count, `F.raiseError(Interrupted)`
   * is returned.
   *
   * If `midAcquires` has nonzero count, we `midAcquires.waitUntil0`, then `F.raiseError(Interrupted)`.
   *
   * Before starting an acquire, we `midAcquires.increment`. We then check `startCompletion`:
   *   If false, proceed with the acquisition, update `resources`, and call `midAcquires.decrement` when done.
   *   If true, `midAcquire.decrement` and F.raiseError(Interrupted) immediately.
   *
   * No new resource acquisitions can begin after `startCompletion` becomes true, and because
   * we are conservative about incrementing the `midAcquires` latch, doing so even before we
   * know we will acquire a resource, we are guaranteed that doing `midAcquires.waitUntil0`
   * will unblock only when `resources` is no longer growing.
   */
  private def runFold_[F2[_],O,B](stream: Free[Algebra[F2,O,?],Unit], init: B)(f: (B, O) => B,
      startCompletion: AtomicBoolean,
      midAcquires: TwoWayLatch,
      resources: ConcurrentSkipListMap[Token, F2[Unit]])(implicit F: Sync[F2]): F2[B] = {
    type AlgebraF[x] = Algebra[F,O,x]
    type F[x] = F2[x] // scala bug, if don't put this here, inner function thinks `F` has kind *
    val interrupt = () => startCompletion.get
    def go(acc: B, v: Free.ViewL[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]): F[B]
    = v.get match {
      case done: Free.Pure[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] => done.r match {
        case None => F.pure(acc)
        case Some((hd, tl)) =>
          F.suspend {
            try go(hd.fold(acc)(f).run, uncons(tl).viewL(interrupt))
            catch { case NonFatal(e) => go(acc, uncons(tl.asHandler(e, interrupt)).viewL(interrupt)) }
          }
      }
      case failed: Free.Fail[AlgebraF, _] => F.raiseError(failed.error)
      case bound: Free.Bind[AlgebraF, _, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] =>
        val f = bound.f.asInstanceOf[
          Either[Throwable,Any] => Free[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]]
        val fx = bound.fx.asInstanceOf[Free.Eval[AlgebraF,_]].fr
        fx match {
          case wrap: Algebra.Eval[F, O, _] =>
            F.flatMap(F.attempt(wrap.value)) { e => go(acc, f(e).viewL(interrupt)) }
          case acquire: Algebra.Acquire[F2,_,_] =>
            val resource = acquire.resource
            val release = acquire.release
            midAcquires.increment
            if (startCompletion.get) { midAcquires.decrement; F.raiseError(Interrupted) }
            else
              F.flatMap(F.attempt(resource)) {
                case Left(err) => go(acc, f(Left(err)).viewL(interrupt))
                case Right(r) =>
                  val token = new Token()
                  lazy val finalizer_ = release(r)
                  val finalizer = F.suspend { finalizer_ }
                  resources.put(token, finalizer)
                  midAcquires.decrement
                  go(acc, f(Right((r, token))).viewL(interrupt))
              }
          case release: Algebra.Release[F2,_] =>
            val token = release.token
            val finalizer = resources.remove(token)
            if (finalizer.asInstanceOf[AnyRef] eq null) go(acc, f(Right(())).viewL(interrupt))
            else F.flatMap(F.attempt(finalizer)) { e => go(acc, f(e).viewL(interrupt)) }
          case snapshot: Algebra.Snapshot[F2,_] =>
            // todo - think through whether we need to take a consistent snapshot of resources
            // if so we need a monotonically increasing nonce associated with each resource
            val tokens = resources.keySet.iterator.asScala.foldLeft(LinkedSet.empty[Token])(_ + _)
            go(acc, f(Right(tokens)).viewL(interrupt))
          case _: Algebra.Interrupt[F2,_] =>
            go(acc, f(Right(interrupt)).viewL(interrupt))
          case unconsAsync: Algebra.UnconsAsync[F2,_,_,_] =>
            val s = unconsAsync.s
            val effect = unconsAsync.effect
            val ec = unconsAsync.ec
            type UO = Option[(Segment[_,Unit], Free[Algebra[F,Any,?],Unit])]
            val asyncPull: F[AsyncPull[F,UO]] = F.flatMap(concurrent.ref[F,Either[Throwable,UO]](effect, ec)) { ref =>
              F.map(concurrent.fork {
                F.flatMap(F.attempt(runFold_(
                  uncons(s.asInstanceOf[Free[Algebra[F,Any,?],Unit]]).flatMap(output1(_)),
                  None: UO)((o,a) => a, startCompletion, midAcquires, resources)
                ))(o => ref.setAsyncPure(o))
              }(effect, ec))(_ => AsyncPull.readAttemptRef(ref))
            }
            F.flatMap(asyncPull) { ap => go(acc, f(Right(ap)).viewL(interrupt)) }
          case _ => sys.error("impossible Segment or Output following uncons")
        }
      case e => sys.error("Free.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
    F.suspend { go(init, uncons(stream).viewL(interrupt)) }
  }

  def translate[F[_],G[_],O,R](fr: Free[Algebra[F,O,?],R], u: F ~> G, G: Option[Effect[G]]): Free[Algebra[G,O,?],R] = {
    type F2[x] = F[x] // nb: workaround for scalac kind bug, where in the unconsAsync case, scalac thinks F has kind 0
    def algFtoG[O]: Algebra[F,O,?] ~> Algebra[G,O,?] = new (Algebra[F,O,?] ~> Algebra[G,O,?]) { self =>
      def apply[X](in: Algebra[F,O,X]): Algebra[G,O,X] = in match {
        case o: Output[F,O] => Output[G,O](o.values)
        case WrapSegment(values) => WrapSegment[G,O,X](values)
        case Eval(value) => Eval[G,O,X](u(value))
        case a: Acquire[F,O,_] => Acquire(u(a.resource), r => u(a.release(r)))
        case r: Release[F,O] => Release[G,O](r.token)
        case s: Snapshot[F,O] => Snapshot[G,O]()
        case i: Interrupt[F,O] => Interrupt[G,O]()
        case ua: UnconsAsync[F,_,_,_] =>
          val uu: UnconsAsync[F2,Any,Any,Any] = ua.asInstanceOf[UnconsAsync[F2,Any,Any,Any]]
          G match {
            case None => Algebra.Eval(u(uu.effect.raiseError[X](new IllegalStateException("unconsAsync encountered while translating synchronously"))))
            case Some(ef) => UnconsAsync(uu.s.translate[Algebra[G,Any,?]](algFtoG), ef, uu.ec).asInstanceOf[Algebra[G,O,X]]
          }
      }
    }
    fr.translate[Algebra[G,O,?]](algFtoG)
  }
}
