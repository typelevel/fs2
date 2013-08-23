package scalaz.stream

import scalaz.\/
import scalaz.concurrent._
import java.util.concurrent.atomic._

trait async {
  import async.{Queue,Ref}

  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`. See `async.Queue`. As long as the
   * `Strategy` is not `Strategy.Sequential`, enqueueing is 
   * guaranteed to take constant time, and consumers will be run on
   * a separate logical thread. Current implementation is based on 
   * `actor.queue`.
   */
  def queue[A](implicit S: Strategy = Strategy.DefaultStrategy): (Queue[A], Process[Task,A]) = 
    actor.queue[A] match { case (snk, p) => (actorQueue(snk), p) }

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
  
  /*
   * Returns a continuous `Process` whose value can be set
   * asynchronously using the returned `Ref`.
   */
  def ref[A](implicit S: Strategy = Strategy.DefaultStrategy): (Ref[A], Process[Task,A]) = 
    actor.ref[A](S) match { case (snk, p) => (actorRef(snk), p) }

  /** 
   * Returns a continuous `Process` whose value can be set 
   * asynchronously using the returned `Ref`. Callbacks will be 
   * run in the calling thread unless another thread is already
   * reading from the `Ref`, so `set` is not guaranteed to take
   * constant time. If this is not desired, use `ref` with a
   * `Strategy` other than `Strategy.Sequential`.
   */
  def localRef[A]: (Ref[A], Process[Task,A]) = 
    ref[A](Strategy.Sequential)

  /** 
   * Returns a discrete `Process` whose value can be set asynchronously
   * using the returned `Ref`. Unlike `ref`, the returned `Process` will
   * only emit a value immediately after being `set`.
   */
  def observable[A](implicit S: Strategy = Strategy.DefaultStrategy): (Ref[A], Process[Task,A]) = {
    val (v, _) = ref[A](S)
    val obs = changed(v)
    val p = Process.repeatWrap { Task.async[A] { cb => obs.get(cb) } }
    (obs, p)
  }
    
  /** 
   * Transforms this `Ref` to only generate a `get` event
   * just after being `set`. 
   */
  def changed[A](r: Ref[A]): Ref[A] = actorRef[A] {
    import message.ref._
    var listeners = Vector[(Throwable \/ A) => Unit]() 
    def publishAndClear = {
      val l2 = Vector[(Throwable \/ A) => Unit]() 
      val l = listeners
      listeners = l2
      l.foreach(cb => r.get(cb))
    }
    Actor.actor[message.ref.Msg[A]] {
      case Get(cb) => listeners = listeners :+ cb 
      case Set(a) => { r.set(a); publishAndClear }
      case Close() => { r.close; publishAndClear } 
      case Fail(e) => { r.fail(e); publishAndClear } 
      case OnRead(cb) => { r.onRead(cb) }
    } (Strategy.Sequential)
  } 

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
      def setImpl(a: A): Unit = actor ! message.ref.set(a)
      def get(cb: (Throwable \/ A) => Unit): Unit = actor ! message.ref.Get(cb)
      def fail(err: Throwable): Unit = actor ! message.ref.fail(err)
      def close: Unit = actor ! message.ref.close
      def onRead(action: => Unit): Unit = actor ! message.ref.OnRead(() => action)
    }
  
  /** Convert an `Queue[A]` to a `Sink[Task, A]`. 
    * Cleanup may be run to eventually terminate queue once the sink will stop or to perform another cleanup action */
  def toSink[A](q:Queue[A], cleanUp: Queue[A] => Task[Unit] = (q:Queue[A]) => Task.delay {}):Process.Sink[Task,A] =
    io.resource(Task.now(q))(cleanUp)(q=>Task.delay{(a:A)=>Task.now(q.enqueue(a))}) 
}

object async extends async {

  /**
   * Syntax ops for easier work with async.Queue
   */
  implicit class QueueOps[A](val q:Queue[A]) {
    def toSink(cleanUp: Queue[A] => Task[Unit] = (q:Queue[A]) => Task.delay {}) = async.toSink(q,cleanUp)
  }
  
  trait Queue[A] {
    protected def enqueueImpl(a: A): Unit
    protected def dequeueImpl(cb: (Throwable \/ A) => Unit): Unit

    /** 
     * Asynchronously dequeue the next element from this `Queue`.
     * If no elements are currently available, the given callback
     * will be invoked later, when an element does become available,
     * or if an error occurs.
     */
    def dequeue(cb: (Throwable \/ A) => Unit): Unit = 
      dequeueImpl { r => sz.decrementAndGet; cb(r) }

    /**
     * Asynchronous trigger failure of this `Queue`. 
     */
    def fail(err: Throwable): Unit

    /** 
     * Halt consumers of this `Queue`, after allowing any unconsumed 
     * queued elements to be processed first. For immediate 
     * cancellation, ignoring any unconsumed elements, use `cancel`.  
     */
    def close: Unit

    /**
     * Halt consumers of this `Queue` immediately, ignoring any 
     * unconsumed queued elements.
     */
    def cancel: Unit

    /** 
     * Add an element to this `Queue` in FIFO order, and update the
     * size. 
     */
    def enqueue(a: A): Unit = {
      sz.incrementAndGet
      enqueueImpl(a)
    }
    private val sz = new AtomicInteger(0)

    /** 
     * Return the current number of unconsumed queued elements. 
     * Guaranteed to take constant time, but may be immediately
     * out of date.
     */
    def size = sz.get 
  }

  trait Ref[A] {
    protected def setImpl(a: A): Unit

    /** 
     * Asynchronously get the current value of this `Ref`. If this
     * `Ref` has not been `set`, the callback will be invoked later.
     */
    def get(callback: (Throwable \/ A) => Unit): Unit

    /** 
     * Indicate that the value is no longer valid. Any attempts to `set` this
     * `Ref` after a `close` will be ignored. 
     */
    def close: Unit

    /** 
     * Raise an asynchronous error for readers of this `Ref`. Any attempts to 
     * `set` this `Ref` after the `fail` shall be ignored.
     */
    def fail(error: Throwable): Unit

    /** Registers the given action to be run when this `Ref` is first `set`. */
    def onRead(action: => Unit): Unit
    
    /** 
     * Sets the value inside this `ref`. If this is the first time the `Ref`
     * is `set`, this triggers evaluation of any `onRead` actions registered
     * with the `Ref`. 
     */
    def set(a: A): Unit = {
      init = true
      setImpl(a)
    }

    @volatile private var init = false 
    def isSet: Boolean = init 
  }
}
