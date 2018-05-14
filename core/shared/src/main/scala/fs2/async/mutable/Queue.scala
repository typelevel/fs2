package fs2
package async
package mutable

import scala.concurrent.ExecutionContext

import cats.Functor
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._

import fs2.internal.{Canceled, Token}

/**
  * Asynchronous queue interface. Operations are all nonblocking in their
  * implementations, but may be 'semantically' blocking. For instance,
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
    * Returns an alternate view of this `Queue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
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
      def dequeue: Stream[F, B] = self.dequeue.map(f)
      def dequeueBatch1(batchSize: Int): F[Chunk[B]] =
        self.dequeueBatch1(batchSize).map(_.map(f))
      def dequeueBatch: Pipe[F, Int, B] =
        in => self.dequeueBatch(in).map(f)
      def peek1: F[B] = self.peek1.map(f)
    }
}

object Queue {

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F], ec: ExecutionContext): F[Queue[F, A]] = {
    /*
     * Internal state of the queue
     * @param queue    Queue, expressed as vector for fast cons/uncons from head/tail
     * @param deq      A list of waiting dequeuers, added to when queue is empty
     * @param peek     The waiting peekers (if any), created when queue is empty
     */
    final case class State(
        queue: Vector[A],
        deq: Vector[(Token, Deferred[F, Chunk[A]])],
        peek: Option[Deferred[F, A]]
    )

    for {
      szSignal <- Signal(0)
      qref <- Ref[F, State](State(Vector.empty, Vector.empty, None))
    } yield
      new Queue[F, A] {
        // Signals size change of queue, if that has changed
        private def signalSize(s: State, ns: State): F[Unit] =
          if (s.queue.size != ns.queue.size) szSignal.set(ns.queue.size)
          else F.unit

        def upperBound: Option[Int] = None
        def enqueue1(a: A): F[Unit] = offer1(a).void

        def offer1(a: A): F[Boolean] =
          qref
            .modifyAndReturn { s =>
              val (newState, signalDequeuers) = s.deq match {
                case dequeuers if dequeuers.isEmpty =>
                  // we enqueue a value to the queue
                  val ns = s.copy(queue = s.queue :+ a, peek = None)
                  ns -> signalSize(s, ns)
                case (_, firstDequeuer) +: dequeuers =>
                  // we await the first dequeuer
                  s.copy(deq = dequeuers, peek = None) -> async.fork {
                    firstDequeuer.complete(Chunk.singleton(a))
                  }.void
              }

              val signalPeekers =
                s.peek.fold(F.unit)(p => async.fork(p.complete(a)).void)

              newState -> (signalDequeuers *> signalPeekers)
            }
            .flatten
            .as(true)

        def dequeue1: F[A] = dequeueBatch1(1).map(_.head.get)

        def dequeue: Stream[F, A] =
          Stream.bracket(F.delay(new Token))(
            t => Stream.repeatEval(dequeueBatch1Impl(1, t).map(_.head.get)),
            t => qref.modify(s => s.copy(deq = s.deq.filterNot(_._1 == t))))

        def dequeueBatch: Pipe[F, Int, A] =
          batchSizes =>
            Stream.bracket(F.delay(new Token))(
              t =>
                batchSizes.flatMap(batchSize =>
                  Stream.eval(dequeueBatch1Impl(batchSize, t)).flatMap(Stream.chunk(_))),
              t => qref.modify(s => s.copy(deq = s.deq.filterNot(_._1 == t)))
          )

        def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
          dequeueBatch1Impl(batchSize, new Token)

        private def dequeueBatch1Impl(batchSize: Int, token: Token): F[Chunk[A]] =
          Deferred[F, Chunk[A]].flatMap { d =>
            qref.modifyAndReturn { s =>
              val newState =
                if (s.queue.isEmpty) s.copy(deq = s.deq :+ (token -> d))
                else s.copy(queue = s.queue.drop(batchSize))

              val cleanup =
                if (s.queue.nonEmpty) F.unit
                else qref.modify(s => s.copy(deq = s.deq.filterNot(_._2 == d)))

              val dequeueBatch = signalSize(s, newState).flatMap { _ =>
                if (s.queue.nonEmpty) {
                  if (batchSize == 1) Chunk.singleton(s.queue.head).pure[F]
                  else Chunk.indexedSeq(s.queue.take(batchSize)).pure[F]
                } else
                  F.onCancelRaiseError(d.get, Canceled).recoverWith {
                    case Canceled => cleanup *> F.never
                  }
              }

              newState -> dequeueBatch
            }.flatten
          }

        def peek1: F[A] =
          Deferred[F, A].flatMap { d =>
            qref.modifyAndReturn { state =>
              val newState =
                if (state.queue.isEmpty && state.peek.isEmpty)
                  state.copy(peek = Some(d))
                else state

              val cleanup = qref.modify { state =>
                if (state.peek == Some(d)) state.copy(peek = None) else state
              }

              val peekAction =
                state.queue.headOption.map(_.pure[F]).getOrElse {
                  F.onCancelRaiseError(newState.peek.get.get, Canceled).recoverWith {
                    case Canceled => cleanup *> F.never
                  }
                }

              newState -> peekAction
            }.flatten
          }

        def size = szSignal
        def full: immutable.Signal[F, Boolean] =
          Signal.constant[F, Boolean](false)
        def available: immutable.Signal[F, Int] =
          Signal.constant[F, Int](Int.MaxValue)
      }
  }

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F],
                                     ec: ExecutionContext): F[Queue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield
      new Queue[F, A] {
        def upperBound: Option[Int] = Some(maxSize)
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
        def peek1: F[A] = q.peek1
        def size = q.size
        def full: immutable.Signal[F, Boolean] = q.size.map(_ >= maxSize)
        def available: immutable.Signal[F, Int] = q.size.map(maxSize - _)
      }

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F],
                                            ec: ExecutionContext): F[Queue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield
      new Queue[F, A] {
        def upperBound: Option[Int] = Some(maxSize)
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
        def peek1: F[A] = q.peek1
        def size = q.size
        def full: immutable.Signal[F, Boolean] = q.size.map(_ >= maxSize)
        def available: immutable.Signal[F, Int] = q.size.map(maxSize - _)
      }

  /** Creates a queue which allows a single element to be enqueued at any time. */
  def synchronous[F[_], A](implicit F: Concurrent[F], ec: ExecutionContext): F[Queue[F, A]] =
    for {
      permits <- Semaphore(0)
      q <- unbounded[F, A]
    } yield
      new Queue[F, A] {
        def upperBound: Option[Int] = Some(0)
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
        def peek1: F[A] = q.peek1
        def size = q.size
        def full: immutable.Signal[F, Boolean] = Signal.constant(true)
        def available: immutable.Signal[F, Int] = Signal.constant(0)
      }

  /** Like `Queue.synchronous`, except that an enqueue or offer of `None` will never block. */
  def synchronousNoneTerminated[F[_], A](implicit F: Concurrent[F],
                                         ec: ExecutionContext): F[Queue[F, Option[A]]] =
    for {
      permits <- Semaphore(0)
      doneRef <- Ref[F, Boolean](false)
      q <- unbounded[F, Option[A]]
    } yield
      new Queue[F, Option[A]] {
        def upperBound: Option[Int] = Some(0)
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
        def peek1: F[Option[A]] = q.peek1
        def size = q.size
        def full: immutable.Signal[F, Boolean] = Signal.constant(true)
        def available: immutable.Signal[F, Int] = Signal.constant(0)
      }
}
