package scalaz.stream

import scalaz.concurrent.{Task, Strategy}
import scalaz.stream.Process.emit
import scalaz.stream.Process.halt
import scalaz.stream.async.mutable.{BoundedQueue, WriterTopic, Topic, Signal}
import scalaz.stream.merge.{JunctionStrategies, Junction}

/**
 * Created by pach on 03/05/14.
 */
package object async {

  /**
   * Creates bounded queue that is bound by supplied max size bound.
   * Please see [[scalaz.stream.async.mutable.BoundedQueue]] for more details.
   * @param max maximum size of queue. When <= 0 (default) queue is unbounded
   */
  def boundedQueue[A](max: Int = 0)(implicit S: Strategy): BoundedQueue[A] = {
    val junction = Junction(JunctionStrategies.boundedQ[A](max), Process.halt)(S)
    new BoundedQueue[A] {
      def enqueueOne(a: A): Task[Unit] = junction.receiveOne(a)
      def dequeue: Process[Task, A] = junction.downstreamO
      def size: immutable.Signal[Int] = stateSignal(junction.downstreamW)
      def enqueueAll(xa: Seq[A]): Task[Unit] = junction.receiveAll(xa)
      def enqueue: Sink[Task, A] = junction.upstreamSink
      def fail(rsn: Throwable): Task[Unit] = junction.downstreamClose(rsn)
    }
  }


  /**
   * Create a new continuous signal which may be controlled asynchronously.
   * All views into the returned signal are backed by the same underlying
   * asynchronous `Ref`.
   */
  def signal[A](implicit S: Strategy): Signal[A] = Signal(halt)

  /**
   * Converts discrete process to signal. Note that, resulting signal must be manually closed, in case the
   * source process would terminate. However if the source terminate with failure, the signal is terminated with that
   * failure
   * @param source discrete process publishing values to this signal
   */
  def toSignal[A](source: Process[Task, A])(implicit S: Strategy): mutable.Signal[A] =
    Signal(Process(source.map(Signal.Set(_))))

  /**
   * A signal constructor from discrete stream, that is backed by some sort of stateful primitive
   * like an Topic, another Signal or queue.
   *
   * If supplied process is normal process, it will, produce a signal that eventually may
   * be de-sync between changes, continuous, discrete or changed variants
   *
   * @param source
   * @tparam A
   * @return
   */
  private[stream] def stateSignal[A](source: Process[Task, A])(implicit S:Strategy) : immutable.Signal[A] =
    new immutable.Signal[A] {
      def changes: Process[Task, Unit] = discrete.map(_ => ())
      def continuous: Process[Task, A] = discrete.wye(Process.constant(()))(wye.echoLeft)(S)
      def discrete: Process[Task, A] = source
      def changed: Process[Task, Boolean] = (discrete.map(_ => true) merge Process.constant(false))
    }

  /**
   * Returns a topic, that can create publisher (sink) and subscriber (source)
   * processes that can be used to publish and subscribe asynchronously.
   * Please see `Topic` for more info.
   */
  def topic[A](source: Process[Task, A] = halt)(implicit S: Strategy): Topic[A] = {
    val junction = Junction(JunctionStrategies.publishSubscribe[A], Process(source))(S)
    new Topic[A] {
      def publish: Sink[Task, A] = junction.upstreamSink
      def subscribe: Process[Task, A] = junction.downstreamO
      def publishOne(a: A): Task[Unit] = junction.receiveOne(a)
      def fail(err: Throwable): Task[Unit] = junction.downstreamClose(err)
    }
  }

  /**
   * Returns Writer topic, that can create publisher(sink) of `I` and subscriber with signal of `W` values.
   * For more info see `WriterTopic`.
   * Note that when `source` ends, the topic does not terminate
   */
  def writerTopic[W, I, O](w: Writer1[W, I, O])(source: Process[Task, I] = halt)
    (implicit S: Strategy): WriterTopic[W, I, O] = {
    val q = boundedQueue[Process[Task, I]]()
    val junction = Junction(JunctionStrategies.liftWriter1(w), emit(source) ++ q.dequeue)(S)
    new WriterTopic[W, I, O] {
      def consumeOne(p: Process[Task, I]): Task[Unit] = q.enqueueOne(p)
      def consume: Sink[Task, Process[Task, I]] = q.enqueue
      def publish: Sink[Task, I] = junction.upstreamSink
      def subscribe: Writer[Task, W, O] = junction.downstreamBoth
      def subscribeO: Process[Task, O] = junction.downstreamO
      def subscribeW: Process[Task, W] = junction.downstreamW
      def signal: immutable.Signal[W] = stateSignal(subscribeW)(S)
      def publishOne(i: I): Task[Unit] = junction.receiveOne(i)
      def fail(err: Throwable): Task[Unit] =
        for {
          _ <- junction.downstreamClose(err)
          _ <- q.fail(err)
        } yield ()
    }
  }


}
