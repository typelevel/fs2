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

package fs2.experimental.concurrent

import cats.effect.Concurrent
import cats.syntax.all._
import fs2._

trait Publish[F[_], A] extends fs2.concurrent.Publish[F, A]
trait Subscribe[F[_], A, Selector] extends fs2.concurrent.Subscribe[F, A, Selector]

trait PubSub[F[_], I, O, Selector]
    extends Publish[F, I]
    with Subscribe[F, O, Selector]
    with concurrent.PubSub[F, I, O, Selector]

object PubSub {
  def apply[F[_]: Concurrent, I, O, QS, Selector](
      strategy: Strategy[I, O, QS, Selector]
  ): F[PubSub[F, I, O, Selector]] =
    fs2.concurrent.PubSub(strategy).map { self =>
      new PubSub[F, I, O, Selector] {
        def get(selector: Selector): F[O] = self.get(selector)
        def getStream(selector: Selector): Stream[F, O] = self.getStream(selector)
        def tryGet(selector: Selector): F[Option[O]] = self.tryGet(selector)
        def subscribe(selector: Selector): F[Boolean] = self.subscribe(selector)
        def unsubscribe(selector: Selector): F[Unit] = self.unsubscribe(selector)
        def publish(a: I): F[Unit] = self.publish(a)
        def tryPublish(a: I): F[Boolean] = self.tryPublish(a)
      }
    }

  trait Strategy[I, O, S, Selector] extends fs2.concurrent.PubSub.Strategy[I, O, S, Selector]

  object Strategy {
    private[experimental] def convert[I, O, S, Selector](
        strategy: fs2.concurrent.PubSub.Strategy[I, O, S, Selector]
    ): Strategy[I, O, S, Selector] =
      new Strategy[I, O, S, Selector] {
        def initial: S = strategy.initial
        def accepts(i: I, queueState: S): Boolean = strategy.accepts(i, queueState)
        def publish(i: I, queueState: S): S = strategy.publish(i, queueState)
        def get(selector: Selector, queueState: S): (S, Option[O]) =
          strategy.get(selector, queueState)
        def empty(queueState: S): Boolean = strategy.empty(queueState)
        def subscribe(selector: Selector, queueState: S): (S, Boolean) =
          strategy.subscribe(selector, queueState)
        def unsubscribe(selector: Selector, queueState: S): S =
          strategy.unsubscribe(selector, queueState)
      }

    def bounded[A, S](
        maxSize: Int
    )(strategy: Strategy[A, Chunk[A], S, Int])(f: S => Int): Strategy[A, Chunk[A], S, Int] =
      convert(fs2.concurrent.PubSub.Strategy.bounded(maxSize)(strategy)(f))

    def closeNow[I, O, S, Sel](
        strategy: Strategy[I, O, S, Sel]
    ): PubSub.Strategy[Option[I], Option[O], Option[S], Sel] =
      convert(fs2.concurrent.PubSub.Strategy.closeNow(strategy))

    def closeDrainFirst[I, O, S, Sel](
        strategy: PubSub.Strategy[I, O, S, Sel]
    ): PubSub.Strategy[Option[I], Option[O], (Boolean, S), Sel] =
      convert(fs2.concurrent.PubSub.Strategy.closeDrainFirst(strategy))
  }
}
