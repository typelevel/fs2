package fs2.fast
package core

import Stream.Algebra
import Stream.Stream
import fs2.util.{Free => _, _}
import fs2.internal.TwoWayLatch
import Free.ViewL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters

// TODO
// X add resource allocation algebra
// X add exception handling algebra
// add variance
//
// add fancy pure streams type
// performance testing
// integrate with rest of fs2

final class Pull[F[_], O, R](val algebra: Pull.PullF[F,O,R]) extends AnyVal {
  def flatMap[R2](f: R => Pull[F, O, R2]): Pull[F, O, R2] =
    Pull(algebra.flatMap { r => f(r).algebra })
  def >>[R2](after: => Pull[F, O, R2]): Pull[F, O, R2] = flatMap(_ => after)
  def onError(h: Throwable => Pull[F,O,R]): Pull[F,O,R] =
    new Pull(algebra onError (e => h(e).algebra))
  def covary[G[_]](implicit S: Sub1[F,G]): Pull[G,O,R] = this.asInstanceOf[Pull[G,O,R]]
  def covaryOutput[O2](implicit T: RealSupertype[O,O2]): Pull[F,O2,R] = this.asInstanceOf[Pull[F,O2,R]]
  def covaryResult[R2](implicit T: RealSupertype[R,R2]): Pull[F,O,R2] = this.asInstanceOf[Pull[F,O,R2]]
}

object Pull {
  type PullF[F[_],O,+R] = Free[({type f[x]=Algebra[F,O,x]})#f, R]

  def apply[F[_],O,R](value: Free[({type f[x] = Stream.Algebra[F,O,x]})#f, R]): Pull[F, O, R] =
    new Pull[F, O, R](value)

  def fail[F[_],O,R](err: Throwable): Pull[F,O,R] =
    apply[F,O,R](Free.Fail[({type f[x] = Stream.Algebra[F,O,x]})#f,R](err))

  def rethrow[F[_],O,R](p: Pull[F,O,Either[Throwable,R]]): Pull[F,O,R] = p flatMap {
    case Left(err) => fail(err)
    case Right(r) => pure(r)
  }

  def eval[F[_],O,R](fr: F[R]): Pull[F,O,R] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull(Free.Eval[AlgebraF,R](Algebra.Wrap(fr)))
  }

  def output[F[_],O](os: Catenable[O]): Pull[F,O,Unit] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull(Free.Eval[AlgebraF,Unit](Algebra.Output(os)))
  }

  def output1[F[_],O](o: O): Pull[F,O,Unit] =
    output(Catenable.single(o))

  def outputs[F[_],O](s: Stream[F, O]): Pull[F,O,Unit] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull(Free.Eval[AlgebraF, Unit](Algebra.Outputs(s)))
  }

  def pure[F[_],O,R](r: R): Pull[F,O,R] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull[F, O, R](Free.Pure[AlgebraF, R](r))
  }
}

object Stream {
  type Stream[F[_],O] = Pull[F,O,Unit]
  type StreamF[F[_],O] = Pull.PullF[F,O,Unit]

  def eval[F[_],O](fo: F[O]): Stream[F,O] = Pull.eval(fo).flatMap(Pull.output1)
  def emit[F[_],O](o: O): Stream[F,O] = Pull.output1(o)

  private[fs2] val empty_ = Pull.pure[Nothing,Nothing,Unit](()): Stream[Nothing,Nothing]

  def empty[F[_],O]: Stream[F,O] = empty_.asInstanceOf[Stream[F,O]]
  def fail[F[_],O](e: Throwable): Stream[F,O] = Pull.fail(e)

  sealed class Token
  sealed trait Algebra[F[_],O,R]

  object Algebra {
    case class Output[F[_],O,R](values: Catenable[O]) extends Algebra[F,O,R]
    case class Outputs[F[_],O,R](stream: Stream[F, O]) extends Algebra[F,O,R]
    case class Wrap[F[_],O,R](value: F[R]) extends Algebra[F,O,R]
    case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends Algebra[F,O,(R,Token)]
    case class Release[F[_],O](token: Token) extends Algebra[F,O,Unit]
    case class Snapshot[F[_],O]() extends Algebra[F,O,Set[Token]]
    case class UnconsAsync[F[_],X,Y,O](s: Stream[F,O])
      extends Algebra[F,X,Pull[F,Y,Option[(Catenable[O], Stream[F,O])]]]
  }

  case object Interrupted extends Throwable { override def fillInStackTrace = this }

  def flatMap[F[_],O,O2](s: Stream[F,O])(f: O => Stream[F,O2]): Stream[F,O2] =
    uncons(s) flatMap {
      case None => Stream.empty
      case Some((hd, tl)) =>
        Pull.outputs(hd.map(f).toList.foldRight(Stream.empty[F,O2])(append(_,_))) >>
        flatMap(tl)(f)
    }

  def append[F[_],O](s1: Stream[F,O], s2: => Stream[F,O]): Stream[F,O] =
    s1 >> s2

