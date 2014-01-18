package scalaz.stream

import java.util.concurrent.atomic.AtomicReference
import scalaz.\/
import scalaz.concurrent._
import scalaz.stream.Process.End
import scalaz.stream.actor.actors
import scalaz.stream.actor.message
import scalaz.stream.async.mutable.Signal.Msg
import scalaz.stream.async.mutable.{WriterTopic, BoundedQueue}
import scalaz.stream.merge.{JunctionStrategies, Junction}

package object async {

  import mutable.{Queue,Signal,Topic}

  /**
   * Convert from an `Actor` accepting `message.queue.Msg[A]` messages 
   * to a `Queue[A]`. 
   */
  def actorQueue[A](actor: Actor[message.queue.Msg[A]]): Queue[A] =
    new Queue[A] {
      def enqueueImpl(a: A): Unit = actor ! message.queue.enqueue(a)
      def dequeueImpl(cb: (Throwable \/ A) => Unit): Unit = actor ! message.queue.Dequeue(cb)
      def fail(err: Throwable): Unit = actor ! message.queue.fail(err)
      def cancel: Unit = actor ! message.queue.cancel
      def close: Unit = actor ! message.queue.close
    }

  /** 
   * Create a new continuous signal which may be controlled asynchronously.
   * All views into the returned signal are backed by the same underlying
   * asynchronous `Ref`.
   */
  def signal[A](implicit S: Strategy = Strategy.DefaultStrategy): Signal[A] = {
    val junction = Junction(JunctionStrategies.signal[A], Process.halt)(S)
    new mutable.Signal[A] {
      def changed: Process[Task, Boolean] = discrete.map(_ => true) merge Process.constant(false)
      def discrete: Process[Task, A] = junction.downstreamW
      def continuous: Process[Task, A] = discrete.wye(Process.constant(()))(wye.echoLeft)(S)
      def changes: Process[Task, Unit] = discrete.map(_ => ())
      def sink: Process.Sink[Task, Msg[A]] = junction.upstreamSink
      def get: Task[A] = discrete.take(1).runLast.flatMap {
        case Some(a) => Task.now(a)
        case None    => Task.fail(End)
      }
      def getAndSet(a: A): Task[Option[A]] = {
        val refa = new AtomicReference[Option[A]](None)
        val fa =  (oa: Option[A]) => {refa.set(oa); Some(a) }
        junction.receiveOne(Signal.CompareAndSet(fa)).flatMap(_ => Task.now(refa.get()))
      }
      def set(a: A): Task[Unit] = junction.receiveOne(Signal.Set(a))
      def compareAndSet(f: (Option[A]) => Option[A]): Task[Option[A]] = {
        val refa = new AtomicReference[Option[A]](None)
        val fa = (oa: Option[A]) => {val set = f(oa); refa.set(set orElse oa); set }
        junction.receiveOne(Signal.CompareAndSet(fa)).flatMap(_ => Task.now(refa.get()))
      }
      def fail(error: Throwable): Task[Unit] = junction.downstreamClose(error)
    }
  }


  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`, `q`. On calling `q.enqueue(a)`, 
   * unless another thread is already processing the elements 
   * in the queue, listeners on the queue will be run using the calling
   * thread of `q.enqueue(a)`, which means that enqueueing is not
   * guaranteed to take constant time. If this is not desired, use 
   * `queue` with a `Strategy` other than `Strategy.Sequential`.
   */
  def localQueue[A]: (Queue[A], Process[Task,A]) = 
    queue[A](Strategy.Sequential)

  /**
   * Converts discrete process to signal.
   * @param source
   * @tparam A
   */
  def toSignal[A](source: Process[Task, A])(implicit S: Strategy = Strategy.DefaultStrategy): immutable.Signal[A] =
    new immutable.Signal[A] {
      def changes: Process[Task, Unit] = discrete.map(_ => ())
      def continuous: Process[Task, A] = discrete.wye(Process.constant(()))(wye.echoLeft)(S)
      def discrete: Process[Task, A] = source
      def changed: Process[Task, Boolean] = (discrete.map(_ => true) merge Process.constant(false))
   }


  /**
   * Creates bounded queue that is bound by supplied max size bound.
   * Please see [[scalaz.stream.async.mutable.BoundedQueue]] for more details.
   * @param max maximum size of queue. When <= 0 (default) queue is unbounded
   */
  def boundedQueue[A](max: Int = 0)(implicit S: Strategy = Strategy.DefaultStrategy): BoundedQueue[A] = {
    val junction = Junction(JunctionStrategies.boundedQ[A](max), Process.halt)(S)
    new BoundedQueue[A] {
      def enqueueOne(a: A): Task[Unit] = junction.receiveOne(a)
      def dequeue: Process[Task, A] = junction.downstreamO
      def size: immutable.Signal[Int] = toSignal(junction.downstreamW)
      def enqueueAll(xa: Seq[A]): Task[Unit] = junction.receiveAll(xa)
      def enqueue: Process.Sink[Task, A] = junction.upstreamSink
      def fail(rsn: Throwable): Task[Unit] = junction.downstreamClose(rsn)
    }
  }


  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`. See `async.Queue`. As long as the
   * `Strategy` is not `Strategy.Sequential`, enqueueing is 
   * guaranteed to take constant time, and consumers will be run on
   * a separate logical thread. Current implementation is based on 
   * `actor.queue`.
   */
  def queue[A](implicit S: Strategy = Strategy.DefaultStrategy): (Queue[A], Process[Task, A]) =
    actors.queue[A] match { case (snk, p) => (actorQueue(snk), p) }



  /** 
   * Convert an `Queue[A]` to a `Sink[Task, A]`. The `cleanup` action will be 
   * run when the `Sink` is terminated.
   */
  def toSink[A](q: Queue[A], cleanup: Queue[A] => Task[Unit] = (q: Queue[A]) => Task.delay {}): Process.Sink[Task,A] =
    io.resource(Task.now(q))(cleanup)(q => Task.delay { (a:A) => Task.now(q.enqueue(a)) })

  /**
   * Returns a topic, that can create publisher (sink) and subscriber (source)
   * processes that can be used to publish and subscribe asynchronously. 
   * Please see `Topic` for more info.
   */
  def topic[A](implicit S: Strategy = Strategy.DefaultStrategy): Topic[A] = {
     val junction = Junction(JunctionStrategies.publishSubscribe[A], Process.halt)(S)
     new Topic[A] {
       def publish: Process.Sink[Task, A] = junction.upstreamSink
       def subscribe: Process[Task, A] = junction.downstreamO
       def publishOne(a: A): Task[Unit] = junction.receiveOne(a)
       def fail(err: Throwable): Task[Unit] = junction.downstreamClose(err)
     }
  }

  /**
   * Returns Writer topic, that can create publisher(sink) of `I` and subscriber with signal of `W` values.
   * For more info see `WriterTopic`.
   */
  def writerTopic[W,I,O](w:Writer1[W,I,O])(implicit S: Strategy = Strategy.DefaultStrategy): WriterTopic[W,I,O] ={
    val junction = Junction(JunctionStrategies.liftWriter1(w), Process.halt)(S)
    new WriterTopic[W,I,O] {
      def publish: Process.Sink[Task, I] = junction.upstreamSink
      def subscribe: Process.Writer[Task, W, O] = junction.downstreamBoth
      def subscribeO: Process[Task, O] = junction.downstreamO
      def subscribeW: Process[Task, W] = junction.downstreamW
      def signal: immutable.Signal[W] = toSignal(subscribeW)
      def publishOne(i: I): Task[Unit] = junction.receiveOne(i)
      def fail(err: Throwable): Task[Unit] = junction.downstreamClose(err)
    }
  }
}

