package fs2
package concurrent

import cats.Functor
import cats.effect.{Concurrent, ExitCase}
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._

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
trait Dequeue[F[_], A] {

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

}

/**
  * A pure FIFO queue of elements. Operations are all nonblocking in their
  * implementations, but may be 'semantically' blocking. For instance,
  * a queue may have a bound on its size, in which case enqueuing may
  * block until there is an offsetting dequeue.
  */
trait Queue[F[_], A] extends Enqueue[F, A] with Dequeue[F, A] { self =>

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

object Queue {

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    Ref
      .of[F, State[F, A]](State(Vector.empty, Vector.empty, None))
      .map(new Unbounded(_))

  /*
   * Internal state of an unbounded queue.
   * @param queue    Queue, expressed as vector for fast cons/uncons from head/tail
   * @param deq      A list of waiting dequeuers, added to when queue is empty
   * @param peek     The waiting peekers (if any), created when queue is empty
   */
  private[concurrent] final case class State[F[_], A](
      queue: Vector[A],
      deq: Vector[(Token, Deferred[F, Chunk[A]])],
      peek: Option[Deferred[F, A]]
  )

  private[concurrent] class Unbounded[F[_], A](qref: Ref[F, State[F, A]])(implicit F: Concurrent[F])
      extends Queue[F, A] {
    protected def sizeChanged(s: State[F, A], ns: State[F, A]): F[Unit] = F.unit

    def enqueue1(a: A): F[Unit] = offer1(a).void

    def offer1(a: A): F[Boolean] =
      qref
        .modify { s =>
          val (newState, signalDequeuers) = s.deq match {
            case dequeuers if dequeuers.isEmpty =>
              // we enqueue a value to the queue
              val ns = s.copy(queue = s.queue :+ a, peek = None)
              ns -> sizeChanged(s, ns)
            case (_, firstDequeuer) +: dequeuers =>
              // we await the first dequeuer
              s.copy(deq = dequeuers, peek = None) -> F.start {
                firstDequeuer.complete(Chunk.singleton(a))
              }.void
          }

          val signalPeekers =
            s.peek.fold(F.unit)(p => F.start(p.complete(a)).void)

          newState -> (signalDequeuers *> signalPeekers)
        }
        .flatten
        .as(true)

    def dequeue1: F[A] = dequeueBatch1(1).map(_.head.get)

    def dequeue: Stream[F, A] =
      Stream
        .bracket(F.delay(new Token))(t =>
          qref.update(s => s.copy(deq = s.deq.filterNot(_._1 == t))))
        .flatMap(t => Stream.repeatEval(dequeueBatch1Impl(1, t).map(_.head.get)))

    def dequeueBatch: Pipe[F, Int, A] =
      batchSizes =>
        Stream
          .bracket(F.delay(new Token))(t =>
            qref.update(s => s.copy(deq = s.deq.filterNot(_._1 == t))))
          .flatMap(t =>
            batchSizes.flatMap(batchSize =>
              Stream.eval(dequeueBatch1Impl(batchSize, t)).flatMap(Stream.chunk(_))))

    def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
      dequeueBatch1Impl(batchSize, new Token)

    private def dequeueBatch1Impl(batchSize: Int, token: Token): F[Chunk[A]] =
      Deferred[F, Chunk[A]].flatMap { d =>
        qref.modify { s =>
          val newState =
            if (s.queue.isEmpty) s.copy(deq = s.deq :+ (token -> d))
            else s.copy(queue = s.queue.drop(batchSize))

          val cleanup =
            if (s.queue.nonEmpty) F.unit
            else qref.update(s => s.copy(deq = s.deq.filterNot(_._2 == d)))

          val dequeueBatch = sizeChanged(s, newState).flatMap { _ =>
            if (s.queue.nonEmpty) {
              if (batchSize == 1) Chunk.singleton(s.queue.head).pure[F]
              else Chunk.indexedSeq(s.queue.take(batchSize)).pure[F]
            } else
              F.guaranteeCase(d.get) {
                case ExitCase.Completed => F.unit
                case ExitCase.Error(t)  => cleanup *> F.raiseError(t)
                case ExitCase.Canceled  => cleanup *> F.unit
              }
          }

          newState -> dequeueBatch
        }.flatten
      }

  }

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield new Bounded(permits, q)

