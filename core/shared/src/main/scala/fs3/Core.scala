package fs2.fast

import fs2.util.{Free => _, _}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters

// TODO
// X add resource allocation algebra
// add exception handling algebra
// add variance
// add fancy pure streams type
// performance testing
// integrate with rest of fs2

final class Pull[F[_], O, R](val algebra: Free[({type f[x] = Stream.Algebra[F, O, x]})#f, R]) {
  def flatMap[R2](f: R => Pull[F, O, R2]): Pull[F, O, R2] =
    Pull(algebra.flatMap { r => f(r).algebra })
  def >>[R2](after: Pull[F, O, R2]): Pull[F, O, R2] = flatMap(_ => after)
  def covary[F2[_]](implicit S: Sub1[F,F2]): Pull[F2, O, R] = this.asInstanceOf[Pull[F2, O, R]]
  def covaryOutput[O2 >: O]: Pull[F, O2, R] = this.asInstanceOf[Pull[F, O2, R]]
  def covaryResult[R2 >: R]: Pull[F, O, R2] = this.asInstanceOf[Pull[F, O, R2]]
}

object Pull {
  import Stream.Algebra

  def apply[F[_], O, R](value: Free[({type f[x] = Stream.Algebra[F, O, x]})#f, R]): Pull[F, O, R] =
    new Pull[F, O, R](value)

  def eval[F[_], O, R](fr: F[R]): Pull[F, O, R] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull(Free.Eval[AlgebraF, R](Algebra.Wrap(fr)))
  }
  def output[F[_], O](os: Catenable[O]): Pull[F, O, Unit] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull(Free.Eval[AlgebraF, Unit](Algebra.Output(os)))
  }
  def output1[F[_], O](o: O): Pull[F, O, Unit] =
    output(Catenable.single(o))
  def outputs[F[_], O](s: Stream[F, O]): Pull[F, O, Unit] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull(Free.Eval[AlgebraF, Unit](Algebra.Outputs(s)))
  }
  def pure[F[_], O, R](r: R): Pull[F, O, R] = {
    type AlgebraF[x] = Algebra[F,O,x]
    Pull[F, O, R](Free.Pure[AlgebraF, R](r))
  }
}

final class Stream[F[_], O](val pull: Pull[F, O, Unit]) {
  import Free.ViewL
  import Stream.Algebra

  def covary[F2[x] >: F[x]]: Stream[F2, O] = this.asInstanceOf[Stream[F2, O]]
  def covaryOutput[O2 >: O]: Stream[F, O2] = this.asInstanceOf[Stream[F, O2]]

  def flatMap[O2](f: O => Stream[F, O2]): Stream[F, O2] = Stream.fromPull {
    uncons.flatMap {
      case None => Pull.pure(())
      case Some((hd, tl)) =>
        Pull.outputs(Stream.fromPull(hd.map(f).map(_.pull).toList.foldRight(Pull.pure[F, O2, Unit](()))((o, acc) => o >> acc))) >> tl.flatMap(f).pull
    }
  }

  def >>[O2](after: Stream[F, O2]): Stream[F, O2] = flatMap(_ => after)

