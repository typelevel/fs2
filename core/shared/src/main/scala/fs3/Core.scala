package fs2.fast

import fs2.util.{Free => _, _}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters

// TODO
// X add resource allocation algebra
// X add exception handling algebra
// add variance
// add fancy pure streams type
// performance testing
// integrate with rest of fs2

final class Pull[F[_], O, R](val algebra: Free[({type f[x] = Stream.Algebra[F, O, x]})#f, R]) extends AnyVal {
  def flatMap[R2](f: R => Pull[F, O, R2]): Pull[F, O, R2] =
    Pull(algebra.flatMap { r => f(r).algebra })
  def >>[R2](after: Pull[F, O, R2]): Pull[F, O, R2] = flatMap(_ => after)
  def covary[F2[_]](implicit S: Sub1[F,F2]): Pull[F2, O, R] = this.asInstanceOf[Pull[F2, O, R]]
  def covaryOutput[O2 >: O]: Pull[F, O2, R] = this.asInstanceOf[Pull[F, O2, R]]
  def covaryResult[R2 >: R]: Pull[F, O, R2] = this.asInstanceOf[Pull[F, O, R2]]
  def onError(h: Throwable => Pull[F,O,R]): Pull[F,O,R] =
    new Pull(algebra onError (e => h(e).algebra))
}

object Pull {
  import Stream.Algebra

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

  def ++(s: => Stream[F,O]): Stream[F,O] = Stream.fromPull(pull flatMap (_ => s.pull))

  def uncons[X]: Pull[F, X, Option[(Catenable[O], Stream[F, O])]] = {
    type AlgebraF[x] = Algebra[F,O,x]
    def assumeNoOutput[Y,R](p: Pull[F,Y,R]): Pull[F,X,R] =
      p.asInstanceOf[Pull[F,X,R]]
    pull.algebra.viewL match {
      case done: ViewL.Done[AlgebraF, Unit] =>
        Pull.pure(None)
      case failed: ViewL.Failed[AlgebraF, _] => Pull.fail(failed.error)
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
            bound.onError match {
              case None =>
                assumeNoOutput(Pull(Free.Eval(algebra)))
                  .flatMap(x => Stream.fromPull(Pull(bound.f(x))).uncons)
              case Some(onError) =>
                assumeNoOutput(Pull(Free.Eval(algebra)))
                  .flatMap(x => Stream.fromPull(Pull(bound.f(x))).uncons)
                  .onError(e => Stream.fromPull(Pull(bound.handleError(e))).uncons)
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
      case failed: ViewL.Failed[AlgebraF, _] =>
        F.fail(failed.error)
      case bound: ViewL.Bound[AlgebraF, _, Option[(Catenable[O], Stream[F, O])]] =>
        val g = bound.tryBind.asInstanceOf[Any => Free[AlgebraF, Option[(Catenable[O], Stream[F, O])]]]
        bound.fx match {
          case wrap: Algebra.Wrap[F, O, _] =>
            val wrapped: F[Any] = wrap.value.asInstanceOf[F[Any]]
            F.flatMap(wrapped) { x => go(acc, g(x).viewL) }
          case Algebra.Acquire(resource, release) =>
            midAcquires.increment
            if (startCompletion.get) { midAcquires.decrement; F.fail(Stream.Interrupted) }
            else
              F.flatMap(F.attempt(resource)) {
                case Left(err) => go(acc, bound.handleError(err).viewL)
                case Right(r) =>
                  val token = new Stream.Token()
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
            val tokens = JavaConverters.enumerationAsScalaIterator(resources.keys).toSet
            go(acc, g(tokens).viewL)
          case Algebra.UnconsAsync(s) =>
            type UO = Option[(Catenable[_], Stream[F,_])]
            type R = Either[Throwable, UO]
            val task: F[F[R]] = F.start {
              F.attempt {
                Stream.fromPull(s.uncons.flatMap(Pull.output1))
                      .runFold(startCompletion, midAcquires, resources)(None: UO) { (o,a) => a }
              }
            }
            F.flatMap(task) { (task: F[R]) =>
              go(acc, g(Pull.rethrow(Pull.eval[F,O,R](task))).viewL)
            }
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
  def empty[F[_],O]: Stream[F,O] = Stream.fromPull(Pull.pure(()))
  def fail[F[_],O](e: Throwable): Stream[F,O] = Stream.fromPull(Pull.fail(e))

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

object Woot extends App {
  import fs2.Task
  implicit val S = scala.concurrent.ExecutionContext.Implicits.global

  val N = 10000
  val s = (0 until N).map(Stream.emit[Task,Int](_)).foldLeft(Stream.empty[Task,Int])(_ ++ _)
  val s2 = (0 until N).map(fs2.Stream.emit).foldLeft(fs2.Stream.empty: fs2.Stream[Task,Int])(_ ++ _)

  timeit("new") {
    s.runFold(new AtomicBoolean(false), TwoWayLatch(0), new ConcurrentHashMap)(0)(_ + _).unsafeRun()
  }
  timeit("old") {
    s2.runFold(0)(_ + _).unsafeRun()
  }

  def timeit(label: String, threshold: Double = 0.99)(action: => Long): Long = {
    // todo - better statistics to determine when to stop, based on
    // assumption that distribution of runtimes approaches some fixed normal distribution
    // (as all methods relevant to overall performance get JIT'd)
    var N = 32
    var i = 0
    var startTime = System.nanoTime
    var stopTime = System.nanoTime
    var sample = 1L
    var previousSample = Long.MaxValue
    var K = 0L
    var ratio = sample.toDouble / previousSample.toDouble
    while (sample > previousSample || sample*N < 1e8 || ratio < threshold) {
      previousSample = sample
      N = (N.toDouble*1.2).toInt ; i = 0 ; startTime = System.nanoTime
      while (i < N) { K += action; i += 1 }
      stopTime = System.nanoTime
      sample = (stopTime - startTime) / N
      ratio = sample.toDouble / previousSample.toDouble
      println(s"iteration $label: " + formatNanos(sample) + " (average of " + N + " samples)")
    }
    println(label + ": " + formatNanos(sample) + " (average of " + N + " samples)")
    println("total number of samples across all iterations: " + K)
    sample
  }

  def formatNanos(nanos: Long) = {
    if (nanos > 1e9) (nanos.toDouble/1e9).toString + " seconds"
    else if (nanos > 1e6) (nanos.toDouble/1e6).toString + " milliseconds"
    else if (nanos > 1e3) (nanos.toDouble/1e3).toString + " microseconds"
    else nanos.toString + " nanoseconds"
  }
}
