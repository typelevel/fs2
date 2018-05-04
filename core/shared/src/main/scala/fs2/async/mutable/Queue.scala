package fs2
package async
package mutable

import java.util.concurrent.{ArrayBlockingQueue, LinkedBlockingQueue}

import cats.Functor
import cats.effect.Sync
import cats.implicits._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
  * A pure queue interface. Operations may or may not be asynchronous. For instance,
  * a queue may have a bound on its size, in which case enqueuing may
  * block until there is an offsetting dequeue.
  */
abstract class Queue[F[_], A] { self =>

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

  /** Dequeues one `A` from this queue. Completes once one is ready. */
  def dequeue1: F[A]

  /** Dequeues at most `batchSize` `A`s from this queue. Completes once at least one value is ready. */
  def dequeueBatch1(batchSize: Int): F[Chunk[A]]

  /** Repeatedly calls `dequeue1` forever. */
  def dequeue: Stream[F, A]

  /** Calls `dequeueBatch1` once with a provided bound on the elements dequeued. */
  def dequeueBatch: Pipe[F, Int, A]

  /** Calls `dequeueBatch1` forever, with a bound of `Int.MaxValue` */
  def dequeueAvailable: Stream[F, A] =
    Stream.constant(Int.MaxValue).covary[F].through(dequeueBatch)

  /**
    * Returns the element which would be dequeued next,
    * but without removing it. Completes when such an
    * element is available.
    */
  def peek1: F[A]

  /**
    * The time-varying size of this `Queue`. This signal refreshes
    * only when size changes. Offsetting enqueues and de-queues may
    * not result in refreshes.
    */
  def size: F[Int]

  /** The size bound on the queue. `None` if the queue is unbounded. */
  def upperBound: Option[Int]

  /**
    * Returns the available number of entries in the queue.
    * Always `Int.MaxValue` when the queue is unbounded.
    */
  def available: F[Int]

  /**
    * Returns `true` when the queue has reached its upper size bound.
    * Always `false` when the queue is unbounded.
    */
  def full: F[Boolean]

  /**
    * Returns an alternate view of this `Queue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Queue[F, B] =
    new Queue[F, B] {
      def size: F[Int] = self.size
      def available: F[Int] = self.available
      def full: F[Boolean] = self.full
      def upperBound: Option[Int] = self.upperBound
      def enqueue1(a: B): F[Unit] = self.enqueue1(g(a))
      def offer1(a: B): F[Boolean] = self.offer1(g(a))
      def dequeue1: F[B] = self.dequeue1.map(f)
      def dequeue: Stream[F, B] = self.dequeue.map(f)
      def dequeueBatch1(batchSize: Int): F[Chunk[B]] =
        self.dequeueBatch1(batchSize).map(_.map(f))
      def dequeueBatch: Pipe[F, Int, B] =
        in => self.dequeueBatch(in).map(f)
      def peek1: F[B] = self.peek1.map(f)
    }
}

object Queue {

  /** A pure analog of java's `ArrayBlockingQueue`. Operations are all synchronous,
    * thus not cancellable.
    *
    * @param queueSize the queue's size bound
    * @param fair if set, elements are pulled by blocked threads in the order
    *             they were requested
    * @return
    */
  def synchronousBounded[F[_], A](queueSize: Int, fair: Boolean = true)(
      implicit F: Sync[F]): F[Queue[F, A]] =
    F.delay {
      val internalQueue = new ArrayBlockingQueue[A](queueSize, fair)

      new Queue[F, A] {
        def enqueue1(a: A): F[Unit] = F.delay(internalQueue.put(a))

        def offer1(a: A): F[Boolean] = F.delay(internalQueue.offer(a))

        def dequeue1: F[A] = F.delay(internalQueue.take())

        def dequeueBatch1(batchSize: Int): F[Chunk[A]] = F.delay {
          val listBuffer = new ListBuffer[A]
          val j = listBuffer.asJava
          internalQueue.drainTo(j, batchSize)
          Chunk.seq(listBuffer.toList)
        }

        def dequeue: Stream[F, A] = Stream.repeatEval(dequeue1)

        def dequeueBatch: Pipe[F, Int, A] = _.flatMap { i =>
          Stream.eval(dequeueBatch1(i)).flatMap(Stream.chunk(_))
        }

        def peek1: F[A] = {
          @tailrec def basicSpin(a: A): A =
            if (a == null) basicSpin(internalQueue.peek())
            else a

          F.delay(basicSpin(internalQueue.peek()))
        }

        def size: F[Int] = F.delay(internalQueue.size())

        val upperBound: Option[Int] = Some(queueSize)

        def available: F[Int] = F.delay(internalQueue.remainingCapacity())

        def full: F[Boolean] = F.delay(internalQueue.remainingCapacity() == 0)
      }
    }

  /** A pure analog of java's `LinkedBlockingQueue`. Operations are all synchronous,
    * thus not cancellable.
    *
    * @return
    */
  def synchronousLinked[F[_], A](implicit F: Sync[F]): F[Queue[F, A]] = F.delay {
    val internalQueue = new LinkedBlockingQueue[A]()
    new Queue[F, A] {
      def enqueue1(a: A): F[Unit] = F.delay(internalQueue.put(a))

      def offer1(a: A): F[Boolean] = F.delay(internalQueue.offer(a))

      def dequeue1: F[A] = F.delay(internalQueue.take())

      def dequeueBatch1(batchSize: Int): F[Chunk[A]] = F.delay {
        val listBuffer = new ListBuffer[A]
        val j = listBuffer.asJava
        internalQueue.drainTo(j, batchSize)
        Chunk.seq(listBuffer.toList)
      }

      def dequeue: Stream[F, A] = Stream.repeatEval(dequeue1)

      def dequeueBatch: Pipe[F, Int, A] = _.flatMap { i =>
        Stream.eval(dequeueBatch1(i)).flatMap(Stream.chunk(_))
      }

      def peek1: F[A] = {
        @tailrec def basicSpin(a: A): A =
          if (a == null) basicSpin(internalQueue.peek())
          else a

        F.delay(basicSpin(internalQueue.peek()))
      }

      def size: F[Int] = F.delay(internalQueue.size())

      def upperBound: Option[Int] = Some(Int.MaxValue)

      def available: F[Int] = F.delay(internalQueue.remainingCapacity())

      def full: F[Boolean] = F.delay(internalQueue.remainingCapacity() == 0)
    }

  }
}
