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

/* Multiple producer, single consumer closeable channel */
trait Channel[F[_], A] {
  def send(a: A): F[Unit]
  def close: F[Unit]
  def stream: Stream[F, A]
}
object Channel {
  def create[F[_], A](implicit F: Concurrent[F]): F[Channel[F, A]] = {
    case class State(
      values: Vector[A],
      wait_ : Option[Deferred[F, Unit]],
      closed: Boolean
    )

    F.ref(State(Vector.empty, None, false)).map { state =>
      new Channel[F, A] {
        def send(a: A): F[Unit] =
          state.modify {
            case State(values, wait, closed) =>
              State(values :+ a, None, closed) -> wait.traverse_(_.complete(()))
          }.flatten
            .uncancelable

        def close: F[Unit] =
          state.modify {
            case State(values, wait, _) =>
              State(values, None, true) -> wait.traverse_(_.complete(()))
          }.flatten.uncancelable

        def consume : Pull[F, A, Unit]  =
          Pull.eval {
            F.deferred[Unit].flatMap { wait =>
              state.modify {
                case State(values, _, closed) =>
                  if (values.nonEmpty) {
                    val newSt = State(Vector(), None, closed)
                    val emit = Pull.output(Chunk.vector(values))
                    val action =
                      emit >> consume.unlessA(closed)

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
