package scalaz.stream.async.mutable

import scalaz.concurrent.Task
import scalaz.stream.Sink
import scalaz.stream.Process
import scalaz.stream.Process.End


/**
 * Queue that allows asynchronously create process or dump result of processes to
 * create eventually new processes that drain from this queue.
 *
 * Queue may have bound on its size, and that causes queue to control publishing processes
 * to stop publish when the queue reaches the size bound.
 * Bound (max size) is specified when queue is created, and may not be altered after.
 *
 * Queue also has signal that signals size of queue whenever that changes.
 * This may be used for example as additional flow control signalling outside this queue.
 *
 * Please see [[scalaz.stream.merge.JunctionStrategies.boundedQ]] for more details.
 *
 */
trait Queue[A] {

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
