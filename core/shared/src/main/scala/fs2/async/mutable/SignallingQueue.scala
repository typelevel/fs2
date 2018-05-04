package fs2
package async
package mutable

import scala.concurrent.ExecutionContext

import cats.Functor
import cats.effect.Concurrent
import cats.implicits._

import fs2.internal.{Canceled, Token}

/**
  * Asynchronous queue interface. Operations are all nonblocking in their
  * implementations, but may be 'semantically' blocking. For instance,
  * a queue may have a bound on its size, in which case enqueuing may
  * block until there is an offsetting dequeue.
  */
abstract class SignallingQueue[F[_], A] extends Queue[F, A] { self =>

  /**
    * The time-varying size of this `Queue`. This signal refreshes
    * only when size changes. Offsetting enqueues and de-queues may
    * not result in refreshes.
    */
  def sizeSignal: immutable.Signal[F, Int]

  /** The size bound on the queue. `None` if the queue is unbounded. */
  def upperBound: Option[Int]

  /**
    * Returns the available number of entries in the queue.
    * Always `Int.MaxValue` when the queue is unbounded.
    */
  def availableSignal: immutable.Signal[F, Int]

  /**
    * Returns `true` when the queue has reached its upper size bound.
    * Always `false` when the queue is unbounded.
    */
  def fullSignal: immutable.Signal[F, Boolean]

  def size: F[Int] = sizeSignal.get

  def available: F[Int] = availableSignal.get

  def full: F[Boolean] = fullSignal.get

  /**
    * Returns an alternate view of this `Queue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  override def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): SignallingQueue[F, B] =
    new SignallingQueue[F, B] {
      def availableSignal: immutable.Signal[F, Int] = self.availableSignal
      def fullSignal: immutable.Signal[F, Boolean] = self.fullSignal
      def sizeSignal: immutable.Signal[F, Int] = self.sizeSignal
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

object SignallingQueue {

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F],
                         ec: ExecutionContext): F[SignallingQueue[F, A]] = {
    /*
     * Internal state of the queue
     * @param queue    Queue, expressed as vector for fast cons/uncons from head/tail
     * @param deq      A list of waiting dequeuers, added to when queue is empty
     * @param peek     The waiting peekers (if any), created when queue is empty
     */
    final case class State(
        queue: Vector[A],
        deq: Vector[(Token, Promise[F, Chunk[A]])],
        peek: Option[Promise[F, A]]
    )

