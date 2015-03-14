package scalaz.stream

import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.Process.halt
import scalaz.stream.async.mutable._

package object async {

  /**
   * Creates bounded queue that is bound by supplied max size bound.
   * Please see [[scalaz.stream.async.mutable.Queue]] for more details.
   * @param max maximum size of queue. When <= 0 (default) queue is unbounded
   */
  def boundedQueue[A](max: Int = 0)(implicit S: Strategy): Queue[A] =
    Queue[A](max)


  /**
   * Creates unbounded queue. see [[scalaz.stream.async.mutable.Queue]] for more
   */
  def unboundedQueue[A](implicit S: Strategy): Queue[A] =  boundedQueue(0)

  /**
   * Create a source that may be added to or halted asynchronously
   * using the returned `Queue`. See `async.Queue`. As long as the
   * `Strategy` is not `Strategy.Sequential`, enqueueing is
   * guaranteed to take constant time, and consumers will be run on
   * a separate logical thread.
   */
  @deprecated("Use async.unboundedQueue instead", "0.5.0")
  def queue[A](implicit S: Strategy) : (Queue[A], Process[Task, A]) = {
   val q = unboundedQueue[A]
    (q,q.dequeue)
  }

  /**
   * Create a new continuous signal which may be controlled asynchronously.
   */
  @deprecated("Use signalOf instead", "0.7.0")
  def signal[A](implicit S: Strategy): Signal[A] =
    toSignal(halt)

  /**
   * Creates a new continuous signalwhich may be controlled asynchronously,
   * and immediately sets the value to `initialValue`.
   */
  def signalOf[A](initialValue: A)(implicit S: Strategy): Signal[A] =
    toSignal(Process(initialValue))

  /**
   * Converts discrete process to signal. Note that, resulting signal must be manually closed, in case the
   * source process would terminate (see `haltOnSource`).
   * However if the source terminate with failure, the signal is terminated with that
   * failure
   * @param source          discrete process publishing values to this signal
   * @param haltOnSource    closes the given signal when the `source` terminates
   */
  def toSignal[A](source: Process[Task, A], haltOnSource: Boolean = false)(implicit S: Strategy): mutable.Signal[A] =
    Signal(source.map(Signal.Set(_)), haltOnSource)


  /**
   * Returns a topic, that can create publisher (sink) and subscriber (source)
   * processes that can be used to publish and subscribe asynchronously.
   * Please see `Topic` for more info.
   */
  def topic[A](source: Process[Task, A] = halt, haltOnSource: Boolean = false)(implicit S: Strategy): Topic[A] = {
    val wt = WriterTopic[Nothing, A, A](Process.liftW(process1.id))(source, haltOnSource = haltOnSource)(S)
    new Topic[A] {
      def publish: Sink[Task, A] = wt.publish
      def subscribe: Process[Task, A] = wt.subscribeO
      def publishOne(a: A): Task[Unit] = wt.publishOne(a)
      private[stream] def failWithCause(c: Cause): Task[Unit] = wt.failWithCause(c)
    }
  }

  /**
   * Returns Writer topic, that can create publisher(sink) of `I` and subscriber with signal of `W` values.
   * For more info see `WriterTopic`.
   * Note that when `source` ends, the topic does not terminate
   */
  def writerTopic[W, I, O](w: Writer1[W, I, O])(source: Process[Task, I] = halt, haltOnSource: Boolean = false)
    (implicit S: Strategy): WriterTopic[W, I, O] =
    WriterTopic[W, I, O](w)(source, haltOnSource = haltOnSource)(S)


}
