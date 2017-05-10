package fs2
package fast
package internal

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import cats.effect.{ Effect, Sync }

import fs2.internal.{ LinkedSet, TwoWayLatch }

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

  def pure[F[_],O,R](r: R): Free[Algebra[F,O,?],R] =
    Free.Pure[Algebra[F,O,?],R](r)

  def fail[F[_],O,R](t: Throwable): Free[Algebra[F,O,?],R] =
    Free.Fail[Algebra[F,O,?],R](t)

  def suspend[F[_],O,R](f: => Free[Algebra[F,O,?],R]): Free[Algebra[F,O,?],R] =
    pure(()) flatMap { _ => f } // todo - revist

  def rethrow[F[_],O,R](f: Free[Algebra[F,O,?],Either[Throwable,R]]): Free[Algebra[F,O,?],R] = f flatMap {
    case Left(err) => fail(err)
    case Right(r) => pure(r)
  }

  def uncons[F[_],X,O](s: Free[Algebra[F,O,?],Unit], chunkSize: Int = 1024): Free[Algebra[F,X,?],Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] = {
    type AlgebraF[x] = Algebra[F,O,x]
    def assumeNoOutput[Y,R](p: Free[Algebra[F,Y,?],R]): Free[Algebra[F,X,?],R] = p.asInstanceOf[Free[Algebra[F,X,?],R]]
    import Free.ViewL

    s.viewL match {
      case done: ViewL.Done[AlgebraF, Unit] => pure(None)
      case failed: ViewL.Failed[AlgebraF, _] => fail(failed.error)
      case bound: ViewL.Bound[AlgebraF, _, Unit] =>
        val f = bound.f.asInstanceOf[Unit => Free[AlgebraF, Unit]]
        bound.fx match {
          case os : Algebra.Output[F, O] =>
            pure[F,X,Option[(Segment[O,Unit], Free[AlgebraF,Unit])]](Some((os.values, f(()))))
          case os : Algebra.WrapSegment[F, O, y] =>
            try {
              val (hd, tl) = os.values.splitAt(chunkSize)
              pure[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]](Some(
                hd -> tl.fold(r => bound.f(r), segment(_).flatMap(bound.f))
              ))
            }
            catch { case e: Throwable => suspend[F,X,Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] {
              uncons(bound.handleError(e))
            }}
          case algebra => // Wrap, Acquire, Release, Snapshot, UnconsAsync
            bound.onError match {
              case None =>
                assumeNoOutput(Free.Eval(algebra))
                  .flatMap(x => uncons(bound.f(x)))
              case Some(onError) =>
                assumeNoOutput(Free.Eval(algebra))
                  .flatMap(x => uncons(bound.f(x)))
                  .onError(e => uncons(bound.handleError(e)))
            }
        }
    }
  }

  case object Interrupted extends Throwable { override def fillInStackTrace = this }

  /** Left-fold the output of a stream, supporting `unconsAsync`. */
  def runFold[F2[_],O,B](stream: Free[Algebra[F2,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F2]): F2[B] = {
    runFold_(stream, init)(f, new AtomicBoolean(false), TwoWayLatch(0), new ConcurrentSkipListMap(new java.util.Comparator[Token] {
      def compare(x: Token, y: Token) = x.nonce compare y.nonce
    }))
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
    def go(acc: B, v: Free.ViewL[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]): F[B] = v match {
      case done: Free.ViewL.Done[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] => done.r match {
        case None => F.pure(acc)
        case Some((hd, tl)) => go(hd.fold(acc)(f).run, uncons(tl).viewL)
      }
      case failed: Free.ViewL.Failed[AlgebraF, _] => F.raiseError(failed.error)
      case bound: Free.ViewL.Bound[AlgebraF, _, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]] =>
        val g = bound.tryBind.asInstanceOf[Any => Free[AlgebraF, Option[(Segment[O,Unit], Free[Algebra[F,O,?],Unit])]]]
        bound.fx match {
          case wrap: Algebra.Eval[F, O, _] =>
            F.flatMap(F.attempt(wrap.value.asInstanceOf[F[Any]])) {
              case Right(x) => go(acc, g(x).viewL)
              case Left(e) => bound.onError match {
                case None => go(acc, fail(e).viewL)
                case Some(onErr) => go(acc, onErr(e).viewL)
              }
            }
          case Algebra.Acquire(resource, release) =>
            midAcquires.increment
            if (startCompletion.get) { midAcquires.decrement; F.raiseError(Interrupted) }
            else
              F.flatMap(F.attempt(resource)) {
                case Left(err) => go(acc, bound.handleError(err).viewL)
                case Right(r) =>
                  val token = new Token()
                  lazy val finalizer_ = release(r)
                  val finalizer = F.suspend { finalizer_ }
                  resources.put(token, finalizer)
                  midAcquires.decrement
                  go(acc, g((r, token)).viewL)
              }
          case Algebra.Release(token) =>
            val finalizer = resources.remove(token)
            if (finalizer.asInstanceOf[AnyRef] eq null) go(acc, g(()).viewL)
            else F.flatMap(F.attempt(finalizer)) {
              case Left(err) => go(acc, bound.handleError(err).viewL)
              case Right(_) => go(acc, g(()).viewL)
            }
          case Algebra.Snapshot() =>
            // todo - think through whether we need to take a consistent snapshot of resources
            // if so we need a monotonically increasing nonce associated with each resource
            val tokens = resources.keySet.iterator.asScala.foldLeft(LinkedSet.empty[Token])(_ + _)
            go(acc, g(tokens).viewL)
          case Algebra.UnconsAsync(s, effect, ec) =>
            type UO = Option[(Segment[_,Unit], Free[Algebra[F,Any,?],Unit])]
            val asyncPull: F[AsyncPull[F,UO]] = F.flatMap(concurrent.ref[F,Either[Throwable,UO]](effect, ec)) { ref =>
              F.map(concurrent.fork {
                F.flatMap(F.attempt(runFold_(
                  uncons(s.asInstanceOf[Free[Algebra[F,Any,?],Unit]]).flatMap(output1(_)),
                  None: UO)((o,a) => a, startCompletion, midAcquires, resources)
                ))(o => ref.setAsyncPure(o))
              }(effect, ec))(_ => AsyncPull.readAttemptRef(ref))
            }
            F.flatMap(asyncPull) { ap => go(acc, g(ap).viewL) }
          case _ => sys.error("impossible Segment or Output following uncons")
        }
    }
    F.suspend { go(init, uncons(stream).viewL) }
  }
}
