package fs2
package concurrent

import cats.{Applicative, Functor}
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import fs2.concurrent.pubsub._
import fs2.internal.Token

/** Provides the ability to enqueue elements to a `Queue`. */
trait Enqueue[F[_], A] {

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
}

/** Provides the ability to dequeue elements from a `Queue`. */
trait Dequeue1[F[_], A] {

  /** Dequeues one `A` from this queue. Completes once one is ready. */
  def dequeue1: F[A]

}

trait Dequeue[F[_], A] {

  /** Repeatedly calls `dequeue1` forever. */
  def dequeue: Stream[F, A]

  /** Calls `dequeueBatch1` once with a provided bound on the elements dequeued. */
  def dequeueBatch: Pipe[F, Int, A]

  /** Calls `dequeueBatch1` forever, with a bound of `Int.MaxValue` */
  def dequeueAvailable: Stream[F, A] =
    Stream.constant(Int.MaxValue).covary[F].through(dequeueBatch)
}

/**
  * A Queue of elements. Operations are all nonblocking in their
  * implementations, but may be 'semantically' blocking. For instance,
  * a queue may have a bound on its size, in which case enqueuing may
  * block (be delayed asynchronously) until there is an offsetting dequeue.
  */
trait Queue[F[_], A] extends Enqueue[F, A] with Dequeue1[F, A] with Dequeue[F, A] { self =>

  /** Dequeues at most `batchSize` `A`s from this queue. Completes once at least one value is ready. */
  def dequeueBatch1(batchSize: Int): F[Chunk[A]]