  def uncons[X]: Pull[F, X, Option[(Catenable[O], Stream[F, O])]] = {
    type AlgebraF[x] = Algebra[F,O,x]
    def assumeNoOutput[Y,R](p: Pull[F,Y,R]): Pull[F,X,R] =
      p.asInstanceOf[Pull[F,X,R]]
    pull.algebra.viewL match {
      case done: ViewL.Done[AlgebraF, Unit] =>
        Pull.pure(None)
      case bound: ViewL.Bound[AlgebraF, _, Unit] =>
        bound.fx match {
          case Algebra.Output(os) =>
            val f = bound.f.asInstanceOf[Unit => Free[AlgebraF, Unit]]
            Pull.pure(Some((os, Stream.fromPull(Pull(f(()))))))
          case Algebra.Outputs(s) =>
            val f = bound.f.asInstanceOf[Unit => Free[AlgebraF, Unit]]
            s.uncons.flatMap {
              case None => Stream.fromPull(Pull(f(()))).uncons
              case Some((hd, tl)) =>
                Pull.pure(Some((hd, tl >> Stream.fromPull(Pull(f(()))))))
            }
          case algebra => // Wrap, Acquire, Release, Snapshot, UnconsAsync
            assumeNoOutput(Pull(Free.Eval(algebra)))
              .flatMap(x => Stream.fromPull(Pull(bound.f(x))).uncons)
            // todo error handling?
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
  def runFold[A](startCompletion: AtomicBoolean,
                 midAcquires: TwoWayLatch,
                 resources: ConcurrentHashMap[Stream.Token, F[Unit]])
                (init: A)(f: (A, O) => A)(implicit F: Async[F]): F[A] = {
    type AlgebraF[x] = Algebra[F,O,x]
    def go(acc: A, v: ViewL[AlgebraF, Option[(Catenable[O], Stream[F, O])]]): F[A] = v match {
      case done: ViewL.Done[AlgebraF, Option[(Catenable[O], Stream[F, O])]] =>
        done.r match {
          case None => F.pure(acc)
          case Some((hd, tl)) => go(hd.toList.foldLeft(acc)(f), tl.uncons.algebra.viewL)
        }
      case bound: ViewL.Bound[AlgebraF, _, Option[(Catenable[O], Stream[F, O])]] =>
        val g = bound.f.asInstanceOf[Any => Free[AlgebraF, Option[(Catenable[O], Stream[F, O])]]]
        bound.fx match {
          case wrap: Algebra.Wrap[F, O, _] =>
            val wrapped: F[Any] = wrap.value.asInstanceOf[F[Any]]
            F.flatMap(wrapped) { x => go(acc, g(x).viewL) }
          case Algebra.Acquire(resource, release) =>
            midAcquires.increment
            if (startCompletion.get) { midAcquires.decrement; F.fail(Stream.Interrupted) }
            else
              F.flatMap(F.attempt(resource)) {
                case Left(err) => ??? // todo stream.fail
                case Right(r) =>
                  val token = new Stream.Token()
                  val finalizer = release(r) // todo catch exceptions
                  resources.put(token, finalizer)
                  midAcquires.decrement
                  go(acc, g((r, token)).viewL)
              }
          case Algebra.Release(token) =>
            val finalizer = resources.remove(token)
            if (finalizer.asInstanceOf[AnyRef] eq null) go(acc, g(()).viewL)
            else F.flatMap(F.attempt(finalizer)) {
              case Left(err) => ??? // todo
              case Right(_) => go(acc, g(()).viewL)
            }
          case Algebra.Snapshot() =>
            // todo - think through whether we need to take a consistent snapshot of resources
            // if so we need a monotonically increasing nonce associated with each resource
            val tokens = JavaConverters.enumerationAsScalaIterator(resources.keys).toSet
            go(acc, g(tokens).viewL)
          case Algebra.UnconsAsync(s) =>
            val task = F.start {
              type R = Option[(Catenable[_], Stream[F,_])]
              Stream.fromPull(s.uncons.flatMap(Pull.output1))
                    .runFold(startCompletion, midAcquires, resources)(None: R) { (o,a) => a }
            }
            go(acc, g(Pull.eval(task)).viewL)
          case Algebra.Output(_) => sys.error("impossible")
          case Algebra.Outputs(_) => sys.error("impossible")
        }
    }
    F.suspend { go(init, uncons.algebra.viewL) }
  }
}

object Stream {
  def eval[F[_],O](fo: F[O]): Stream[F,O] = Stream.fromPull(Pull.eval(fo).flatMap(Pull.output1))
  def emit[F[_],O](o: O): Stream[F,O] = Stream.fromPull(Pull.output1(o))

  def fromPull[F[_],O](pull: Pull[F,O,Unit]): Stream[F,O] = new Stream(pull)

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
}

/*
If stream is sequential, it's very straightforward:
  - Resources are acquired and added to the map
  - At end of stream, all resources cleared from map
  - At end of stream segments, there can be some manual cleanup (via onComplete)
If we have concurrency, we have some issues:
  - There's an outer stream, and asynchronous steps of N inner streams
  - Need to ensure that any resources acquired by inner streams get freed when
    outer stream completes
  - Also want to safely terminate any inner streams when outer stream completes
  - Scenarios:
    * Outer stream completes, no inner stream is mid-acquire
    * Outer stream completes, 1 or more inner streams is mid-acquire
  - Algorithm:
    * When outer stream reaches end, it "begins completion".
    * After a stream "begins completion", inner streams that are mid-acquire are allowed
      to continue running, and any inner streams that aren't mid-acquire are killed
    * A stream transitions from "begins completion" to "completing" once no inner streams are mid-acquire
    * Once "completing", free all resources that have been acquired, outer stream is now "completed"
  - Correctness conditions:
    * Once outer stream "begins completion", no inner stream should be able to begin an acquire.
    * Outer stream should wait to transition to "completing" as long as there are inner streams mid-acquire
*/
