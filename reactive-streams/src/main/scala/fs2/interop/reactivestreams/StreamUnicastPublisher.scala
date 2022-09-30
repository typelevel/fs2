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

import cats.effect.kernel._
import cats.effect.std.Dispatcher
import cats.syntax.all._

import org.reactivestreams._

/** Implementation of a `org.reactivestreams.Publisher`
  *
  * This is used to publish elements from a `fs2.Stream` to a downstream reactivestreams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#1-publisher-code]]
  */
final class StreamUnicastPublisher[F[_]: Async, A] private (
    val stream: Stream[F, A],
    startDispatcher: Dispatcher[F],
    requestDispatcher: Dispatcher[F]
) extends Publisher[A] {

  @deprecated("Use StreamUnicastPublisher.apply", "3.4.0")
  def this(stream: Stream[F, A], dispatcher: Dispatcher[F]) = this(stream, dispatcher, dispatcher)

  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    nonNull(subscriber)
    startDispatcher.unsafeRunAndForget {
      StreamSubscription(subscriber, stream, startDispatcher, requestDispatcher)
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
  @deprecated("Use overload which takes only the stream and returns a Resource", "3.4.0")
  def apply[F[_]: Async, A](
      s: Stream[F, A],
      dispatcher: Dispatcher[F]
  ): StreamUnicastPublisher[F, A] =
    new StreamUnicastPublisher(s, dispatcher, dispatcher)

  def apply[F[_]: Async, A](
      s: Stream[F, A]
  ): Resource[F, StreamUnicastPublisher[F, A]] =
    (Dispatcher.sequential[F], Dispatcher.sequential[F]).mapN(new StreamUnicastPublisher(s, _, _))
}
