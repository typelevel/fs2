package fs2
package concurrent

import cats.{Applicative, Eq, Functor, Id}
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import fs2.internal.{SizedQueue, Token}

/** Provides the ability to enqueue elements to a `Queue`. */
trait Enqueue[F[_], A] {

  /**
    * Enqueues one element to this `Queue`.
    * If the queue is `full` this waits until queue has space.
    *
    * This completes after `a`  has been successfully enqueued to this `Queue`
    */
  def enqueue1(a: A): F[Unit]

  /**
    * Enqueues each element of the input stream to this queue by
    * calling `enqueue1` on each element.
    */
  def enqueue: Pipe[F, A, Unit] = _.evalMap(enqueue1)

  /**
    * Offers one element to this `Queue`.
    *
    * Evaluates to `false` if the queue is full, indicating the `a` was not queued up.
    * Evaluates to `true` if the `a` was queued up successfully.
    *
    * @param a `A` to enqueue
    */
  def offer1(a: A): F[Boolean]
}

/** Provides the ability to dequeue individual elements from a `Queue`. */
trait Dequeue1[F[_], A] {

  /** Dequeues one `A` from this queue. Completes once one is ready. */
  def dequeue1: F[A]

  /**
    * Tries to dequeue a single element. Unlike `dequeue1`, this method does not semantically
    * block until a chunk is available - instead, `None` is returned immediately.
    */
  def tryDequeue1: F[Option[A]]
}

/** Provides the ability to dequeue individual chunks from a `Queue`. */
trait DequeueChunk1[F[_], G[_], A] {

  /** Dequeues one `Chunk[A]` with no more than `maxSize` elements. Completes once one is ready. */
  def dequeueChunk1(maxSize: Int): F[G[Chunk[A]]]

  /**
    * Tries to dequeue a single chunk of no more than `max size` elements.
    * Unlike `dequeueChunk1`, this method does not semantically block until a chunk is available -
    * instead, `None` is returned immediately.
    */
  def tryDequeueChunk1(maxSize: Int): F[Option[G[Chunk[A]]]]
}

/** Provides the ability to dequeue chunks of elements from a `Queue` as streams. */
trait Dequeue[F[_], A] {

  /** Dequeues elements from the queue. */
  def dequeue: Stream[F, A] =
    dequeueChunk(Int.MaxValue)

  /** Dequeues elements from the queue, ensuring elements are dequeued in chunks not exceeding `maxSize`. */
  def dequeueChunk(maxSize: Int): Stream[F, A]

  /**
    * Provides a pipe that converts a stream of batch sizes in to a stream of elements by dequeuing
    * batches of the specified size.
    */
  def dequeueBatch: Pipe[F, Int, A]
}

/**
  * A queue of elements. Operations are all nonblocking in their
  * implementations, but may be 'semantically' blocking. For instance,
  * a queue may have a bound on its size, in which case enqueuing may
  * block (be delayed asynchronously) until there is an offsetting dequeue.
  */
