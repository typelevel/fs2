/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package concurrent

import cats.Applicative
import cats.effect._
import cats.effect.std.Queue
import cats.effect.syntax.all._
import cats.syntax.all._

/** Stream aware, multiple producer, single consumer closeable channel.
  */
sealed trait Channel[F[_], A] {

  /** Sends all the elements of the input stream through this channel,
    * and closes it after.
    * Especially useful if the channel is single producer.
    */
  def sendAll: Pipe[F, A, Nothing]

  /** Sends an element through this channel.
    *
    * It can be called concurrently by multiple producers, and it may
    * semantically block if the channel is bounded or synchronous.
    *
    * No-op if the channel is closed, see [[close]] for further info.
    */
  def send(a: A): F[Either[Channel.Closed, Unit]]

  /** Attempts to send an element through this channel, and indicates if
    * it succeeded (`true`) or not (`false`).
    *
    * It can be called concurrently by multiple producers, and it may
    * not succeed if the channel is bounded or synchronous. It will
    * never semantically block.
    *
    * No-op if the channel is closed, see [[close]] for further info.
    */
  def trySend(a: A): F[Either[Channel.Closed, Boolean]]

  /** The stream of elements sent through this channel.
    * It terminates if [[close]] is called and all elements in the channel
    * have been emitted (see [[close]] for futher info).
    *
    * This method CANNOT be called concurrently by multiple consumers, if
    * you do so, one of the consumers might become permanently
    * deadlocked.
    *
    * It is possible to call `stream` again once the previous
    * one has terminated, but be aware that some element might get lost
    * in the process, e.g if the first call to `stream` got 5 elements off
    * the channel, and terminated after emitting 2, when the second call
    * to `stream` starts it won't see those 3 elements.
    *
    * Every time `stream` is pulled, it will serve all the elements that
    * are queued up in a single chunk, including those from producers
    * that might be semantically blocked on a bounded channel, which will
    * then become unblocked. That is, a bound on a channel represents
    * the maximum number of elements that can be queued up before a
    * producer blocks, and not the maximum number of elements that will
    * be received by `stream` at once.
    */
  def stream: Stream[F, A]

  /** This method achieves graceful shutdown: when the channel gets
    * closed, `stream` will terminate naturally after consuming all
    * currently enqueued elements, including the ones by producers blocked
    * on a bound.
    *
    * "Termination" here means that `stream` will no longer
    * wait for new elements on the channel, and not that it will be
    * interrupted while performing another action: if you want to
    * interrupt `stream` immediately, without first processing enqueued
    * elements, you should use `interruptWhen` on it instead.
    *
    * After a call to `close`, any further calls to `send` or `close`
    * will be no-ops.
    *
    * Note that `close` does not automatically unblock producers which
    * might be blocked on a bound, they will only become unblocked if
    * `stream` is executing.
    *
    * In other words, if `close` is called while `stream` is
    * executing, blocked producers will eventually become unblocked,
    * before `stream` terminates and further `send` calls become
    * no-ops.
    * However, if `close` is called after `stream` has terminated (e.g
    * because it was interrupted, or had a `.take(n)`), then blocked
    * producers will stay blocked unless they get explicitly
    * unblocked, either by a further call to `stream` to drain the
    * channel, or by a a `race` with `closed`.
    */
  def close: F[Either[Channel.Closed, Unit]]

  /** Returns true if this channel is closed */
  def isClosed: F[Boolean]

  /** Semantically blocks until the channel gets closed. */
  def closed: F[Unit]
}

object Channel {
  type Closed = Closed.type
  object Closed

  def unbounded[F[_]: Concurrent, A]: F[Channel[F, A]] =
    Queue.unbounded[F, AnyRef].flatMap(impl(_))

  def synchronous[F[_]: Concurrent, A]: F[Channel[F, A]] =
    Queue.synchronous[F, AnyRef].flatMap(impl(_))

  def bounded[F[_], A](capacity: Int)(implicit F: Concurrent[F]): F[Channel[F, A]] = {
    require(capacity < Short.MaxValue)
    Queue.bounded[F, AnyRef](capacity).flatMap(impl(_))
  }

  // used as a marker to wake up q.take when the channel is closed
  private[this] val Sentinel = new AnyRef

  private[this] val LeftClosed: Either[Channel.Closed, Unit] = Left(Channel.Closed)
  private[this] val RightUnit: Either[Channel.Closed, Unit] = Right(())

  private final case class State(leases: Int, closed: Boolean)

  private object State {
    val Empty: State = State(0, false)
  }

