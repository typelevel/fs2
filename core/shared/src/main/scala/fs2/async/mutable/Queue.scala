package fs2
package async
package mutable

import fs2.util.{Async,Functor}
import fs2.util.syntax._

/**
 * Asynchronous queue interface. Operations are all nonblocking in their
 * implementations, but may be 'semantically' blocking. For instance,
 * a queue may have a bound on its size, in which case enqueuing may
 * block until there is an offsetting dequeue.
 */
trait Queue[F[_], A] { self =>

  /**
   * Enqueues one element in this `Queue`.
   * If the queue is `full` this waits until queue is empty.
   *
   * This completes after `a`  has been successfully enqueued to this `Queue`
   */
  def enqueue1(a: A): F[Unit]

  /**
   * Enqueues each element of the input stream to this `Queue` by
   * calling `enqueue1` on each element.
   */
  def enqueue: Sink[F, A] = _.evalMap(enqueue1)

  /**
   * Offers one element in this `Queue`.
   *
   * Evaluates to `false` if the queue is full, indicating the `a` was not queued up.
   * Evaluates to `true` if the `a` was queued up successfully.
   *
   * @param a `A` to enqueue
   */
  def offer1(a: A): F[Boolean]

  /** Dequeue one `A` from this queue. Completes once one is ready. */
  def dequeue1: F[A]

  /** Like `dequeue1` but provides a way to cancel the dequeue. */
  def cancellableDequeue1: F[(F[A], F[Unit])]

  /** Dequeue `A`s from this queue. Completes once any values are ready. */
  def dequeueBatch1(batchSize: Int): F[Chunk[A]]

  /** Like `dequeueAvailable1` but provides a way to cancel the dequeue. */
  def cancellableDequeueBatch1(batchSize: Int): F[(F[Chunk[A]], F[Unit])]

  /** Repeatedly call `dequeue1` forever. */
  def dequeue: Stream[F, A] = Stream.bracket(cancellableDequeue1)(d => Stream.eval(d._1), d => d._2).repeat

  /** Call `dequeueBatch1` once with a provided bound on the elements dequeued. */
  def dequeueBatch: Pipe[F, Int, A] = _.flatMap { batchSize =>
    Stream.bracket(cancellableDequeueBatch1(batchSize))(d => Stream.eval(d._1).flatMap(Stream.chunk), d => d._2)
  }

  /** Call `dequeueBatch1` repeatedly with a bound of `Int.MaxValue` */
  def dequeueAvailable: Stream[F, A] = Stream.constant(Int.MaxValue).through(dequeueBatch)

  /**
   * The time-varying size of this `Queue`. This signal refreshes
   * only when size changes. Offsetting enqueues and de-queues may
   * not result in refreshes.
   */
  def size: immutable.Signal[F, Int]

  /** The size bound on the queue. `None` if the queue is unbounded. */
  def upperBound: Option[Int]

  /**
   * Returns the available number of entries in the queue.
   * Always `Int.MaxValue` when the queue is unbounded.
   */
  def available: immutable.Signal[F, Int]

  /**
   * Returns `true` when the queue has reached its upper size bound.
   * Always `false` when the queue is unbounded.
   */
  def full: immutable.Signal[F, Boolean]

  /**
   * Returns an alternate view of this `Queue` where its elements are of type [[B]],
   * given back and forth function from `A` to `B`.
   */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Queue[F, B] =
    new Queue[F, B] {
      def available: immutable.Signal[F, Int] = self.available
      def full: immutable.Signal[F, Boolean] = self.full
      def size: immutable.Signal[F, Int] = self.size
      def upperBound: Option[Int] = self.upperBound
      def enqueue1(a: B): F[Unit] = self.enqueue1(g(a))
      def offer1(a: B): F[Boolean] = self.offer1(g(a))
      def dequeue1: F[B] = self.dequeue1.map(f)
      def cancellableDequeue1: F[(F[B],F[Unit])] =
        self.cancellableDequeue1.map(bu => bu._1.map(f) -> bu._2)
      def dequeueBatch1(batchSize: Int): F[Chunk[B]] =
        self.dequeueBatch1(batchSize).map(_.map(f))
      def cancellableDequeueBatch1(batchSize: Int): F[(F[Chunk[B]],F[Unit])] =
        self.cancellableDequeueBatch1(batchSize).map(bu => bu._1.map(_.map(f)) -> bu._2)
    }
}

object Queue {