trait Queue[F[_], A]
    extends Enqueue[F, A]
    with Dequeue1[F, A]
    with DequeueChunk1[F, Id, A]
    with Dequeue[F, A] { self =>

  /**
    * Returns an alternate view of this `Queue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Queue[F, B] =
    new Queue[F, B] {
      def enqueue1(a: B): F[Unit] = self.enqueue1(g(a))
      def offer1(a: B): F[Boolean] = self.offer1(g(a))
      def dequeue1: F[B] = self.dequeue1.map(f)
      def tryDequeue1: F[Option[B]] = self.tryDequeue1.map(_.map(f))
      def dequeueChunk1(maxSize: Int): F[Chunk[B]] = self.dequeueChunk1(maxSize).map(_.map(f))
      def tryDequeueChunk1(maxSize: Int): F[Option[Chunk[B]]] =
        self.tryDequeueChunk1(maxSize).map(_.map(_.map(f)))
      def dequeueChunk(maxSize: Int): Stream[F, B] = self.dequeueChunk(maxSize).map(f)
      def dequeueBatch: Pipe[F, Int, B] = self.dequeueBatch.andThen(_.map(f))
    }
}

/**
  * Like [[Queue]], but allows allows signalling of no further enqueues by enqueueing `None`.
  * Optimizes dequeue to minimum possible boxing.
  */
trait NoneTerminatedQueue[F[_], A]
    extends Enqueue[F, Option[A]]
    with Dequeue1[F, Option[A]]
    with DequeueChunk1[F, Option, A]
    with Dequeue[F, A] { self =>

  /**
    * Returns an alternate view of this `NoneTerminatedQueue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): NoneTerminatedQueue[F, B] =
    new NoneTerminatedQueue[F, B] {
      def enqueue1(a: Option[B]): F[Unit] = self.enqueue1(a.map(g))
      def offer1(a: Option[B]): F[Boolean] = self.offer1(a.map(g))
      def dequeue1: F[Option[B]] = self.dequeue1.map(_.map(f))
      def tryDequeue1: F[Option[Option[B]]] = self.tryDequeue1.map(_.map(_.map(f)))
      def dequeueChunk1(maxSize: Int) =
        self.dequeueChunk1(maxSize).map(_.map(_.map(f)))
      def tryDequeueChunk1(maxSize: Int) =
        self.tryDequeueChunk1(maxSize).map(_.map(_.map(_.map(f))))
      def dequeueChunk(maxSize: Int): Stream[F, B] = self.dequeueChunk(maxSize).map(f)
      def dequeueBatch: Pipe[F, Int, B] = self.dequeueBatch.andThen(_.map(f))
    }
}

object Queue {
  final class InPartiallyApplied[G[_]](val G: Sync[G]) extends AnyVal {

    /** Creates a queue with no size bound. */
    def unbounded[F[_], A](implicit F: Concurrent[F]): G[Queue[F, A]] =
      forStrategy(Strategy.fifo[A])

    /** Creates an unbounded queue that distributed always at max `fairSize` elements to any subscriber. */
    def fairUnbounded[F[_], A](fairSize: Int)(implicit F: Concurrent[F]): G[Queue[F, A]] =
      forStrategy(Strategy.fifo[A].transformSelector[Int]((sz, _) => sz.min(fairSize)))

    /** Creates a queue with the specified size bound. */
    def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): G[Queue[F, A]] =
      forStrategy(Strategy.boundedFifo(maxSize))

    /** Creates a bounded queue terminated by enqueueing `None`. All elements before `None` are preserved. */
    def boundedNoneTerminated[F[_], A](maxSize: Int)(
        implicit F: Concurrent[F]): G[NoneTerminatedQueue[F, A]] =
      forStrategyNoneTerminated(PubSub.Strategy.closeDrainFirst(Strategy.boundedFifo(maxSize)))

    /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
    def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): G[Queue[F, A]] =
      forStrategy(Strategy.circularBuffer(maxSize))

    /** Created a bounded queue that distributed always at max `fairSize` elements to any subscriber. */
    def fairBounded[F[_], A](maxSize: Int, fairSize: Int)(
        implicit F: Concurrent[F]): G[Queue[F, A]] =
      forStrategy(Strategy.boundedFifo(maxSize).transformSelector[Int]((sz, _) => sz.min(fairSize)))

    /** Created an unbounded queue terminated by enqueueing `None`. All elements before `None`. */
    def noneTerminated[F[_], A](implicit F: Concurrent[F]): G[NoneTerminatedQueue[F, A]] =
      forStrategyNoneTerminated(PubSub.Strategy.closeDrainFirst(Strategy.fifo))

    /** Creates a queue which allows at most a single element to be enqueued at any time. */
    def synchronous[F[_], A](implicit F: Concurrent[F]): G[Queue[F, A]] =
      forStrategy(Strategy.synchronous)

    /** Like [[synchronous]], except that any enqueue of `None` will never block and cancels any dequeue operation. */
    def synchronousNoneTerminated[F[_], A](
        implicit F: Concurrent[F]): G[NoneTerminatedQueue[F, A]] =
      forStrategyNoneTerminated(PubSub.Strategy.closeNow(Strategy.synchronous))

    /** Creates a queue from the supplied strategy. */
    private[fs2] def forStrategy[F[_]: Concurrent, S, A](
        strategy: PubSub.Strategy[A, Chunk[A], S, Int]): G[Queue[F, A]] = {
      implicit val SyncG: Sync[G] = G
      PubSub.in[G].from(strategy).map { pubSub =>
        new Queue[F, A] {

          def enqueue1(a: A): F[Unit] =
            pubSub.publish(a)

          def offer1(a: A): F[Boolean] =
            pubSub.tryPublish(a)

          def dequeue1: F[A] =
            pubSub.get(1).flatMap(headUnsafe[F, A])

          def tryDequeue1: F[Option[A]] = pubSub.tryGet(1).flatMap {
            case Some(chunk) => headUnsafe[F, A](chunk).map(Some(_))
            case None        => Applicative[F].pure(None)
          }

          def dequeueChunk1(maxSize: Int): F[Chunk[A]] =
            pubSub.get(maxSize)

          def tryDequeueChunk1(maxSize: Int): F[Option[Chunk[A]]] =
            pubSub.tryGet(maxSize)

          def dequeueChunk(maxSize: Int): Stream[F, A] =
            pubSub.getStream(maxSize).flatMap(Stream.chunk)

          def dequeueBatch: Pipe[F, Int, A] =
            _.flatMap { sz =>
              Stream.evalUnChunk(pubSub.get(sz))
            }
        }
      }
    }

    /** Creates a queue that is terminated by enqueueing `None` from the supplied strategy. */
    private[fs2] def forStrategyNoneTerminated[F[_]: Concurrent, S, A](
        strategy: PubSub.Strategy[Option[A], Option[Chunk[A]], S, Int])
      : G[NoneTerminatedQueue[F, A]] = {
      implicit val SyncG: Sync[G] = G
      PubSub.in[G].from(strategy).map { pubSub =>
        new NoneTerminatedQueue[F, A] {
          def enqueue1(a: Option[A]): F[Unit] =
            pubSub.publish(a)

          def offer1(a: Option[A]): F[Boolean] =
            pubSub.tryPublish(a)

          def dequeueChunk(maxSize: Int): Stream[F, A] =
            pubSub
              .getStream(maxSize)
              .unNoneTerminate
              .flatMap(Stream.chunk)

          def dequeueBatch: Pipe[F, Int, A] =
            _.evalMap(pubSub.get).unNoneTerminate
              .flatMap(Stream.chunk)

          def tryDequeue1: F[Option[Option[A]]] =
            pubSub.tryGet(1).flatMap {
              case None              => Applicative[F].pure(None)
              case Some(None)        => Applicative[F].pure(Some(None))
              case Some(Some(chunk)) => headUnsafe[F, A](chunk).map(a => Some(Some(a)))
            }

          def dequeueChunk1(maxSize: Int): F[Option[Chunk[A]]] =
            pubSub.get(maxSize)

          def tryDequeueChunk1(maxSize: Int): F[Option[Option[Chunk[A]]]] =
            pubSub.tryGet(maxSize)

          def dequeue1: F[Option[A]] =
            pubSub.get(1).flatMap {
              case None        => Applicative[F].pure(None)
              case Some(chunk) => headUnsafe[F, A](chunk).map(Some(_))
            }
        }
      }
    }
  }

  /**
    * Provides constructors for Queue with state initialized using
    * another `Sync` datatype.
    *
    * This method uses the [[http://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially Applied Type Params technique]]
    *
    * {{{
    *   val queue = Queue.in[SyncIO].unbounded[IO, String]
    * }}}
    */
  def in[G[_]](implicit G: Sync[G]) = new InPartiallyApplied(G)

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    in[F].unbounded

  /** Creates an unbounded queue that distributed always at max `fairSize` elements to any subscriber. */
  def fairUnbounded[F[_], A](fairSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    in[F].fairUnbounded(fairSize)

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    in[F].bounded(maxSize)

  /** Creates a bounded queue terminated by enqueueing `None`. All elements before `None` are preserved. */
  def boundedNoneTerminated[F[_], A](maxSize: Int)(
      implicit F: Concurrent[F]): F[NoneTerminatedQueue[F, A]] =
    in[F].boundedNoneTerminated(maxSize)

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    in[F].circularBuffer(maxSize)

  /** Created a bounded queue that distributed always at max `fairSize` elements to any subscriber. */
  def fairBounded[F[_], A](maxSize: Int, fairSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    in[F].fairBounded(maxSize, fairSize)

  /** Created an unbounded queue terminated by enqueueing `None`. All elements before `None`. */
  def noneTerminated[F[_], A](implicit F: Concurrent[F]): F[NoneTerminatedQueue[F, A]] =
    in[F].noneTerminated

  /** Creates a queue which allows at most a single element to be enqueued at any time. */
  def synchronous[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    in[F].synchronous

  /** Like [[synchronous]], except that any enqueue of `None` will never block and cancels any dequeue operation. */
  def synchronousNoneTerminated[F[_], A](implicit F: Concurrent[F]): F[NoneTerminatedQueue[F, A]] =
    in[F].synchronousNoneTerminated

  private[fs2] def headUnsafe[F[_]: Sync, A](chunk: Chunk[A]): F[A] =
    if (chunk.size == 1) Applicative[F].pure(chunk(0))
    else Sync[F].raiseError(new Throwable(s"Expected chunk of size 1. got $chunk"))

  private[fs2] object Strategy {

    /** Unbounded fifo strategy. */
    def boundedFifo[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.bounded(maxSize)(fifo[A])(_.size)

    /** Unbounded lifo strategy. */
    def boundedLifo[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.bounded(maxSize)(lifo[A])(_.size)

    /** Strategy for circular buffer, which stores the last `maxSize` enqueued elements and never blocks on enqueue. */
    def circularBuffer[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      unbounded { (q, a) =>
        if (q.size < maxSize) q :+ a
        else q.tail :+ a
      }

    /** Unbounded lifo strategy. */
    def lifo[A]: PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] = unbounded((q, a) => a +: q)

    /** Unbounded fifo strategy. */
    def fifo[A]: PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] = unbounded(_ :+ _)

    /**
      * Strategy that allows at most a single element to be published.
      * Before the `A` is published successfully, at least one subscriber must be ready to consume.
      */
    def synchronous[A]: PubSub.Strategy[A, Chunk[A], (Boolean, Option[A]), Int] =
      new PubSub.Strategy[A, Chunk[A], (Boolean, Option[A]), Int] {
        def initial: (Boolean, Option[A]) = (false, None)

        def accepts(i: A, queueState: (Boolean, Option[A])): Boolean =
          queueState._1 && queueState._2.isEmpty

        def publish(i: A, queueState: (Boolean, Option[A])): (Boolean, Option[A]) =
          (queueState._1, Some(i))

        def get(selector: Int,
                queueState: (Boolean, Option[A])): ((Boolean, Option[A]), Option[Chunk[A]]) =
          queueState._2 match {
            case None    => ((true, None), None)
            case Some(a) => ((false, None), Some(Chunk.singleton(a)))
          }

        def empty(queueState: (Boolean, Option[A])): Boolean =
          queueState._2.isEmpty

        def subscribe(selector: Int,
                      queueState: (Boolean, Option[A])): ((Boolean, Option[A]), Boolean) =
          (queueState, false)

        def unsubscribe(selector: Int, queueState: (Boolean, Option[A])): (Boolean, Option[A]) =
          queueState
      }

    /**
      * Creates unbounded queue strategy for `A` with configurable append function.
      *
      * @param append function used to append new elements to the queue
      */
    def unbounded[A](append: (SizedQueue[A], A) => SizedQueue[A])
      : PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      new PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] {

        val initial: SizedQueue[A] = SizedQueue.empty

        def publish(a: A, queueState: SizedQueue[A]): SizedQueue[A] =
          append(queueState, a)

        def accepts(i: A, queueState: SizedQueue[A]): Boolean =
          true

        def empty(queueState: SizedQueue[A]): Boolean =
          queueState.isEmpty

        def get(selector: Int, queueState: SizedQueue[A]): (SizedQueue[A], Option[Chunk[A]]) =
          if (queueState.isEmpty) (queueState, None)
          else {
            val (out, rem) = Chunk.queueFirstN(queueState.toQueue, selector)
            (new SizedQueue(rem, (queueState.size - selector).max(0)), Some(out))
          }

        def subscribe(selector: Int, queueState: SizedQueue[A]): (SizedQueue[A], Boolean) =
          (queueState, false)

        def unsubscribe(selector: Int, queueState: SizedQueue[A]): SizedQueue[A] =
          queueState
      }

  }
}

/** Extension of [[Queue]] that allows peeking and inspection of the current size. */
trait InspectableQueue[F[_], A] extends Queue[F, A] {

  /**
    * Returns the element which would be dequeued next,
    * but without removing it. Completes when such an
    * element is available.
    */
  def peek1: F[A]

  /**
    * The time-varying size of this `Queue`.
    * Emits elements describing the current size of the queue.
    * Offsetting enqueues and de-queues may not result in refreshes.
    *
    * Finally, note that operations like `dequeue` are optimized to
    * work on chunks when possible, which will result in faster
    * decreases in size that one might expect.
    * More granular updates can be achieved by calling `dequeue1`
    * repeatedly, but this is less efficient than dequeueing in
    * batches.
    */
  def size: Stream[F, Int]

  /** Gets the current size of the queue. */
  def getSize: F[Int]
}

object InspectableQueue {

  final class InPartiallyApplied[G[_]](val G: Sync[G]) extends AnyVal {

    /** Creates a queue with no size bound. */
    def unbounded[F[_], A](implicit F: Concurrent[F]): G[InspectableQueue[F, A]] =
      forStrategy(Queue.Strategy.fifo[A])(_.headOption)(_.size)

    /** Creates a queue with the specified size bound. */
    def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): G[InspectableQueue[F, A]] =
      forStrategy(Queue.Strategy.boundedFifo[A](maxSize))(_.headOption)(_.size)

    /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
    def circularBuffer[F[_], A](maxSize: Int)(
        implicit F: Concurrent[F]): G[InspectableQueue[F, A]] =
      forStrategy(Queue.Strategy.circularBuffer[A](maxSize))(_.headOption)(_.size)

    private[fs2] def forStrategy[F[_]: Concurrent, S, A](
        strategy: PubSub.Strategy[A, Chunk[A], S, Int]
    )(
        headOf: S => Option[A]
    )(
        sizeOf: S => Int
    ): G[InspectableQueue[F, A]] = {
      implicit val SyncG: Sync[G] = G
      implicit def eqInstance: Eq[S] = Eq.fromUniversalEquals[S]
      PubSub.in[G].from(PubSub.Strategy.Inspectable.strategy(strategy)).map { pubSub =>
        new InspectableQueue[F, A] {
          def enqueue1(a: A): F[Unit] = pubSub.publish(a)

          def offer1(a: A): F[Boolean] = pubSub.tryPublish(a)

          def dequeue1: F[A] = pubSub.get(Right(1)).flatMap {
            case Left(s) =>
              Sync[F].raiseError(new Throwable(
                s"Inspectable `dequeue1` requires chunk of size 1 with `A` got Left($s)"))
            case Right(chunk) =>
              Queue.headUnsafe[F, A](chunk)

          }

          def tryDequeue1: F[Option[A]] = pubSub.tryGet(Right(1)).flatMap {
            case None => Applicative[F].pure(None)
            case Some(Left(s)) =>
              Sync[F].raiseError(new Throwable(
                s"Inspectable `dequeue1` requires chunk of size 1 with `A` got Left($s)"))
            case Some(Right(chunk)) =>
              Queue.headUnsafe[F, A](chunk).map(Some(_))
          }

          def dequeueChunk1(maxSize: Int): F[Chunk[A]] =
            pubSub.get(Right(maxSize)).map(_.toOption.getOrElse(Chunk.empty))

          def tryDequeueChunk1(maxSize: Int): F[Option[Chunk[A]]] =
            pubSub.tryGet(Right(maxSize)).map(_.map(_.toOption.getOrElse(Chunk.empty)))

          def dequeueChunk(maxSize: Int): Stream[F, A] =
            pubSub.getStream(Right(maxSize)).flatMap {
              case Left(_)      => Stream.empty
              case Right(chunk) => Stream.chunk(chunk)
            }

          def dequeueBatch: Pipe[F, Int, A] =
            _.flatMap { sz =>
              Stream
                .evalUnChunk(
                  pubSub.get(Right(sz)).map {
                    _.toOption.getOrElse(Chunk.empty)
                  }
                )
            }

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
                      s"Inspectable `peek1` requires state to be returned, got: $chunk"))
                }

              take
            })(token => pubSub.unsubscribe(Left(Some(token))))

          def size: Stream[F, Int] =
            Stream
              .bracket(Sync[F].delay(new Token))(token => pubSub.unsubscribe(Left(Some(token))))
              .flatMap { token =>
                pubSub.getStream(Left(Some(token))).flatMap {
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
  }

  /**
    * Provides constructors for InspectableQueue with state initialized using
    * another `Sync` datatype.
    *
    * This method uses the [[http://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially Applied Type Params technique]]
    *
    * {{{
    *   val queue = InspectableQueue.in[SyncIO].unbounded[IO, String]
    * }}}
    */
  def in[G[_]](implicit G: Sync[G]) = new InPartiallyApplied(G)

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    in[F].unbounded

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    in[F].bounded(maxSize)

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    in[F].circularBuffer(maxSize)
}
