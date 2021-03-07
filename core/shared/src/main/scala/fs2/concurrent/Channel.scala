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

/*
 * Multiple producer, single consumer closeable channel.
 *
 * `send` can be called concurrently by multiple producers, and it may
 * semantically block if the Channel is bounded or synchronous.
 *
 * `stream` cannot be called concurrently by multiple consumers, if you do so, one of the consumers might become permanently deadlocked. It is possible to call `stream` again once the previous one has terminated, but be aware that some element might get lost in the process, e.g if the first call to stream got 5 elements off the channel, and terminated after emitting 2, when the second call to stream starts it won't see those 3 elements.
 * the behaviour is undefined if you do so.
 * Every time `stream` is pulled, it will serve all the elements that
 * are queued up in a single chunk, including those from producers
 * that might be semantically blocked on a bounded channel, which will
 * then become unblocked.
 *
 * TODO is this latter decision the correct semantics? it also affects
 * what happens during closure, i.e. whether blocked elements are
 * processed before closing or left there. Consistency with
 * `noneTerminated` says they should be processed in that case. it can
 * also change the behaviour of blocked producers if you try to drain
 * the channel as a way to unblock them, like in topic.
 * In addition, we could have close complete the blocked producers, to avoid having to drain
 * the channel in the Topic scenario, however it's unclear whether they should complete with
 * Closed or Unit, because in the general we don't know if a subsequent call to `stream` is going to arrive, and we ideally want to only return Closed if the elements are discarded
 *
 */

trait Channel[F[_], A] {
  def send(a: A): F[Either[Channel.Closed, Unit]]
  def close: F[Either[Channel.Closed, Unit]]
  def stream: Stream[F, A]
}
object Channel {
  type Closed = Closed.type
  object Closed
  private final val closed: Either[Closed, Unit] = Left(Closed)
  private final val open: Either[Closed, Unit] = Right(())

  def create[F[_], A](implicit F: Concurrent[F]): F[Channel[F, A]] = {
    case class State(
      values: Vector[A],
      wait_ : Option[Deferred[F, Unit]],
      closed: Boolean
    )

    F.ref(State(Vector.empty, None, false)).map { state =>
      new Channel[F, A] {
        def send(a: A) =
          state.modify {
            case s @ State(_, _, closed) if closed == true =>
              s -> Channel.closed.pure[F]
            case State(values, wait, closed) =>
              State(values :+ a, None, closed) -> wait.traverse_(_.complete(())).as(Channel.open)
          }.flatten
            .uncancelable

        def close =
          state.modify {
            case s @ State(_, _, closed) if closed == true =>
              s -> Channel.closed.pure[F]
            case State(values, wait, _) =>
              State(values, None, true) -> wait.traverse_(_.complete(())).as(Channel.open)
          }.flatten.uncancelable

        def consume : Pull[F, A, Unit]  =
          Pull.eval {
            F.deferred[Unit].flatMap { wait =>
              state.modify {
                case State(values, _, closed) =>
                  if (values.nonEmpty) { // values.nonEmpty || prods.nonEmpty
                    val newSt = State(Vector(), None, closed)
                    val emit = Pull.output(Chunk.vector(values))
                    val action =
                      emit >> consume.unlessA(closed)
                    // the unlessA could be removed here,
                    // the important part is to not wait after closure
                    // i.e. the case below

                    newSt -> action
                  } else {
                    val newSt = State(values, wait.some, closed)
                    val action =
                      (Pull.eval(wait.get) >> consume).unlessA(closed)

                    newSt -> action
                  }
              }
            }
          }.flatten

        def stream: Stream[F, A] = consume.stream
      }
    }
  }

}
