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
import cats.effect.implicits._
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
    bounded(Int.MaxValue)

  def synchronous[F[_]: Concurrent, A]: F[Channel[F, A]] =
    bounded(0)

  def bounded[F[_], A](capacity: Int)(implicit F: Concurrent[F]): F[Channel[F, A]] = {
    case class State(
        values: List[A],
        size: Int,
        waiting: Option[Deferred[F, Unit]],
        producers: List[(A, Deferred[F, Unit])],
        closed: Boolean
    )

    val open = State(List.empty, 0, None, List.empty, closed = false)

    def empty(isClosed: Boolean): State =
      if (isClosed) State(List.empty, 0, None, List.empty, closed = true)
      else open

    (F.ref(open), F.deferred[Unit]).mapN { (state, closedGate) =>
      new Channel[F, A] {

        def sendAll: Pipe[F, A, Nothing] = { in =>
          (in ++ Stream.exec(close.void))
            .evalMap(send)
            .takeWhile(_.isRight)
            .drain
        }

        def send(a: A) =
          F.deferred[Unit].flatMap { producer =>
            F.uncancelable { poll =>
              state.modify {
                case s @ State(_, _, _, _, closed @ true) =>
                  (s, Channel.closed.pure[F])

                case State(values, size, waiting, producers, closed @ false) =>
                  if (size < capacity)
                    (
                      State(a :: values, size + 1, None, producers, false),
                      notifyStream(waiting)
                    )
                  else
                    (
                      State(values, size, None, (a, producer) :: producers, false),
                      notifyStream(waiting) <* waitOnBound(producer, poll)
                    )
              }.flatten
            }
          }

        def close =
          state
            .modify {
              case s @ State(_, _, _, _, closed @ true) =>
                (s, Channel.closed.pure[F])

              case State(values, size, waiting, producers, closed @ false) =>
                (
                  State(values, size, None, producers, true),
                  notifyStream(waiting) <* signalClosure
                )
            }
            .flatten
            .uncancelable

        def isClosed = closedGate.tryGet.map(_.isDefined)

        def closed = closedGate.get

        def stream = consumeLoop.stream

        def consumeLoop: Pull.ToStream[F, A] =
          Pull.eval {
            F.deferred[Unit].flatMap { waiting =>
              state
                .modify { state =>
                  if (shouldEmit(state)) (empty(state.closed), state)
                  else (state.copy(waiting = waiting.some), state)
                }
                .flatMap {
                  case s @ State(values, stateSize, ignorePreviousWaiting @ _, producers, closed) =>
                    if (shouldEmit(s)) {
                      var size = stateSize
                      var allValues = values
                      var unblock = F.unit

                      producers.foreach { case (value, producer) =>
                        size += 1
                        allValues = value :: allValues
                        unblock = unblock <* producer.complete(())
                      }

                      val toEmit = makeChunk(allValues, size)

                      unblock.as(Pull.output(toEmit) >> consumeLoop)
                    } else {
                      F.pure(
                        if (closed) Pull.done
                        else Pull.eval(waiting.get) >> consumeLoop
                      )
                    }
                }
                .uncancelable
            }
          }.flatten

        def notifyStream(waitForChanges: Option[Deferred[F, Unit]]) =
          waitForChanges.traverse(_.complete(())).as(rightUnit)

        def waitOnBound(producer: Deferred[F, Unit], poll: Poll[F]) =
          poll(producer.get).onCancel {
            state.update { s =>
              s.copy(producers = s.producers.filter(_._2 ne producer))
            }
          }

        def signalClosure = closedGate.complete(())

        @inline private def shouldEmit(s: State) = s.values.nonEmpty || s.producers.nonEmpty

        private def makeChunk(allValues: List[A], size: Int): Chunk[A] = {
          val arr = new Array[Any](size)
          var i = size - 1
          var values = allValues
          while (i >= 0) {
            arr(i) = values.head
            values = values.tail
            i -= 1
          }
          Chunk.array(arr).asInstanceOf[Chunk[A]]
        }
      }
    }
  }

  // allocate once
  private final val closed: Either[Closed, Unit] = Left(Closed)
  private final val rightUnit: Either[Closed, Unit] = Right(())
}
