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

import cats.effect.{Async, IO, Resource}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime

import java.util.Objects.requireNonNull
import java.util.concurrent.Flow.{Publisher, Subscriber}
import java.util.concurrent.RejectedExecutionException
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
private[fs2] sealed abstract class StreamPublisher[F[_], A] private (
    stream: Stream[F, A]
)(implicit
    F: Async[F]
) extends Publisher[A] {
  protected def runSubscription(run: F[Unit]): Unit

  override final def subscribe(subscriber: Subscriber[? >: A]): Unit = {
    requireNonNull(
      subscriber,
      "The subscriber provided to subscribe must not be null"
    )
    val subscription = StreamSubscription(stream, subscriber)
    subscriber.onSubscribe(subscription)
    try
      runSubscription(subscription.run.compile.drain)
    catch {
      case _: IllegalStateException | _: RejectedExecutionException =>
        subscriber.onError(StreamPublisher.CanceledStreamPublisherException)
    }
  }
}

private[fs2] object StreamPublisher {
  private final class DispatcherStreamPublisher[F[_], A](
      stream: Stream[F, A],
      dispatcher: Dispatcher[F]
  )(implicit
      F: Async[F]
  ) extends StreamPublisher[F, A](stream) {
    override protected final def runSubscription(run: F[Unit]): Unit =
      dispatcher.unsafeRunAndForget(run)
  }

  private final class IORuntimeStreamPublisher[A](
      stream: Stream[IO, A]
  )(implicit
      runtime: IORuntime
  ) extends StreamPublisher[IO, A](stream) {
    override protected final def runSubscription(run: IO[Unit]): Unit =
      run.unsafeRunAndForget()
  }

  def apply[F[_], A](
      stream: Stream[F, A]
  )(implicit
      F: Async[F]
  ): Resource[F, StreamPublisher[F, A]] =
    Dispatcher.parallel[F](await = true).map { dispatcher =>
      new DispatcherStreamPublisher(stream, dispatcher)
    }

  def unsafe[A](
      stream: Stream[IO, A]
  )(implicit
      runtime: IORuntime
  ): StreamPublisher[IO, A] =
    new IORuntimeStreamPublisher(stream)

  private object CanceledStreamPublisherException
      extends IllegalStateException(
        "This StreamPublisher is not longer accepting subscribers"
      )
      with NoStackTrace
}
