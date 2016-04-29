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
   * Enqueues one element in this `Queue`.
   * If the queue is `full` this waits until queue is empty.
   *
   * This completes after `a`  has been successfully enqueued to this `Queue`
   */
  def enqueue1(a: A): F[Unit]

  /**
   * Offers one element in this `Queue`.
   *
   * Evaluates to `false` if the queue is full, indicating the `a` was not queued up.
   * Evaluates to `true` if the `a` was queued up successfully.
   *
   * @param a `A` to enqueue
   */
  def offer1(a: A): F[Boolean]

  /** Repeatedly call `dequeue1` forever. */
  def dequeue: Stream[F, A] = Stream.repeatEval(dequeue1)

  /** Dequeue one `A` from this queue. Completes once one is ready. */
  def dequeue1: F[A]

  /**
   * The time-varying size of this `Queue`. This signal refreshes
   * only when size changes. Offsetting enqueues and de-queues may
   * not result in refreshes.
   */
  def size: immutable.Signal[F,Int]

  /** The size bound on the queue. `None` if the queue is unbounded. */
  def upperBound: Option[Int]

  /**
   * Returns the available number of entries in the queue.
   * Always `Int.MaxValue` when the queue is unbounded.
   */
  def available: immutable.Signal[F,Int]

  /**
   * Returns `true` when the queue has reached its upper size bound.
   * Always `false` when the queue is unbounded.
   */
  def full: immutable.Signal[F,Boolean]
}

object Queue {

  def unbounded[F[_],A](implicit F: Async[F]): F[Queue[F,A]] = {
    /*
      * Internal state of the queue
      * @param queue    Queue, expressed as vector for fast cons/uncons from head/tail
      * @param deq      A list of waiting dequeuers, added to when queue is empty
      */
    case class State(queue: Vector[A], deq: Vector[F.Ref[A]])

    F.bind(Signal(0)) { szSignal =>
    F.map(F.refOf[State](State(Vector.empty,Vector.empty))) { qref =>
      // Signals size change of queue, if that has changed
      def signalSize(s: State, ns: State) : F[Unit] = {
        if (s.queue.size != ns.queue.size) szSignal.set(ns.queue.size)
        else F.pure(())
      }

      new Queue[F,A] {
        def upperBound: Option[Int] = None
        def enqueue1(a:A): F[Unit] = F.map(offer1(a))(_ => ())
        def offer1(a: A): F[Boolean] =
          F.bind(F.modify(qref) { s =>
            if (s.deq.isEmpty) s.copy(queue = s.queue :+ a)
            else s.copy(deq = s.deq.tail)
          }) { c =>
            if (c.previous.deq.isEmpty) // we enqueued a value to the queue
              F.map(signalSize(c.previous, c.now)) { _ => true }
            else // queue was empty, we had waiting dequeuers
              F.map(F.setPure(c.previous.deq.head)(a)) { _ => true }
          }

        def dequeue1: F[A] =
          F.bind(F.ref[A]) { r =>
          F.bind(F.modify(qref) { s =>
            if (s.queue.isEmpty) s.copy(deq = s.deq :+ r)
            else s.copy(queue = s.queue.tail)
          }) { c =>
            F.bind(signalSize(c.previous, c.now)) { _ =>
              if (c.previous.queue.nonEmpty) F.pure(c.previous.queue.head)
              else F.get(r)
            }
          }}
        def size = szSignal
        def full: immutable.Signal[F, Boolean] = Signal.constant[F,Boolean](false)
        def available: immutable.Signal[F, Int] = Signal.constant[F,Int](Int.MaxValue)
      }
    }}}

  def bounded[F[_],A](maxSize: Int)(implicit F: Async[F]): F[Queue[F,A]] =
    F.bind(Semaphore(maxSize.toLong)) { permits =>
    F.map(unbounded[F,A]) { q =>
      new Queue[F,A] {
        def upperBound: Option[Int] = Some(maxSize)
        def enqueue1(a:A): F[Unit] =
          F.bind(permits.decrement) { _ => q.enqueue1(a) }
        def offer1(a: A): F[Boolean] =
          F.bind(permits.tryDecrement) { b => if (b) q.offer1(a) else F.pure(false) }
        def dequeue1: F[A] =
          F.bind(q.dequeue1) { a => F.map(permits.increment)(_ => a) }
        def size = q.size
        def full: immutable.Signal[F, Boolean] = q.size.map(_ >= maxSize)
        def available: immutable.Signal[F, Int] = q.size.map(maxSize - _)
      }
    }}

  def synchronous[F[_],A](implicit F: Async[F]): F[Queue[F,A]] =
    F.bind(Semaphore(0)) { permits =>
    F.map(unbounded[F,A]) { q =>
      new Queue[F,A] {
        def upperBound: Option[Int] = Some(0)
        def enqueue1(a: A): F[Unit] =
          F.bind(permits.decrement) { _ => q.enqueue1(a) }
        def offer1(a: A): F[Boolean] =
          F.bind(permits.tryDecrement) { b => if (b) q.offer1(a) else F.pure(false) }
        def dequeue1: F[A] =
          F.bind(permits.increment) { _ => q.dequeue1 }
        def size = q.size
        def full: immutable.Signal[F, Boolean] = Signal.constant(true)
        def available: immutable.Signal[F, Int] = Signal.constant(0)
      }
    }}

  /** Like `Queue.synchronous`, except that an enqueue or offer of `None` will never block. */
  def synchronousNoneTerminated[F[_],A](implicit F: Async[F]): F[Queue[F,Option[A]]] =
    F.bind(Semaphore(0)) { permits =>
    F.map(unbounded[F,Option[A]]) { q =>
      new Queue[F,Option[A]] {
        def upperBound: Option[Int] = Some(0)
        def enqueue1(a: Option[A]): F[Unit] = a match {
          case None => q.enqueue1(None)
          case _ => F.bind(permits.decrement) { _ => q.enqueue1(a) }
        }
        def offer1(a: Option[A]): F[Boolean] = a match {
          case None => q.offer1(None)
          case _ => F.bind(permits.tryDecrement) { b => if (b) q.offer1(a) else F.pure(false) }
        }
        def dequeue1: F[Option[A]] =
          F.bind(permits.increment) { _ => q.dequeue1 }
        def size = q.size
        def full: immutable.Signal[F, Boolean] = Signal.constant(true)
        def available: immutable.Signal[F, Int] = Signal.constant(0)
      }
    }}
}