  /**
    * Returns an alternate view of this `Queue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Queue[F, B] =
    new Queue[F, B] {
      def enqueue1(a: B): F[Unit] = self.enqueue1(g(a))
      def offer1(a: B): F[Boolean] = self.offer1(g(a))
      def dequeue1: F[B] = self.dequeue1.map(f)
      def dequeue: Stream[F, B] = self.dequeue.map(f)
      def dequeueBatch1(batchSize: Int): F[Chunk[B]] =
        self.dequeueBatch1(batchSize).map(_.map(f))
      def dequeueBatch: Pipe[F, Int, B] =
        in => self.dequeueBatch(in).map(f)
    }
}

/**
  * Like Queue, but allows to signal no more elements arriving to queue by enqueueing `None`.
  * Optimizes dequeue to minimum possible boxing.
  */
trait NoneTerminatedQueue[F[_], A]
    extends Enqueue[F, Option[A]]
    with Dequeue1[F, Option[A]]
    with Dequeue[F, A] { self =>

  /** Dequeues at most `batchSize` `A`s from this queue. Completes once at least one value is ready. None signals the queue is closed. */
  def dequeueBatch1(batchSize: Int): F[Option[Chunk[A]]]

  /**
    * Returns an alternate view of this `NoneTerminatedQueue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): NoneTerminatedQueue[F, B] =
    new NoneTerminatedQueue[F, B] {
      def enqueue1(a: Option[B]): F[Unit] = self.enqueue1(a.map(g))
      def offer1(a: Option[B]): F[Boolean] = self.offer1(a.map(g))
      def dequeue1: F[Option[B]] = self.dequeue1.map(_.map(f))
      def dequeue: Stream[F, B] = self.dequeue.map(f)
      def dequeueBatch1(batchSize: Int): F[Option[Chunk[B]]] =
        self.dequeueBatch1(batchSize).map(_.map(_.map(f)))
      def dequeueBatch: Pipe[F, Int, B] =
        in => self.dequeueBatch(in).map(f)
    }
}

object Queue {

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(UnboundedQueue.strategy[A])

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(BoundedQueue.strategy(maxSize))

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(UnboundedQueue.circularBuffer(maxSize))

  /** Creates a queue which allows a single element to be enqueued at any time. */
  def synchronous[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(BoundedQueue.synchronous)

  /** Like [[synchronous]], except that any enqueue of `None` will never block and cancels any dequeue operation */
  def synchronousNoneTerminated[F[_], A](implicit F: Concurrent[F]): F[NoneTerminatedQueue[F, A]] =
    forStrategyNoneTerminated(NoneTerminated.closeNow(BoundedQueue.synchronous))

  /** creates queue from supplied strategy **/
  def forStrategy[F[_]: Concurrent, S, A](
      strategy: PubSubStrategy[A, Chunk[A], S, Int]): F[Queue[F, A]] =
    PubSub(strategy).map { pubSub =>
      new Queue[F, A] {
        def enqueue1(a: A): F[Unit] = pubSub.publish(a)
        def offer1(a: A): F[Boolean] = pubSub.tryPublish(a)
        def dequeue1: F[A] = pubSub.get(1).flatMap { chunk =>
          if (chunk.size == 1) Applicative[F].pure(chunk(0))
          else Sync[F].raiseError(new Throwable(s"Expected chunk of size 1. got $chunk"))
        }
        def dequeueBatch1(batchSize: Int): F[Chunk[A]] = pubSub.get(batchSize)
        def dequeueMax(maxSize: Int): Stream[F, A] =
          Stream.evalUnChunk(pubSub.get(maxSize)).repeat
        def dequeue: Stream[F, A] = dequeueMax(1)
        def dequeueBatch: Pipe[F, Int, A] =
          _.evalMap(dequeueBatch1).flatMap(Stream.chunk)
      }
    }

  /** creates queue that is terminated by enqueueing None for supplied strategy **/
  def forStrategyNoneTerminated[F[_]: Concurrent, S, A](
      strategy: PubSubStrategy[Option[A], Option[Chunk[A]], S, Int]): F[NoneTerminatedQueue[F, A]] =
    PubSub(strategy).map { pubSub =>
      new NoneTerminatedQueue[F, A] {
        def dequeueBatch1(batchSize: Int): F[Option[Chunk[A]]] =
          pubSub.get(batchSize)
        def enqueue1(a: Option[A]): F[Unit] =
          pubSub.publish(a)
        def offer1(a: Option[A]): F[Boolean] =
          pubSub.tryPublish(a)
        def dequeueMax(maxSize: Int): Stream[F, A] =
          Stream.repeatEval(pubSub.get(maxSize)).unNoneTerminate.flatMap(Stream.chunk)
        def dequeue: Stream[F, A] =
          dequeueMax(1)
        def dequeueBatch: Pipe[F, Int, A] =
          _.evalMap(pubSub.get).unNoneTerminate.flatMap(Stream.chunk)
        def dequeue1: F[Option[A]] =
          pubSub.get(1).flatMap {
            case None => Applicative[F].pure(None)
            case Some(chunk) =>
              if (chunk.size == 1) Applicative[F].pure(Some(chunk(0)))
              else Sync[F].raiseError(new Throwable(s"Expected chunk of size 1. got $chunk"))
          }
      }
    }

}

trait InspectableQueue[F[_], A] extends Queue[F, A] {

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
  def size: Stream[F, Int]

  /** gets current size of the queue **/
  def getSize: F[Int]

}

object InspectableQueue {

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    forStrategy(UnboundedQueue.strategy[A])(_.headOption)(_.size)

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    forStrategy(BoundedQueue.strategy[A](maxSize))(_.headOption)(_.size)

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    forStrategy(UnboundedQueue.circularBuffer[A](maxSize))(_.headOption)(_.size)

  def forStrategy[F[_]: Concurrent, S, A](
      strategy: PubSubStrategy[A, Chunk[A], S, Int]
  )(
      headOf: S => Option[A]
  )(
      sizeOf: S => Int
  ): F[InspectableQueue[F, A]] =
    PubSub(Inspectable.strategy(strategy)).map { pubSub =>
      new InspectableQueue[F, A] {
        def enqueue1(a: A): F[Unit] = pubSub.publish(a)
        def offer1(a: A): F[Boolean] = pubSub.tryPublish(a)
        def dequeue1: F[A] = pubSub.get(Right(1)).flatMap {
          case Left(s) =>
            Sync[F].raiseError(new Throwable(
              s"Inspectable `dequeue1` requires chunk of size 1 with `A` got Left($s)"))
          case Right(chunk) =>
            if (chunk.size == 1) Applicative[F].pure(chunk(0))
            else Sync[F].raiseError(new Throwable(s"Expected chunk of size 1. got $chunk"))

        }
        def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
          pubSub.get(Right(batchSize)).map {
            _.right.toOption.getOrElse(Chunk.empty)
          }
        def dequeueMax(maxSize: Int): Stream[F, A] =
          Stream
            .evalUnChunk(
              pubSub.get(Right(maxSize)).map { _.right.toOption.getOrElse(Chunk.empty) }
            )
            .repeat

        def dequeue: Stream[F, A] = dequeueMax(1)
        def dequeueBatch: Pipe[F, Int, A] =
          _.evalMap(dequeueBatch1).flatMap(Stream.chunk)

        def peek1: F[A] =
          Sync[F].bracket(Sync[F].delay(new Token))({ token =>
            def take: F[A] =
              pubSub.get(Left(Some(token))).flatMap {
                case Left(s) =>
                  headOf(s) match {
                    case None    => take
                    case Some(a) => Applicative[F].pure(a)
                  }

                case Right(chunk) =>
                  Sync[F].raiseError(new Throwable(
                    s"Inspectable `peek1` requires chunk of size 1 with state, got: $chunk"))
              }
            take
          })(token => pubSub.unsubscribe(Left(Some(token))))

        def size: Stream[F, Int] =
          Stream
            .bracket(Sync[F].delay(new Token))(token => pubSub.unsubscribe(Left(Some(token))))
            .flatMap { token =>
              Stream.repeatEval(pubSub.get(Left(Some(token)))).flatMap {
                case Left(s)  => Stream.emit(sizeOf(s))
                case Right(_) => Stream.empty // impossible
              }
            }

        def getSize: F[Int] =
          pubSub.get(Left(None)).map {
            case Left(s)  => sizeOf(s)
            case Right(_) => -1
          }
      }
    }

}
