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