  private[concurrent] class Bounded[F[_], A](permits: Semaphore[F], q: Queue[F, A])(
      implicit F: Concurrent[F])
      extends Queue[F, A] {
    def enqueue1(a: A): F[Unit] =
      permits.acquire *> q.enqueue1(a)
    def offer1(a: A): F[Boolean] =
      permits.tryAcquire.flatMap { b =>
        if (b) q.offer1(a) else F.pure(false)
      }
    def dequeue1: F[A] = dequeueBatch1(1).map(_.head.get)
    def dequeue: Stream[F, A] = q.dequeue.evalMap(a => permits.release.as(a))
    def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
      q.dequeueBatch1(batchSize).flatMap { chunk =>
        permits.releaseN(chunk.size).as(chunk)
      }
    def dequeueBatch: Pipe[F, Int, A] =
      q.dequeueBatch.andThen(_.chunks.flatMap(c =>
        Stream.eval(permits.releaseN(c.size)).flatMap(_ => Stream.chunk(c))))
  }

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield new CircularBufer(permits, q)

  private[concurrent] class CircularBufer[F[_], A](permits: Semaphore[F], q: Queue[F, A])(
      implicit F: Concurrent[F])
      extends Queue[F, A] {
    def enqueue1(a: A): F[Unit] =
      permits.tryAcquire.flatMap { b =>
        if (b) q.enqueue1(a) else (q.dequeue1 *> q.enqueue1(a))
      }
    def offer1(a: A): F[Boolean] =
      enqueue1(a).as(true)
    def dequeue1: F[A] = dequeueBatch1(1).map(_.head.get)
    def dequeue: Stream[F, A] = q.dequeue.evalMap(a => permits.release.as(a))
    def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
      q.dequeueBatch1(batchSize).flatMap { chunk =>
        permits.releaseN(chunk.size).as(chunk)
      }
    def dequeueBatch: Pipe[F, Int, A] =
      q.dequeueBatch.andThen(_.chunks.flatMap(c =>
        Stream.eval(permits.releaseN(c.size)).flatMap(_ => Stream.chunk(c))))
  }

