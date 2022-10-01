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
    Queue.unbounded[F, A].flatMap(impl(_))

  def synchronous[F[_]: Concurrent, A]: F[Channel[F, A]] =
    Queue.synchronous[F, A].flatMap(impl(_))

  def bounded[F[_], A](capacity: Int)(implicit F: Concurrent[F]): F[Channel[F, A]] =
    Queue.bounded[F, A](capacity).flatMap(impl(_))

  private[this] def impl[F[_]: Concurrent, A](q: Queue[F, A]): F[Channel[F, A]] =
    (Concurrent[F].deferred[Unit], Concurrent[F].ref(0)).mapN { (closedR, leasesR) =>
      new Channel[F, A] {

        def sendAll: Pipe[F, A, Nothing] =
          _.evalMapChunk(send(_))
            .takeWhile(_.isRight)
            .onComplete(Stream.exec(close.void))
            .drain

        // doesn't interrupt taking in progress
        def close: F[Either[Channel.Closed, Unit]] =
          closedR.complete(()).map {
            case false => Left(Channel.Closed)
            case true  => Right(())
          }

        def isClosed: F[Boolean] = closedR.tryGet.map(_.isDefined)

        private[this] val leasesDrained: F[Boolean] =
          leasesR.get.map(_ <= 0)

        private[this] val isQuiesced: F[Boolean] =
          isClosed.ifM(leasesDrained, false.pure[F])

        def send(a: A): F[Either[Channel.Closed, Unit]] =
          isClosed.ifM(
            Channel.Closed.asLeft[Unit].pure[F],
            (leasesR.update(_ + 1) *> q.offer(a)).guarantee(leasesR.update(_ - 1)).map(Right(_))
          )

        def trySend(a: A): F[Either[Channel.Closed, Boolean]] =
          isClosed.ifM(Channel.Closed.asLeft[Boolean].pure[F], q.tryOffer(a).map(Right(_)))

        def stream: Stream[F, A] = {
          val takeN: F[Chunk[A]] =
            q.tryTakeN(None).flatMap {
              case Nil =>
                val fallback = leasesDrained.flatMap { b =>
                  if (b) {
                    MonadCancel[F].uncancelable { poll =>
                      poll(Spawn[F].racePair(q.take, closedR.get)).flatMap {
                        case Left((oca, fiber)) =>
                          oca.embedNever.flatMap(a => fiber.cancel.as(Chunk.singleton(a)))

                        case Right((fiber, ocb)) =>
                          ocb.embedNever.flatMap { _ =>
                            (fiber.cancel *> fiber.join).flatMap { oca =>
                              oca.fold(
                                Chunk.empty[A].pure[F],
                                _ => Chunk.empty[A].pure[F],
                                _.map(Chunk.singleton(_))
                              )
                            }
                          }
                      }
                    }
                  } else {
                    q.take.map(Chunk.singleton(_))
                  }
                }

                isQuiesced.ifM(Chunk.empty[A].pure[F], fallback)

              case as =>
                Chunk.seq(as).pure[F]
            }

          // you can do this more efficiently, just proves a point
          Stream.eval(takeN).repeat.takeWhile(!_.isEmpty).unchunks
        }

        def closed: F[Unit] = closedR.get
      }
    }
}