  // technically this should be A | Sentinel.type
  // the queue will consist of exclusively As until we shut down, when there will be one Sentinel
  private[this] def impl[F[_]: Concurrent, A](q: Queue[F, AnyRef]): F[Channel[F, A]] =
    (Concurrent[F].ref(State.Empty), Concurrent[F].deferred[Unit]).mapN { (stateR, closedLatch) =>
      new Channel[F, A] {

        private[this] val LeftClosedF = LeftClosed.pure[F]
        private[this] val FalseF = false.pure[F]

        // might be interesting to try to optimize this more, but it needs support from CE
        val sendAll: Pipe[F, A, Nothing] =
          _.evalMapChunk(send(_))
            .takeWhile(_.isRight)
            .onComplete(Stream.exec(close.void))
            .drain

        // setting the flag means we won't accept any more sends
        val close: F[Either[Channel.Closed, Unit]] = {
          val modifyF = stateR.modify {
            case State(0, false) =>
              State(0, true) -> q.offer(Sentinel).start.as(RightUnit)

            case State(leases, false) =>
              State(leases, true) -> RightUnit.pure[F]

            case st @ State(_, true) =>
              st -> LeftClosedF
          }

          modifyF.flatten.uncancelable
        }

        val isClosed: F[Boolean] = stateR.get.map(_.closed)

        // there are four states to worry about: open, closing, draining, quiesced
        // in the second state, we have outstanding blocked sends
        // in the third state we have data in the queue but no sends
        // in the fourth state we are completely drained and can shut down the stream
        private[this] val isQuiesced: F[Boolean] =
          stateR.get.flatMap {
            case State(0, true) => q.size.map(_ == 0)
            case _              => FalseF
          }

        def send(a: A): F[Either[Channel.Closed, Unit]] =
          MonadCancel[F].uncancelable { poll =>
            // we track the outstanding blocked offers so we can distinguish closing from draining
            // the very last blocked send, when closed, is responsible for triggering the sentinel

            val modifyF = stateR.modify {
              case st @ State(_, true) =>
                st -> LeftClosedF

              case State(leases, false) =>
                val cleanupF = {
                  val modifyF = stateR.modify {
                    case State(1, true) =>
                      State(0, true) -> q.offer(Sentinel).start.void

                    case State(leases, closed) =>
                      State(leases - 1, closed) -> Applicative[F].unit
                  }

                  modifyF.flatten
                }

                val offerF = poll(q.offer(a.asInstanceOf[AnyRef]).as(RightUnit))

                State(leases + 1, false) -> offerF.guarantee(cleanupF).as(RightUnit)
            }

            modifyF.flatten
          }

        def trySend(a: A): F[Either[Channel.Closed, Boolean]] =
          isClosed.flatMap { b =>
            if (b)
              LeftClosedF.asInstanceOf[F[Either[Channel.Closed, Boolean]]]
            else
              q.tryOffer(a.asInstanceOf[AnyRef]).map(_.asRight[Channel.Closed])
          }

        val stream: Stream[F, A] = {
          lazy val loop: Pull[F, A, Unit] = {
            val pullF = q.tryTakeN(None).flatMap {
              case Nil =>
                // if we land here, it either means we're consuming faster than producing
                // or it means we're actually closed and we need to shut down
                // this is the unhappy path either way

                val fallback = q.take.map { a =>
                  // if we get the sentinel, shut down all the things, otherwise emit
                  if (a eq Sentinel)
                    Pull.eval(closedLatch.complete(()).void)
                  else
                    Pull.output1(a.asInstanceOf[A]) >> loop
                }

                // check to see if we're closed and done processing
                // if we're all done, complete the latch and terminate the stream
                isQuiesced.map { b =>
                  if (b)
                    Pull.eval(closedLatch.complete(()).void)
                  else
                    Pull.eval(fallback).flatten
                }

              case as =>
                // this is the happy path: we were able to take a chunk
                // meaning we're producing as fast or faster than we're consuming

                isClosed.map { b =>
                  if (b) {
                    // if we're closed, we have to check for the sentinel and strip it out
                    val as2 = as.filter(_ ne Sentinel)

                    // if it's empty, we definitely stripped a sentinel, so just be done
                    // if it's non-empty, we can't know without expensive comparisons, so fall through
                    if (as2.isEmpty)
                      Pull.eval(closedLatch.complete(()).void)
                    else
                      Pull.output(Chunk.seq(as2.asInstanceOf[List[A]])) >> loop
                  } else {
                    Pull.output(Chunk.seq(as.asInstanceOf[List[A]])) >> loop
                  }
                }
            }

            Pull.eval(pullF).flatten
          }

          loop.stream
        }

        // closedLatch solely exists to support this function
        val closed: F[Unit] = closedLatch.get
      }
    }
}
