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

import org.reactivestreams._

import scala.annotation.unused
import scala.util.control.NoStackTrace

/** Implementation of a `org.reactivestreams.Publisher`
  *
  * This is used to publish elements from a [[Stream]] to a downstream reactive-streams system.
  *
  * @note Not longer unicast, this Publisher can be reused for multiple Subscribers:
  *       each subscription will re-run the [[Stream]] from the beginning.
  *       However, a _parallel_ `Dispatcher` is required to allow concurrent subscriptions.
  *       Please, refer to the `apply` factory in the companion object that only requires a stream.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#1-publisher-code]]
  */
final class StreamUnicastPublisher[F[_]: Async, A](
    val stream: Stream[F, A],
    startDispatcher: Dispatcher[F]
) extends Publisher[A] {
  // Added only for bincompat, effectively deprecated.
  private[reactivestreams] def this(
      stream: Stream[F, A],
      startDispatcher: Dispatcher[F],
      @unused requestDispatcher: Dispatcher[F]
  ) =
    this(stream, startDispatcher)

  def subscribe(subscriber: Subscriber[? >: A]): Unit = {
    nonNull(subscriber)
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
        subscriber.onError(StreamUnicastPublisher.CanceledStreamPublisherException)
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
    new StreamUnicastPublisher(s, dispatcher)

  def apply[F[_]: Async, A](
      s: Stream[F, A]
  ): Resource[F, StreamUnicastPublisher[F, A]] =
    Dispatcher.parallel[F](await = false).map { startDispatcher =>
      new StreamUnicastPublisher(stream = s, startDispatcher)
    }

  private object CanceledStreamPublisherException
      extends IllegalStateException(
        "This StreamPublisher is not longer accepting subscribers"
      )
      with NoStackTrace
}
