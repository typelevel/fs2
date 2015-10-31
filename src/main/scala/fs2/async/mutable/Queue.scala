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
   * A `Sink` for enqueueing values to this `Queue`.
   */
  //todo: how about sinks?
  //def enqueue: Sink[Task, A]

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
   * Provides a process that dequeues in chunks.  Whenever *n* elements
   * are available in the queue, `min(n, limit)` elements will be dequeud
   * and produced as a single `Seq`.  Note that this naturally does not
   * *guarantee* that `limit` items are returned in every chunk.  If only
   * one element is available, that one element will be returned in its own
   * sequence.  This method basically just allows a consumer to "catch up"
   * to a rapidly filling queue in the case where some sort of batching logic
   * is applicable.
   */
  // todo: likely we don't need it as we have Chunks now?
  def dequeueBatch(limit: Int): Stream[F, Seq[A]]

  /**
   * Equivalent to dequeueBatch with an infinite limit.  Only use this
   * method if your underlying algebra (`A`) has some sort of constant
   * time "natural batching"!  If processing a chunk of size n is linearly
   * more expensive than processing a chunk of size 1, you should always
   * use dequeueBatch with some small limit, otherwise you will disrupt
   * fairness in the nondeterministic merge combinators.
   */
  // todo: likely we don't need it as we have Chunks now?
  def dequeueAvailable: Stream[F, Seq[A]]

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

  /**
   * Closes this queue. This halts the `enqueue` `Sink` and
   * `dequeue` `Process` after any already-queued elements are
   * drained.
   *
   * After this any enqueue will fail with `Terminated(End)`,
   * and the enqueue `Sink` will terminate with `End`.
   */
  // todo: Not sure on this. Should we get close off the Queue like signal? And leave it to external interruption?
  def close: F[Unit]


}
