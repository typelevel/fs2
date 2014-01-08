package scalaz.stream

import scalaz.{-\/, \/-, \/}
import scalaz.concurrent._
import scalaz.stream.Process.Writer1
import scalaz.stream.actor.TopicActor.Msg
import scalaz.stream.actor.{TopicActor, message, actors}
import scalaz.stream.async.mutable.WriterTopic

package object async {

  import mutable.{Queue,Ref,Signal,Topic}

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
   * Convert from an `Actor` accepting `message.queue.Msg[A]` messages 
   * to a `Queue[A]`. 
   */
  def actorRef[A](actor: Actor[message.ref.Msg[A]]): Ref[A] =
    new Ref[A] {

      import message.ref._

      @volatile var init = false
      protected[stream] def set_(f: (Option[A]) => Option[A], cb: (\/[Throwable, Option[A]]) => Unit, old: Boolean): Unit =  {
        actor ! Set(f,cb,old)
        init = true
      }

      protected[stream] def get_(cb: (\/[Throwable, (Int,A)]) => Unit, onlyChanged: Boolean, last: Int) : Unit = 
        actor ! Get(cb,onlyChanged,last)
 
      protected[stream] def fail_(t: Throwable, cb: (Throwable) => Unit):Unit =  
        actor ! Fail(t,cb)

      def isSet = init
    }

  /** 
   * Create a new continuous signal which may be controlled asynchronously.
   * All views into the returned signal are backed by the same underlying
   * asynchronous `Ref`.
   */
  def signal[A](implicit S: Strategy = Strategy.DefaultStrategy): Signal[A] =
    ref[A].signal
    
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
   * Returns a continuous `Process` whose value can be set 
   * asynchronously using the returned `Ref`. Callbacks will be 
   * run in the calling thread unless another thread is already
   * reading from the `Ref`, so `set` is not guaranteed to take
   * constant time. If this is not desired, use `ref` with a
   * `Strategy` other than `Strategy.Sequential`.
   */
  def localRef[A]: Ref[A] = 
    ref[A](Strategy.Sequential)
  

  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`. See `async.Queue`. As long as the
   * `Strategy` is not `Strategy.Sequential`, enqueueing is 
   * guaranteed to take constant time, and consumers will be run on
   * a separate logical thread. Current implementation is based on 
   * `actor.queue`.
   */
  def queue[A](implicit S: Strategy = Strategy.DefaultStrategy): (Queue[A], Process[Task,A]) = 
    actors.queue[A] match { case (snk, p) => (actorQueue(snk), p) }

  /**
   * Returns a ref, that can create continuous process, that can be set 
   * asynchronously using the returned `Ref`.
   */
  def ref[A](implicit S: Strategy = Strategy.DefaultStrategy): Ref[A] = 
    actors.ref[A](S) match { case (snk, p) => actorRef(snk)}

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
  def topic[A](implicit S: Strategy = Strategy.DefaultStrategy): Topic[A] = new Topic[A] {
    private[stream] val actor: Actor[Msg[Unit,A, A]] =
      TopicActor.topic[Unit,A, A](Process.emit(-\/(())) fby process1.lift{a=> \/-(a)})(S)
  }


  /**
   * Returns a writer topic. Writer topic uses supplied Writer1 to keep
   * state `S` from supplied `A` messages. Unlike `Topic` subscribers will always
   * Receive tuple `(S,A)` instead of `A` when subscribed.
   *
   * Writer topic also provides signal of `S`.
   *
   * Please note that Writer is required to write `S` initially. If writer emits instead `B` they will get discarded
   * until first `S` is emitted. That means subscriber will not see any `B` before `S` is seen for the first time.
   * This can be also used to control whether topic is required to receive `A` before subscribers get `S` or `B`.
   *
   *
   * Note the resulting topic will terminate whenever the `Writer1` terminates,
   * in addition when `close` or `fail` is called.
   */
  def writerTopic[S, A, B](w: Writer1[S, A, B])(implicit S: Strategy = Strategy.DefaultStrategy): WriterTopic[S, A, B] = new WriterTopic[S, A, B] {
    private[stream] val actor: Actor[Msg[S,A,B]] = TopicActor.topic[S,A,B](w)(S)
  }
}