  def unbounded[F[_],A](implicit F: Async[F]): F[Queue[F,A]] = {
    /*
      * Internal state of the queue
      * @param queue    Queue, expressed as vector for fast cons/uncons from head/tail
      * @param deq      A list of waiting dequeuers, added to when queue is empty
      */
    final case class State(queue: Vector[A], deq: Vector[Async.Ref[F,Chunk[A]]])

    Signal(0).flatMap { szSignal =>
    F.refOf[State](State(Vector.empty,Vector.empty)).map { qref =>
      // Signals size change of queue, if that has changed
      def signalSize(s: State, ns: State) : F[Unit] = {
        if (s.queue.size != ns.queue.size) szSignal.set(ns.queue.size)
        else F.pure(())
      }

      new Queue[F,A] {
        def upperBound: Option[Int] = None
        def enqueue1(a:A): F[Unit] = offer1(a).as(())
        def offer1(a: A): F[Boolean] =
          qref.modify { s =>
            if (s.deq.isEmpty) s.copy(queue = s.queue :+ a)
            else s.copy(deq = s.deq.tail)
          }.flatMap { c =>
            if (c.previous.deq.isEmpty) // we enqueued a value to the queue
              signalSize(c.previous, c.now).as(true)
            else // queue was empty, we had waiting dequeuers
              c.previous.deq.head.setPure(Chunk.singleton(a)).as(true)
          }

        def dequeue1: F[A] = cancellableDequeue1.flatMap { _._1 }

        def cancellableDequeue1: F[(F[A],F[Unit])] =
          cancellableDequeueBatch1(1).map { case (deq,cancel) => (deq.map(_(0)),cancel) }

        def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
          cancellableDequeueBatch1(batchSize).flatMap { _._1 }

        def cancellableDequeueBatch1(batchSize: Int): F[(F[Chunk[A]],F[Unit])] =
          F.ref[Chunk[A]].flatMap { r =>
            qref.modify { s =>
              if (s.queue.isEmpty) s.copy(deq = s.deq :+ r)
              else s.copy(queue = s.queue.drop(batchSize))
            }.map { c =>
              val deq = signalSize(c.previous, c.now).flatMap { _ =>
                if (c.previous.queue.nonEmpty) F.pure {
                    if (batchSize == 1) Chunk.singleton(c.previous.queue.head) else Chunk.indexedSeq(c.previous.queue.take(batchSize))
                }
                else r.get
              }
              val cleanup =
                if (c.previous.queue.nonEmpty) F.pure(())
                else qref.modify { s =>
                  s.copy(deq = s.deq.filterNot(_ == r))
                }.as(())
              (deq,cleanup)
            }}

        def size = szSignal
        def full: immutable.Signal[F, Boolean] = Signal.constant[F,Boolean](false)
        def available: immutable.Signal[F, Int] = Signal.constant[F,Int](Int.MaxValue)
      }
    }}}

  def bounded[F[_],A](maxSize: Int)(implicit F: Async[F]): F[Queue[F,A]] =
    Semaphore(maxSize.toLong).flatMap { permits =>
    unbounded[F,A].map { q =>
      new Queue[F,A] {
        def upperBound: Option[Int] = Some(maxSize)
        def enqueue1(a:A): F[Unit] =
          permits.decrement >> q.enqueue1(a)
        def offer1(a: A): F[Boolean] =
          permits.tryDecrement.flatMap { b => if (b) q.offer1(a) else F.pure(false) }
        def dequeue1: F[A] = cancellableDequeue1.flatMap { _._1 }
        override def cancellableDequeue1: F[(F[A], F[Unit])] =
          cancellableDequeueBatch1(1).map { case (deq,cancel) => (deq.map(_(0)),cancel) }
        override def dequeueBatch1(batchSize: Int): F[Chunk[A]] = cancellableDequeueBatch1(batchSize).flatMap { _._1 }
        def cancellableDequeueBatch1(batchSize: Int): F[(F[Chunk[A]],F[Unit])] =
          q.cancellableDequeueBatch1(batchSize).map { case (deq,cancel) => (deq.flatMap(a => permits.incrementBy(a.size).as(a)), cancel) }
        def size = q.size
        def full: immutable.Signal[F, Boolean] = q.size.map(_ >= maxSize)
        def available: immutable.Signal[F, Int] = q.size.map(maxSize - _)
      }
    }}

