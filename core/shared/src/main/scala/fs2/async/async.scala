package fs2

import scala.concurrent.ExecutionContext

import cats.effect.Effect

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
  def hold[F[_]:Effect,A](initial: A, source: Stream[F, A])(implicit ec: ExecutionContext): Stream[F, immutable.Signal[F,A]] =
     immutable.Signal.hold(initial, source)

  /** Defined as `[[hold]](None, source.map(Some(_)))` */
  def holdOption[F[_]:Effect,A](source: Stream[F, A])(implicit ec: ExecutionContext): Stream[F, immutable.Signal[F,Option[A]]] =
     immutable.Signal.holdOption(source)

  /**
    * Creates an asynchronous topic, which distributes each published `A` to
    * an arbitrary number of subscribers. Each subscriber is guaranteed to
    * receive at least the initial `A` or last value published by any publisher.
    */
  def topic[F[_]:Effect,A](initial: A)(implicit ec: ExecutionContext): F[mutable.Topic[F,A]] =
    mutable.Topic(initial)
}
