package fs2.async.mutable

import fs2._

import fs2.async.immutable


/**
 * Asynchronous queue interface. Operations are all nonblocking in their
 * implementations, but may be 'semantically' blocking. For instance,
 * a queue may have a bound on its size, in which case enqueuing may
 * block until there is an offsetting dequeue.
 */
trait Queue[F[_],A] {


  /**
   * Enqueue one element in this `Queue`. Resulting task will
   * terminate with failure if queue is closed or failed.
   * Please note this will get completed _after_ `a` has been successfully enqueued.
   * @param a `A` to enqueue
   */
  def enqueueOne(a: A): F[Unit]

  /**
   * Enqueue multiple `A` values in this queue. This has same semantics as sequencing
   * repeated calls to `enqueueOne`.
   */
  def enqueueAll(xa: Seq[A]): F[Unit]

  /**
   * Provides a process that dequeue from this queue.
   * When multiple consumers dequeue from this queue,
   * they dequeue in first-come, first-serve order.
   *
   * Please use `Topic` instead of `Queue` when all subscribers
   * need to see each value enqueued.
   *
   * This process is equivalent to `dequeueBatch(1)`.
   */
  def dequeue: Stream[F, A]

  /**
   * The time-varying size of this `Queue`. This signal refreshes
   * only when size changes. Offsetting enqueues and dequeues may
   * not result in refreshes.
   */
  def size: immutable.Signal[F,Int]

  /**
   * The size bound on the queue.
   * Returns None if the queue is unbounded.
   */
  def upperBound: Option[Int]

  /**
   * Returns the available number of entries in the queue.
   * Always returns `Int.MaxValue` when the queue is unbounded.
   */
  def available: immutable.Signal[F,Int]

  /**
   * Returns `true` when the queue has reached its upper size bound.
   * Always returns `false` when the queue is unbounded.
   */
  def full: immutable.Signal[F,Boolean]




}