  def circularBuffer[F[_],A](maxSize: Int)(implicit F: Async[F]): F[Queue[F,A]] =
    Semaphore(maxSize.toLong).flatMap { permits =>
    unbounded[F,A].map { q =>
      new Queue[F,A] {
        def upperBound: Option[Int] = Some(maxSize)
        def enqueue1(a:A): F[Unit] =
          permits.tryDecrement.flatMap { b => if (b) q.enqueue1(a) else (q.dequeue1 >> q.enqueue1(a)) }
        def offer1(a: A): F[Boolean] =
          enqueue1(a).as(true)
        def dequeue1: F[A] = cancellableDequeue1.flatMap { _._1 }
        def dequeueBatch1(batchSize: Int): F[Chunk[A]] = cancellableDequeueBatch1(batchSize).flatMap { _._1 }
        def cancellableDequeue1: F[(F[A], F[Unit])] = cancellableDequeueBatch1(1).map { case (deq,cancel) => (deq.map(_(0)),cancel) }
        def cancellableDequeueBatch1(batchSize: Int): F[(F[Chunk[A]],F[Unit])] =
          q.cancellableDequeueBatch1(batchSize).map { case (deq,cancel) => (deq.flatMap(a => permits.incrementBy(a.size).as(a)), cancel) }
        def size = q.size
        def full: immutable.Signal[F, Boolean] = q.size.map(_ >= maxSize)
        def available: immutable.Signal[F, Int] = q.size.map(maxSize - _)
      }
    }}

  def synchronous[F[_],A](implicit F: Async[F]): F[Queue[F,A]] =
    Semaphore(0).flatMap { permits =>
    unbounded[F,A].map { q =>
      new Queue[F,A] {
        def upperBound: Option[Int] = Some(0)
        def enqueue1(a: A): F[Unit] =
          permits.decrement >> q.enqueue1(a)
        def offer1(a: A): F[Boolean] =
          permits.tryDecrement.flatMap { b => if (b) q.offer1(a) else F.pure(false) }
        def dequeue1: F[A] = cancellableDequeue1.flatMap { _._1 }
        def cancellableDequeue1: F[(F[A],F[Unit])] =
          permits.increment >> q.cancellableDequeue1
        override def dequeueBatch1(batchSize: Int): F[Chunk[A]] = q.dequeueBatch1(batchSize)
        def cancellableDequeueBatch1(batchSize: Int): F[(F[Chunk[A]],F[Unit])] =
          permits.increment >> q.cancellableDequeueBatch1(batchSize)
        def size = q.size
        def full: immutable.Signal[F, Boolean] = Signal.constant(true)
        def available: immutable.Signal[F, Int] = Signal.constant(0)
      }
    }}

  /** Like `Queue.synchronous`, except that an enqueue or offer of `None` will never block. */
  def synchronousNoneTerminated[F[_],A](implicit F: Async[F]): F[Queue[F,Option[A]]] =
    Semaphore(0).flatMap { permits =>
    F.refOf(false).flatMap { doneRef =>
    unbounded[F,Option[A]].map { q =>
      new Queue[F,Option[A]] {
        def upperBound: Option[Int] = Some(0)
        def enqueue1(a: Option[A]): F[Unit] = doneRef.access.flatMap { case (done, update) =>
          if (done) F.pure(())
          else a match {
            case None => update(Right(true)).flatMap { successful => if (successful) q.enqueue1(None) else enqueue1(None) }
            case _ => permits.decrement >> q.enqueue1(a)
          }
        }
        def offer1(a: Option[A]): F[Boolean] = doneRef.access.flatMap { case (done, update) =>
          if (done) F.pure(true)
          else a match {
            case None => update(Right(true)).flatMap { successful => if (successful) q.offer1(None) else offer1(None) }
            case _ => permits.decrement >> q.offer1(a)
          }
        }
        def dequeue1: F[Option[A]] = cancellableDequeue1.flatMap { _._1 }
        def cancellableDequeue1: F[(F[Option[A]],F[Unit])] =
          permits.increment >> q.cancellableDequeue1
        override def dequeueBatch1(batchSize: Int): F[Chunk[Option[A]]] =
          q.dequeueBatch1(batchSize)
        def cancellableDequeueBatch1(batchSize: Int): F[(F[Chunk[Option[A]]],F[Unit])] =
          permits.increment >> q.cancellableDequeueBatch1(batchSize)
        def size = q.size
        def full: immutable.Signal[F, Boolean] = Signal.constant(true)
        def available: immutable.Signal[F, Int] = Signal.constant(0)
      }
    }}}
}
