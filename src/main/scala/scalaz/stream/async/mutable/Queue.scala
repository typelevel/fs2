package scalaz.stream.async.mutable

import scalaz.\/
import scalaz.\/._
import scalaz.concurrent._
import java.util.concurrent.atomic._

import scalaz.stream.Process
import scalaz.stream.Process._

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

  /** 
   * Convert to a `Sink[Task, A]`. The `cleanup` action will be 
   * run when the `Sink` is terminated.
   */
  def toSink(cleanup: Queue[A] => Task[Unit] = (q: Queue[A]) => Task.delay {}): Sink[Task, A] = 
    scalaz.stream.async.toSink(this, cleanup)
}


/**
 * Queue that allows asynchronously create process or dump result of processes to
 * create eventually new processes that drain from this queue.
 *
 * Queue may have bound of its size, and that causes queue to control publishing processes
 * to stop publish when the queue reaches the size bound.
 * Bound (max size) is specified when queue is created, and may not be altered after.
 *
 * Queue also has signal that signals size of queue whenever that changes.
 * This may be used for example as additional flow control signalling outside this queue.
 *
 * Please see [[scalaz.stream.merge.JunctionStrategies.boundedQ]] for more details.
 *
 */
trait BoundedQueue[A] {

  /**
   * Provides a Sink, that allows to enqueue `A` in this queue
   */
  def enqueue: Sink[Task, A]

  /**
   * Enqueue one element in this Queue. Resulting task will terminate
   * with failure if queue is closed or failed.
   * Please note this will get completed _after_ `a` has been successfully enqueued.
   * @param a `A` to enqueue
   * @return
   */
  def enqueueOne(a: A): Task[Unit]

  /**
   * Enqueue sequence of `A` in this queue. It has same semantics as `enqueueOne`
   * @param xa sequence of `A`
   * @return
   */
  def enqueueAll(xa: Seq[A]): Task[Unit]

  /**
   * Provides a process that dequeue from this queue.
   * When multiple consumers dequeue from this queue,
   * Then they dequeue from this queue in first-come, first-server order.
   *
   * Please use `Topic` instead of `Queue` when you have multiple subscribers that
   * want to see each `A` value enqueued.
   *
   */
  def dequeue: Process[Task, A]

  /**
   * Provides signal, that contains size of this queue.
   * Please note the discrete source from this signal only signal actual changes of state, not the
   * values being enqueued or dequeued.
   * That means elements can get enqueued and dequeued, but signal of size may not update,
   * because size of the queue did not change.
   *
   */
  def size: scalaz.stream.async.immutable.Signal[Int]

  /**
   * Closes this queue. This has effect of enqueue Sink to be stopped
   * and dequeue process to be stopped immediately after last `A` being drained from queue.
   *
   * Please note this is completed _AFTER_ this queue is completely drained and all publishers
   * to queue are terminated.
   *
   */
  def close: Task[Unit] = fail(End)

  /**
   * Closes this queue. Unlike `close` this is run immediately when called and will not wait until
   * all elements are drained.
   */
  def closeNow: Unit = close.runAsync(_=>())


  /**
   * Like `close`, except it terminates with supplied reason.
   */
  def fail(rsn: Throwable): Task[Unit]

  /**
   * like `closeNow`, only allows to pass reason fro termination
   */
  def failNow(rsn:Throwable) : Unit = fail(rsn).runAsync(_=>())

}
