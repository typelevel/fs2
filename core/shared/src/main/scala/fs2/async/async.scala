package fs2

import scala.concurrent.ExecutionContext

import cats.Traverse
import cats.implicits.{catsSyntaxEither => _, _}
import cats.effect.{Async, Concurrent, Effect, Fiber, IO, Sync}

/** Provides utilities for asynchronous computations. */
package object async {

  /**
    * Creates a new continuous signal which may be controlled asynchronously,
    * and immediately sets the value to `initialValue`.
    */
  def signalOf[F[_]: Concurrent, A](initialValue: A)(
      implicit ec: ExecutionContext): F[mutable.Signal[F, A]] =
    mutable.Signal(initialValue)

  /** Creates a `[[mutable.Semaphore]]`, initialized to the given count. */
  def semaphore[F[_]: Concurrent](initialCount: Long): F[mutable.Semaphore[F]] =
    mutable.Semaphore(initialCount)

  /** Creates an unbounded asynchronous queue. See [[mutable.Queue]] for more documentation. */
  def unboundedQueue[F[_]: Concurrent, A](implicit ec: ExecutionContext): F[mutable.Queue[F, A]] =
    mutable.Queue.unbounded[F, A]

  /**
    * Creates a bounded asynchronous queue. Calls to `enqueue1` will wait until the
    * queue's size is less than `maxSize`. See [[mutable.Queue]] for more documentation.
    */
  def boundedQueue[F[_]: Concurrent, A](maxSize: Int)(
      implicit ec: ExecutionContext): F[mutable.Queue[F, A]] =
    mutable.Queue.bounded[F, A](maxSize)

  /**
    * Creates a synchronous queue, which always has size 0. Any calls to `enqueue1`
    * block until there is an offsetting call to `dequeue1`. Any calls to `dequeue1`
    * block until there is an offsetting call to `enqueue1`.
    */
  def synchronousQueue[F[_], A](implicit F: Concurrent[F],
                                ec: ExecutionContext): F[mutable.Queue[F, A]] =
    mutable.Queue.synchronous[F, A]

  /**
    * Creates a queue that functions as a circular buffer. Up to `size` elements of
    * type `A` will accumulate on the queue and then it will begin overwriting
    * the oldest elements. Thus an enqueue process will never wait.
    * @param maxSize The size of the circular buffer (must be > 0)
    */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F],
                                            ec: ExecutionContext): F[mutable.Queue[F, A]] =
    mutable.Queue.circularBuffer[F, A](maxSize)

  /**
    * Converts a discrete stream to a signal. Returns a single-element stream.
    *
    * Resulting signal is initially `initial`, and is updated with latest value
    * produced by `source`. If `source` is empty, the resulting signal will always
    * be `initial`.
    *
    * @param source   discrete stream publishing values to this signal
    */
  def hold[F[_], A](initial: A, source: Stream[F, A])(
      implicit F: Concurrent[F],
      ec: ExecutionContext): Stream[F, immutable.Signal[F, A]] =
    Stream.eval(signalOf[F, A](initial)).flatMap { sig =>
      Stream(sig).concurrently(source.evalMap(sig.set))
    }

  /** Defined as `[[hold]](None, source.map(Some(_)))` */
  def holdOption[F[_]: Concurrent, A](source: Stream[F, A])(
      implicit ec: ExecutionContext): Stream[F, immutable.Signal[F, Option[A]]] =
    hold(None, source.map(Some(_)))

  /**
    * Creates an asynchronous topic, which distributes each published `A` to
    * an arbitrary number of subscribers. Each subscriber is guaranteed to
    * receive at least the initial `A` or last value published by any publisher.
    */
  def topic[F[_]: Concurrent, A](initial: A)(
      implicit ec: ExecutionContext): F[mutable.Topic[F, A]] =
    mutable.Topic(initial)

  /** Creates an empty `Promise[F, A]` */
  def promise[F[_]: Concurrent, A]: F[Promise[F, A]] = Promise.empty

  /** Creates an initialized `SyncRef[F,A]`. */
  def refOf[F[_]: Sync, A](a: A): F[Ref[F, A]] = Ref[F, A](a)

  /** Like `traverse` but each `G[B]` computed from an `A` is evaluated in parallel. */
  @deprecated(
    "Use cats.Parallel.parTraverse instead. If G = IO and you want each IO to start executing on a pool thread, use cats.Parallel.parTraverse(IO.shift(ec) *> f(_)).",
    "1.0.0"
  )
  def parallelTraverse[F[_], G[_], A, B](fa: F[A])(
      f: A => G[B])(implicit F: Traverse[F], G: Concurrent[G], ec: ExecutionContext): G[F[B]] =
    F.traverse(fa)(f.andThen(start[G, B])).flatMap(F.sequence(_))

  /** Like `sequence` but each `G[A]` is evaluated in parallel. */
  @deprecated(
    "Use cats.Parallel.parSequence instead. If G = IO and you want each IO start executing on a pool thread, use cats.Parallel.parTraverse(IO.shift(ec) *> _).",
    "1.0.0"
  )
  def parallelSequence[F[_], G[_], A](
      fga: F[G[A]])(implicit F: Traverse[F], G: Concurrent[G], ec: ExecutionContext): G[F[A]] =
    parallelTraverse(fga)(identity)

  /**
    * Begins asynchronous evaluation of `f` when the returned `F[F[A]]` is
    * bound. The inner `F[A]` will block until the result is available.
    */
  @deprecated("Use Concurrent[F].start(Async.shift(ec) *> f) instead.", "1.0.0")
  def start[F[_], A](f: F[A])(implicit F: Concurrent[F], ec: ExecutionContext): F[F[A]] =
    shiftStart(f).map(_.join)

  /**
    * Lazily memoize `f`. For every time the returned `F[F[A]]` is
    * bound, the effect `f` will be performed at most once (when the
    * inner `F[A]` is bound the first time).
    *
    * @see `start` for eager memoization.
    */
  def once[F[_], A](f: F[A])(implicit F: Concurrent[F]): F[F[A]] =
    refOf[F, Option[Promise[F, Either[Throwable, A]]]](None).map { ref =>
      for {
        p <- promise[F, Either[Throwable, A]]
        ca <- ref.modify2 {
          case None =>
            (Some(p), f.attempt.flatTap(p.complete))
          case s @ Some(other) =>
            (s, other.get)
        }
        ma <- ca._2
        a <- F.fromEither(ma)
      } yield a
    }

  /**
    * Begins asynchronous evaluation of `f` when the returned `F[Unit]` is
    * bound.
    */
  @deprecated("Use Concurrent[F].start(Async.shift(ec) *> f) instead.", "1.0.0")
  def fork[F[_], A](f: F[A])(implicit F: Concurrent[F], ec: ExecutionContext): F[Unit] =
    shiftStart(f).void

  /**
    * Shifts `f` to the supplied execution context and then starts it, returning the spawned fiber.
    */
  def shiftStart[F[_], A](f: F[A])(implicit F: Concurrent[F],
                                   ec: ExecutionContext): F[Fiber[F, A]] =
    F.start(Async.shift(ec) *> f)

  /**
    * Like `unsafeRunSync` but execution is shifted to the supplied execution context.
    * This method returns immediately after submitting execution to the execution context.
    */
  def unsafeRunAsync[F[_], A](fa: F[A])(
      f: Either[Throwable, A] => IO[Unit])(implicit F: Effect[F], ec: ExecutionContext): Unit =
    F.runAsync(Async.shift(ec) *> fa)(f).unsafeRunSync
}
