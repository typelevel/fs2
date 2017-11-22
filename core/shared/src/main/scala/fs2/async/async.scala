package fs2

import scala.concurrent.ExecutionContext

import cats.Traverse
import cats.implicits.{ catsSyntaxEither => _, _ }
import cats.effect.{ Effect, IO }

/** Provides utilities for asynchronous computations. */
package object async {

  /**
   * Creates a new continuous signal which may be controlled asynchronously,
   * and immediately sets the value to `initialValue`.
   */
  def signalOf[F[_]:Effect,A](initialValue: A)(implicit ec: ExecutionContext): F[mutable.Signal[F,A]] =
    mutable.Signal(initialValue)

  /** Creates a `[[mutable.Semaphore]]`, initialized to the given count. */
  def semaphore[F[_]:Effect](initialCount: Long)(implicit ec: ExecutionContext): F[mutable.Semaphore[F]] =
    mutable.Semaphore(initialCount)

  /** Creates an unbounded asynchronous queue. See [[mutable.Queue]] for more documentation. */
  def unboundedQueue[F[_]:Effect,A](implicit ec: ExecutionContext): F[mutable.Queue[F,A]] =
    mutable.Queue.unbounded[F,A]

  /**
   * Creates a bounded asynchronous queue. Calls to `enqueue1` will wait until the
   * queue's size is less than `maxSize`. See [[mutable.Queue]] for more documentation.
   */
  def boundedQueue[F[_]:Effect,A](maxSize: Int)(implicit ec: ExecutionContext): F[mutable.Queue[F,A]] =
    mutable.Queue.bounded[F,A](maxSize)

  /**
   * Creates a synchronous queue, which always has size 0. Any calls to `enqueue1`
   * block until there is an offsetting call to `dequeue1`. Any calls to `dequeue1`
   * block until there is an offsetting call to `enqueue1`.
   */
  def synchronousQueue[F[_],A](implicit F: Effect[F], ec: ExecutionContext): F[mutable.Queue[F,A]] =
    mutable.Queue.synchronous[F,A]

  /**
   * Creates a queue that functions as a circular buffer. Up to `size` elements of
   * type `A` will accumulate on the queue and then it will begin overwriting
   * the oldest elements. Thus an enqueue process will never wait.
   * @param maxSize The size of the circular buffer (must be > 0)
   */
  def circularBuffer[F[_],A](maxSize: Int)(implicit F: Effect[F], ec: ExecutionContext): F[mutable.Queue[F,A]] =
    mutable.Queue.circularBuffer[F,A](maxSize)

  /**
   * Converts a discrete stream to a signal. Returns a single-element stream.
   *
   * Resulting signal is initially `initial`, and is updated with latest value
   * produced by `source`. If `source` is empty, the resulting signal will always
   * be `initial`.
   *
   * @param source   discrete stream publishing values to this signal
   */
  def hold[F[_],A](initial: A, source:Stream[F,A])(implicit F: Effect[F], ec: ExecutionContext): Stream[F,immutable.Signal[F,A]] =
    Stream.eval(signalOf[F,A](initial)) flatMap { sig =>
      Stream(sig).concurrently(source.evalMap(sig.set))
    }

  /** Defined as `[[hold]](None, source.map(Some(_)))` */
  def holdOption[F[_]:Effect,A](source: Stream[F, A])(implicit ec: ExecutionContext): Stream[F, immutable.Signal[F,Option[A]]] =
    hold(None, source.map(Some(_)))

  /**
    * Creates an asynchronous topic, which distributes each published `A` to
    * an arbitrary number of subscribers. Each subscriber is guaranteed to
    * receive at least the initial `A` or last value published by any publisher.
    */
  def topic[F[_]:Effect,A](initial: A)(implicit ec: ExecutionContext): F[mutable.Topic[F,A]] =
    mutable.Topic(initial)

  /** Creates an asynchronous, concurrent mutable reference. */
  def ref[F[_], A](implicit F: Effect[F], ec: ExecutionContext): F[Ref[F,A]] =
    F.delay(new Ref[F, A])

  /** Creates an asynchronous, concurrent mutable reference, initialized to `a`. */
  def refOf[F[_]: Effect, A](a: A)(implicit ec: ExecutionContext): F[Ref[F,A]] = ref[F, A].flatMap(r => r.setAsyncPure(a).as(r))

  /** Like `traverse` but each `G[B]` computed from an `A` is evaluated in parallel. */
  def parallelTraverse[F[_], G[_], A, B](fa: F[A])(f: A => G[B])(implicit F: Traverse[F], G: Effect[G], ec: ExecutionContext): G[F[B]] =
    F.traverse(fa)(f andThen start[G, B]).flatMap(G.sequence(_))

  /** Like `sequence` but each `G[A]` is evaluated in parallel. */
  def parallelSequence[F[_], G[_], A](fga: F[G[A]])(implicit F: Traverse[F], G: Effect[G], ec: ExecutionContext): G[F[A]] =
    parallelTraverse(fga)(identity)

  /**
   * Begins asynchronous evaluation of `f` when the returned `F[F[A]]` is
   * bound. The inner `F[A]` will block until the result is available.
   */
  def start[F[_], A](f: F[A])(implicit F: Effect[F], ec: ExecutionContext): F[F[A]] =
    ref[F, A].flatMap { ref => ref.setAsync(F.shift(ec) *> f).as(ref.get) }

  /**
   * Begins asynchronous evaluation of `f` when the returned `F[Unit]` is
   * bound. Like `start` but is more efficient.
   */
  def fork[F[_], A](f: F[A])(implicit F: Effect[F], ec: ExecutionContext): F[Unit] =
    F.liftIO(F.runAsync(F.shift *> f) { _ => IO.unit })

  /**
    * Returns an effect that, when run, races evaluation of `fa` and `fb`,
    * and returns the result of whichever completes first. The losing effect
    * continues to execute in the background though its result will be sent
    * nowhere.
   */
  def race[F[_]: Effect, A, B](fa: F[A], fb: F[B])(implicit ec: ExecutionContext): F[Either[A, B]] =
    ref[F, Either[A,B]].flatMap { ref =>
      ref.race(fa.map(Left.apply), fb.map(Right.apply)) *> ref.get
    }

  /**
   * Like `unsafeRunSync` but execution is shifted to the supplied execution context.
   * This method returns immediately after submitting execution to the execution context.
   */
  def unsafeRunAsync[F[_], A](fa: F[A])(f: Either[Throwable, A] => IO[Unit])(implicit F: Effect[F], ec: ExecutionContext): Unit =
    F.runAsync(F.shift(ec) *> fa)(f).unsafeRunSync
}
