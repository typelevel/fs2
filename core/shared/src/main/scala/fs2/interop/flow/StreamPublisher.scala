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
package flow

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Dispatcher

import java.util.Objects.requireNonNull
import java.util.concurrent.Flow.{Publisher, Subscriber, Subscription}
import scala.util.control.NoStackTrace

/** Implementation of a [[Publisher]].
  *
  * This is used to publish elements from a [[Stream]] to a downstream reactive-streams system.
  *
  * @note This Publisher can be reused for multiple Subscribers,
  *       each subscription will re-run the [[Stream]] from the beginning.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#1-publisher-code]]
  */
private[flow] final class StreamPublisher[F[_], A] private (
    stream: Stream[F, A],
    startDispatcher: Dispatcher[F]
)(implicit F: Async[F])
    extends Publisher[A] {
  override def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    requireNonNull(
      subscriber,
      "The subscriber provided to subscribe must not be null"
    )
    try
      startDispatcher.unsafeRunAndForget(
        StreamSubscription.subscribe(stream, subscriber)
      )
    catch {
      case _: IllegalStateException =>
        subscriber.onSubscribe(new Subscription {
          override def cancel(): Unit = ()
          override def request(x$1: Long): Unit = ()
        })
        subscriber.onError(StreamPublisher.CanceledStreamPublisherException)
    }
  }
}

private[flow] object StreamPublisher {
  def apply[F[_], A](
      stream: Stream[F, A]
  )(implicit F: Async[F]): Resource[F, StreamPublisher[F, A]] =
    Dispatcher.parallel[F](await = false).map { startDispatcher =>
      new StreamPublisher(stream, startDispatcher)
    }

  private object CanceledStreamPublisherException
      extends IllegalStateException(
        "This StreamPublisher is not longer accepting subscribers"
      )
      with NoStackTrace
}