  def uncons[F[_],X,O](s: Stream[F,O]): Pull[F, X, Option[(Catenable[O], Stream[F, O])]] = {
    type AlgebraF[x] = Algebra[F,O,x]
    def assumeNoOutput[Y,R](p: Pull[F,Y,R]): Pull[F,X,R] = p.asInstanceOf[Pull[F,X,R]]

    s.algebra.viewL match {
      case done: ViewL.Done[AlgebraF, Unit] => Pull.pure(None)
      case failed: ViewL.Failed[AlgebraF, _] => Pull.fail(failed.error)
      case bound: ViewL.Bound[AlgebraF, _, Unit] =>
        val f = bound.f.asInstanceOf[Unit => Free[AlgebraF, Unit]]
        bound.fx match {
          case Algebra.Output(os) => Pull.pure(Some((os, Pull(f(())))))
          case Algebra.Outputs(s) =>
            uncons(s) flatMap {
              case None => Stream.uncons(Pull(f(())))
              case Some((hd, tl)) =>
                Pull.pure(Some((hd, tl >> Pull(f(())))))
            }
          case algebra => // Wrap, Acquire, Release, Snapshot, UnconsAsync
            bound.onError match {
              case None =>
                assumeNoOutput(Pull(Free.Eval(algebra)))
                  .flatMap(x => uncons(Pull(bound.f(x))))
              case Some(onError) =>
                assumeNoOutput(Pull(Free.Eval(algebra)))
                  .flatMap(x => uncons(Pull(bound.f(x))))
                  .onError(e => uncons(Pull(bound.handleError(e))))
            }
        }
    }
  }

  /**
   * Left-fold the output of a stream.
   *
   *    `startCompletion` is used to control whether stream completion has begun.
   *    `midAcquires` tracks number of resources that are in the middle of being acquired.
   *    `resources` tracks the current set of resources in use by the stream, keyed by `Token`.
   *
   * When `startCompletion` becomes `true`, if `midAcquires` has 0 count, `F.fail(Interrupted)`
   * is returned.
   *
   * If `midAcquires` has nonzero count, we `midAcquires.waitUntil0`, then `F.fail(Interrupted)`.
   *
   * Before starting an acquire, we `midAcquires.increment`. We then check `startCompletion`:
   *   If false, proceed with the acquisition, update `resources`, and call `midAcquires.decrement` when done.
   *   If true, `midAcquire.decrement` and F.fail(Interrupted) immediately.
   *
   * No new resource acquisitions can begin after `startCompletion` becomes true, and because
   * we are conservative about incrementing the `midAcquires` latch, doing so even before we
   * know we will acquire a resource, we are guaranteed that doing `midAcquires.waitUntil0`
   * will unblock only when `resources` is no longer growing.
   */
  def runFold[F2[_],O,B](stream: Stream[F2,O], init: B)(f: (B, O) => B,
      startCompletion: AtomicBoolean,
      midAcquires: TwoWayLatch,
      resources: ConcurrentHashMap[Stream.Token, F2[Unit]])(implicit A: Async[F2]): F2[B] = {
    type AlgebraF[x] = Algebra[F,O,x]
    type F[x] = F2[x] // scala bug, if don't put this here, inner function thinks `F` has kind *
    def go(acc: B, v: ViewL[AlgebraF, Option[(Catenable[O], Stream[F, O])]]): F[B] = v match {
      case done: ViewL.Done[AlgebraF, Option[(Catenable[O], Stream[F, O])]] => done.r match {
        case None => A.pure(acc)
        case Some((hd, tl)) => go(hd.toList.foldLeft(acc)(f), uncons(tl).algebra.viewL)
      }
      case failed: ViewL.Failed[AlgebraF, _] => A.fail(failed.error)
      case bound: ViewL.Bound[AlgebraF, _, Option[(Catenable[O], Stream[F, O])]] =>
        val g = bound.tryBind.asInstanceOf[Any => Free[AlgebraF, Option[(Catenable[O], Stream[F, O])]]]
        bound.fx match {
          case wrap: Algebra.Wrap[F, O, _] =>
            A.flatMap(wrap.value.asInstanceOf[F[Any]]) { x => go(acc, g(x).viewL) }
          case Algebra.Acquire(resource, release) =>
            midAcquires.increment
            if (startCompletion.get) { midAcquires.decrement; A.fail(Stream.Interrupted) }
            else
              A.flatMap(A.attempt(resource)) {
                case Left(err) => go(acc, bound.handleError(err).viewL)
                case Right(r) =>
                  val token = new Stream.Token()
                  lazy val finalizer_ = release(r)
                  val finalizer = A.suspend { finalizer_ }
                  resources.put(token, finalizer)
                  midAcquires.decrement
                  go(acc, g((r, token)).viewL)
              }
          case Algebra.Release(token) =>
            val finalizer = resources.remove(token)
            if (finalizer.asInstanceOf[AnyRef] eq null) go(acc, g(()).viewL)
            else A.flatMap(A.attempt(finalizer)) {
              case Left(err) => go(acc, bound.handleError(err).viewL)
              case Right(_) => go(acc, g(()).viewL)
            }
          case Algebra.Snapshot() =>
            // todo - think through whether we need to take a consistent snapshot of resources
            // if so we need a monotonically increasing nonce associated with each resource
            val tokens = JavaConverters.enumerationAsScalaIterator(resources.keys).toSet
            go(acc, g(tokens).viewL)
          case Algebra.UnconsAsync(s) =>
            type UO = Option[(Catenable[_], Stream[F,_])]
            type R = Either[Throwable, UO]
            val task: F[F[R]] = A.start { A.attempt {
              runFold(
                uncons(s.asInstanceOf[Stream[F,Any]]).flatMap(Pull.output1(_)),
                None: UO)((o,a) => a, startCompletion, midAcquires, resources)
            }}
            A.flatMap(task) { (task: F[R]) => go(acc, g(Pull.rethrow(Pull.eval[F,O,R](task))).viewL) }
          case Algebra.Output(_) => sys.error("impossible")
          case Algebra.Outputs(_) => sys.error("impossible")
        }
    }
    A.suspend { go(init, uncons(stream).algebra.viewL) }
  }
}