  /** Creates a queue which allows a single element to be enqueued at any time. */
  def synchronous[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    for {
      permits <- Semaphore(0)
      q <- unbounded[F, A]
    } yield new SynchronousQueue(permits, q)

  private class SynchronousQueue[F[_], A](permits: Semaphore[F], q: Queue[F, A])(
      implicit F: Concurrent[F])
      extends Queue[F, A] {
    def enqueue1(a: A): F[Unit] =
      permits.acquire *> q.enqueue1(a)
    def offer1(a: A): F[Boolean] =
      permits.tryAcquire.flatMap { b =>
        if (b) q.offer1(a) else F.pure(false)
      }
    def dequeue1: F[A] = permits.release *> q.dequeue1
    def dequeue: Stream[F, A] = {
      def loop(s: Stream[F, A]): Pull[F, A, Unit] =
        Pull.eval(permits.release) >> s.pull.uncons1.flatMap {
          case Some((h, t)) => Pull.output1(h) >> loop(t)
          case None         => Pull.done
        }
      loop(q.dequeue).stream
    }
    def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
      permits.release *> q.dequeueBatch1(batchSize)
    def dequeueBatch: Pipe[F, Int, A] = {
      def loop(s: Stream[F, A]): Pull[F, A, Unit] =
        Pull.eval(permits.release) >> s.pull.uncons1.flatMap {
          case Some((h, t)) => Pull.output1(h) >> loop(t)
          case None         => Pull.done
        }
      in =>
        loop(q.dequeueBatch(in)).stream
    }
  }

  /** Like [[synchronous]], except that an enqueue or offer of `None` will never block. */
  def synchronousNoneTerminated[F[_], A](implicit F: Concurrent[F]): F[Queue[F, Option[A]]] =
    for {
      permits <- Semaphore(0)
      doneRef <- Ref.of[F, Boolean](false)
      q <- unbounded[F, Option[A]]
    } yield new SynchronousNoneTerminated(permits, doneRef, q)

  private class SynchronousNoneTerminated[F[_], A](
      permits: Semaphore[F],
      doneRef: Ref[F, Boolean],
      q: Queue[F, Option[A]]
  )(implicit F: Concurrent[F])
      extends Queue[F, Option[A]] {
    def enqueue1(a: Option[A]): F[Unit] = doneRef.access.flatMap {
      case (done, update) =>
        if (done) F.unit
        else
          a match {
            case None =>
              update(true).flatMap { successful =>
                if (successful) q.enqueue1(None) else enqueue1(None)
              }
            case _ => permits.acquire *> q.enqueue1(a)
          }
    }
    def offer1(a: Option[A]): F[Boolean] = doneRef.access.flatMap {
      case (done, update) =>
        if (done) F.pure(true)
        else
          a match {
            case None =>
              update(true).flatMap { successful =>
                if (successful) q.offer1(None) else offer1(None)
              }
            case _ => permits.acquire *> q.offer1(a)
          }
    }
    def dequeue1: F[Option[A]] = permits.release *> q.dequeue1
    def dequeue: Stream[F, Option[A]] = {
      def loop(s: Stream[F, Option[A]]): Pull[F, Option[A], Unit] =
        Pull.eval(permits.release) >> s.pull.uncons1.flatMap {
          case Some((h, t)) => Pull.output1(h) >> loop(t)
          case None         => Pull.done
        }
      loop(q.dequeue).stream
    }
    def dequeueBatch1(batchSize: Int): F[Chunk[Option[A]]] =
      permits.release *> q.dequeueBatch1(batchSize)
    def dequeueBatch: Pipe[F, Int, Option[A]] = {
      def loop(s: Stream[F, Option[A]]): Pull[F, Option[A], Unit] =
        Pull.eval(permits.release) >> s.pull.uncons1.flatMap {
          case Some((h, t)) => Pull.output1(h) >> loop(t)
          case None         => Pull.done
        }
      in =>
        loop(q.dequeueBatch(in)).stream
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
  def size: Signal[F, Int]

  /** The size bound on the queue. `None` if the queue is unbounded. */
  def upperBound: Option[Int]

  /**
    * Returns the available number of entries in the queue.
    * Always `Int.MaxValue` when the queue is unbounded.
    */
  def available: Signal[F, Int]

  /**
    * Returns `true` when the queue has reached its upper size bound.
    * Always `false` when the queue is unbounded.
    */
  def full: Signal[F, Boolean]
}

object InspectableQueue {
  import Queue._

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    for {
      qref <- Ref.of[F, State[F, A]](State(Vector.empty, Vector.empty, None))
      szSignal <- SignallingRef(0)
    } yield
      new Unbounded(qref) with InspectableQueue[F, A] {
        override protected def sizeChanged(s: State[F, A], ns: State[F, A]): F[Unit] =
          if (s.queue.size != ns.queue.size) szSignal.set(ns.queue.size)
          else F.unit

        def upperBound = None

        def size = szSignal

        def full: Signal[F, Boolean] =
          Signal.constant[F, Boolean](false)

        def available: Signal[F, Int] =
          Signal.constant[F, Int](Int.MaxValue)

        def peek1: F[A] =
          Deferred[F, A].flatMap { d =>
            qref.modify { state =>
              val newState =
                if (state.queue.isEmpty && state.peek.isEmpty)
                  state.copy(peek = Some(d))
                else state

              val cleanup = qref.update { state =>
                if (state.peek == Some(d)) state.copy(peek = None) else state
              }

              val peekAction =
                state.queue.headOption.map(_.pure[F]).getOrElse {
                  F.guaranteeCase(newState.peek.get.get) {
                    case ExitCase.Completed => F.unit
                    case ExitCase.Error(t)  => cleanup *> F.raiseError(t)
                    case ExitCase.Canceled  => cleanup *> F.unit
                  }
                }

              newState -> peekAction
            }.flatten
          }
      }

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield
      new Bounded(permits, q) with InspectableQueue[F, A] {
        def upperBound: Option[Int] = Some(maxSize)
        def size = q.size
        def full: Signal[F, Boolean] = q.size.map(_ >= maxSize)
        def available: Signal[F, Int] = q.size.map(maxSize - _)
        def peek1: F[A] = q.peek1
      }

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield
      new CircularBufer(permits, q) with InspectableQueue[F, A] {
        def upperBound: Option[Int] = Some(maxSize)
        def size = q.size
        def full: Signal[F, Boolean] = q.size.map(_ >= maxSize)
        def available: Signal[F, Int] = q.size.map(maxSize - _)
        def peek1: F[A] = q.peek1
      }
}