    for {
      szSignal <- Signal(0)
      qref <- async.refOf[F, State](State(Vector.empty, Vector.empty, None))
    } yield
      new SignallingQueue[F, A] {
        // Signals size change of queue, if that has changed
        private def signalSize(s: State, ns: State): F[Unit] =
          if (s.queue.size != ns.queue.size) szSignal.set(ns.queue.size)
          else F.pure(())

        def upperBound: Option[Int] = None
        def enqueue1(a: A): F[Unit] = offer1(a).as(())

        def offer1(a: A): F[Boolean] =
          qref
            .modify { s =>
              if (s.deq.isEmpty) s.copy(queue = s.queue :+ a, peek = None)
              else s.copy(deq = s.deq.tail, peek = None)
            }
            .flatMap { c =>
              val dq = if (c.previous.deq.isEmpty) {
                // we enqueued a value to the queue
                signalSize(c.previous, c.now)
              } else {
                // queue was empty, we had waiting dequeuers
                async.shiftStart(c.previous.deq.head._2.complete(Chunk.singleton(a)))
              }
              val pk = if (c.previous.peek.isEmpty) {
                // no peeker to notify
                F.unit
              } else {
                // notify peekers
                async.shiftStart(c.previous.peek.get.complete(a))

              }
              (dq *> pk).as(true)
            }

        def dequeue1: F[A] = dequeueBatch1(1).map(_.head.get)

        def dequeue: Stream[F, A] =
          Stream.bracket(F.delay(new Token))(
            t => Stream.repeatEval(dequeueBatch1Impl(1, t).map(_.head.get)),
            t => qref.modify(s => s.copy(deq = s.deq.filterNot(_._1 == t))).void)

        def dequeueBatch: Pipe[F, Int, A] =
          batchSizes =>
            Stream.bracket(F.delay(new Token))(
              t =>
                batchSizes.flatMap(batchSize =>
                  Stream.eval(dequeueBatch1Impl(batchSize, t)).flatMap(Stream.chunk(_))),
              t => qref.modify(s => s.copy(deq = s.deq.filterNot(_._1 == t))).void
          )

        def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
          dequeueBatch1Impl(batchSize, new Token)

        private def dequeueBatch1Impl(batchSize: Int, token: Token): F[Chunk[A]] =
          promise[F, Chunk[A]].flatMap { p =>
            qref
              .modify { s =>
                if (s.queue.isEmpty) s.copy(deq = s.deq :+ (token -> p))
                else s.copy(queue = s.queue.drop(batchSize))
              }
              .flatMap { c =>
                val cleanup =
                  if (c.previous.queue.nonEmpty) F.pure(())
                  else
                    qref.modify { s =>
                      s.copy(deq = s.deq.filterNot(_._2 == p))
                    }.void
                val out = signalSize(c.previous, c.now).flatMap { _ =>
                  if (c.previous.queue.nonEmpty) {
                    if (batchSize == 1)
                      F.pure(Chunk.singleton(c.previous.queue.head))
                    else
                      F.pure(Chunk.indexedSeq(c.previous.queue.take(batchSize)))
                  } else
                    F.onCancelRaiseError(p.get, Canceled).recoverWith {
                      case Canceled => cleanup *> F.async[Chunk[A]](cb => ())
                    }
                }
                out
              }
          }

        def peek1: F[A] =
          promise[F, A].flatMap { p =>
            qref
              .modify { state =>
                if (state.queue.isEmpty && state.peek.isEmpty)
                  state.copy(peek = Some(p))
                else state
              }
              .flatMap { change =>
                if (change.previous.queue.isEmpty) {
                  F.onCancelRaiseError(change.now.peek.get.get, Canceled).recoverWith {
                    case Canceled =>
                      qref.modify { state =>
                        if (state.peek == Some(p)) state.copy(peek = None) else state
                      } *> F.async[A](cb => ())
                  }
                } else F.pure(change.previous.queue.head)
              }
          }
        def sizeSignal = szSignal
        def fullSignal: immutable.Signal[F, Boolean] =
          Signal.constant[F, Boolean](false)
        def availableSignal: immutable.Signal[F, Int] =
          Signal.constant[F, Int](Int.MaxValue)
      }
  }

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F],
                                     ec: ExecutionContext): F[SignallingQueue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield
      new SignallingQueue[F, A] {
        def upperBound: Option[Int] = Some(maxSize)
        def enqueue1(a: A): F[Unit] =
          permits.decrement *> q.enqueue1(a)
        def offer1(a: A): F[Boolean] =
          permits.tryDecrement.flatMap { b =>
            if (b) q.offer1(a) else F.pure(false)
          }
        def dequeue1: F[A] = dequeueBatch1(1).map(_.head.get)
        def dequeue: Stream[F, A] = q.dequeue.evalMap(a => permits.increment.as(a))
        def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
          q.dequeueBatch1(batchSize).flatMap { chunk =>
            permits.incrementBy(chunk.size).as(chunk)
          }
        def dequeueBatch: Pipe[F, Int, A] =
          q.dequeueBatch.andThen(_.chunks.flatMap(c =>
            Stream.eval(permits.incrementBy(c.size)).flatMap(_ => Stream.chunk(c))))
        def peek1: F[A] = q.peek1
        def sizeSignal = q.sizeSignal
        def fullSignal: immutable.Signal[F, Boolean] = q.sizeSignal.map(_ >= maxSize)
        def availableSignal: immutable.Signal[F, Int] = q.sizeSignal.map(maxSize - _)
      }

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F],
                                            ec: ExecutionContext): F[SignallingQueue[F, A]] =
    for {
      permits <- Semaphore(maxSize.toLong)
      q <- unbounded[F, A]
    } yield
      new SignallingQueue[F, A] {
        def upperBound: Option[Int] = Some(maxSize)
        def enqueue1(a: A): F[Unit] =
          permits.tryDecrement.flatMap { b =>
            if (b) q.enqueue1(a) else (q.dequeue1 *> q.enqueue1(a))
          }
        def offer1(a: A): F[Boolean] =
          enqueue1(a).as(true)
        def dequeue1: F[A] = dequeueBatch1(1).map(_.head.get)
        def dequeue: Stream[F, A] = q.dequeue.evalMap(a => permits.increment.as(a))
        def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
          q.dequeueBatch1(batchSize).flatMap { chunk =>
            permits.incrementBy(chunk.size).as(chunk)
          }
        def dequeueBatch: Pipe[F, Int, A] =
          q.dequeueBatch.andThen(_.chunks.flatMap(c =>
            Stream.eval(permits.incrementBy(c.size)).flatMap(_ => Stream.chunk(c))))
        def peek1: F[A] = q.peek1
        def sizeSignal = q.sizeSignal
        def fullSignal: immutable.Signal[F, Boolean] = q.sizeSignal.map(_ >= maxSize)
        def availableSignal: immutable.Signal[F, Int] = q.sizeSignal.map(maxSize - _)
      }

  /** Creates a queue which allows a single element to be enqueued at any time. */
  def synchronous[F[_], A](implicit F: Concurrent[F],
                           ec: ExecutionContext): F[SignallingQueue[F, A]] =
    for {
      permits <- Semaphore(0)
      q <- unbounded[F, A]
    } yield
      new SignallingQueue[F, A] {
        def upperBound: Option[Int] = Some(0)
        def enqueue1(a: A): F[Unit] =
          permits.decrement *> q.enqueue1(a)
        def offer1(a: A): F[Boolean] =
          permits.tryDecrement.flatMap { b =>
            if (b) q.offer1(a) else F.pure(false)
          }
        def dequeue1: F[A] = permits.increment *> q.dequeue1
        def dequeue: Stream[F, A] = {
          def loop(s: Stream[F, A]): Pull[F, A, Unit] =
            Pull.eval(permits.increment) >> s.pull.uncons1.flatMap {
              case Some((h, t)) => Pull.output1(h) >> loop(t)
              case None         => Pull.done
            }
          loop(q.dequeue).stream
        }
        def dequeueBatch1(batchSize: Int): F[Chunk[A]] =
          permits.increment *> q.dequeueBatch1(batchSize)
        def dequeueBatch: Pipe[F, Int, A] = {
          def loop(s: Stream[F, A]): Pull[F, A, Unit] =
            Pull.eval(permits.increment) >> s.pull.uncons1.flatMap {
              case Some((h, t)) => Pull.output1(h) >> loop(t)
              case None         => Pull.done
            }
          in =>
            loop(q.dequeueBatch(in)).stream
        }
        def peek1: F[A] = q.peek1
        def sizeSignal = q.sizeSignal
        def fullSignal: immutable.Signal[F, Boolean] = Signal.constant(true)
        def availableSignal: immutable.Signal[F, Int] = Signal.constant(0)
      }

  /** Like `Queue.synchronous`, except that an enqueue or offer of `None` will never block. */
  def synchronousNoneTerminated[F[_], A](implicit F: Concurrent[F],
                                         ec: ExecutionContext): F[SignallingQueue[F, Option[A]]] =
    for {
      permits <- Semaphore(0)
      doneRef <- refOf[F, Boolean](false)
      q <- unbounded[F, Option[A]]
    } yield
      new SignallingQueue[F, Option[A]] {
        def upperBound: Option[Int] = Some(0)
        def enqueue1(a: Option[A]): F[Unit] = doneRef.access.flatMap {
          case (done, update) =>
            if (done) F.pure(())
            else
              a match {
                case None =>
                  update(true).flatMap { successful =>
                    if (successful) q.enqueue1(None) else enqueue1(None)
                  }
                case _ => permits.decrement *> q.enqueue1(a)
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
                case _ => permits.decrement *> q.offer1(a)
              }
        }
        def dequeue1: F[Option[A]] = permits.increment *> q.dequeue1
        def dequeue: Stream[F, Option[A]] = {
          def loop(s: Stream[F, Option[A]]): Pull[F, Option[A], Unit] =
            Pull.eval(permits.increment) >> s.pull.uncons1.flatMap {
              case Some((h, t)) => Pull.output1(h) >> loop(t)
              case None         => Pull.done
            }
          loop(q.dequeue).stream
        }
        def dequeueBatch1(batchSize: Int): F[Chunk[Option[A]]] =
          permits.increment *> q.dequeueBatch1(batchSize)
        def dequeueBatch: Pipe[F, Int, Option[A]] = {
          def loop(s: Stream[F, Option[A]]): Pull[F, Option[A], Unit] =
            Pull.eval(permits.increment) >> s.pull.uncons1.flatMap {
              case Some((h, t)) => Pull.output1(h) >> loop(t)
              case None         => Pull.done
            }
          in =>
            loop(q.dequeueBatch(in)).stream
        }
        def peek1: F[Option[A]] = q.peek1
        def sizeSignal = q.sizeSignal
        def fullSignal: immutable.Signal[F, Boolean] = Signal.constant(true)
        def availableSignal: immutable.Signal[F, Int] = Signal.constant(0)
      }
}
