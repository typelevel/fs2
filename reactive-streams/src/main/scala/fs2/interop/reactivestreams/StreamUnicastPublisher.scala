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
package interop
package reactivestreams

import cats.effect._
import cats.effect.unsafe.UnsafeRun
import cats.syntax.all._

import org.reactivestreams._

/**
  * Implementation of a `org.reactivestreams.Publisher`
  *
  * This is used to publish elements from a `fs2.Stream` to a downstream reactivestreams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#1-publisher-code]]
  */
final class StreamUnicastPublisher[F[_]: Async: UnsafeRun, A](val stream: Stream[F, A])
    extends Publisher[A] {
  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    nonNull(subscriber)
    UnsafeRun[F].unsafeRunAndForget {
      StreamSubscription(subscriber, stream)
        .flatMap { subscription =>
          Sync[F].delay {
            subscriber.onSubscribe(subscription)
            subscription.unsafeStart()
          }
        }
    }
  }

  private def nonNull[B](b: B): Unit = if (b == null) throw new NullPointerException()
}

object StreamUnicastPublisher {
  def apply[F[_]: Async: UnsafeRun, A](
      s: Stream[F, A]
  ): StreamUnicastPublisher[F, A] =
    new StreamUnicastPublisher(s)
}
