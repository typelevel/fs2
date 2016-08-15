package fs2

import fs2.util.Async
import fs2.async.mutable.Topic

package object async {

  /**
   * Creates a new continuous signal which may be controlled asynchronously,
   * and immediately sets the value to `initialValue`.
   */
  def signalOf[F[_]:Async,A](initialValue: A): F[mutable.Signal[F,A]] =
    mutable.Signal(initialValue)

  /** Creates a `[[mutable.Semaphore]]`, initialized to the given count. */
  def semaphore[F[_]:Async](initialCount: Long): F[mutable.Semaphore[F]] =
    mutable.Semaphore(initialCount)

  /** Creates an unbounded asynchronous queue. See [[mutable.Queue]] for more documentation. */
  def unboundedQueue[F[_]:Async,A]: F[mutable.Queue[F,A]] =
    mutable.Queue.unbounded[F,A]

  /**
   * Creates a bounded asynchronous queue. Calls to `enqueue1` will wait until the
   * queue's size is less than `maxSize`. See [[mutable.Queue]] for more documentation.
   */
  def boundedQueue[F[_]:Async,A](maxSize: Int): F[mutable.Queue[F,A]] =
    mutable.Queue.bounded[F,A](maxSize)

  /**
   * Creates a synchronous queue, which always has size 0. Any calls to `enqueue1`
   * block until there is an offsetting call to `dequeue1`. Any calls to `dequeue1`
   * block until there is an offsetting call to `enqueue1`.
   */
  def synchronousQueue[F[_],A](implicit F: Async[F]): F[mutable.Queue[F,A]] =
    mutable.Queue.synchronous[F,A]

  /**
   * Creates a queue that functions as a circular buffer. Up to `size` elements of
   * type `A` will accumulate on the queue and then it will begin overwriting
   * the oldest elements. Thus an enqueue process will never wait.
   * @param size The size of the circular buffer (must be > 0)
   */
  def circularBuffer[F[_],A](maxSize: Int)(implicit F: Async[F]): F[mutable.Queue[F,A]] =
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
  def hold[F[_]:Async,A](initial: A, source: Stream[F, A]): Stream[F, immutable.Signal[F,A]] =
     immutable.Signal.hold(initial, source)

  /** Defined as `[[hold]](None, source.map(Some(_)))` */
  def holdOption[F[_]:Async,A](source: Stream[F, A]): Stream[F, immutable.Signal[F,Option[A]]] =
     immutable.Signal.holdOption(source)

  /**
    * Creates an asynchronous topic, which distributes each published `A` to
    * an arbitrary number of subscribers. Each subscriber is guaranteed to
    * receive at least the initial `A` or last value published by any publisher.
    */
  def topic[F[_]:Async,A](initial: A): F[Topic[F,A]] =
    Topic(initial)
}
