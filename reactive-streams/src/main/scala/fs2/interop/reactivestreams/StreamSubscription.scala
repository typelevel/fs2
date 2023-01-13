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

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._
import fs2.concurrent.SignallingRef
import org.reactivestreams.{Subscription, Subscriber}

import java.util.concurrent.atomic.AtomicBoolean

/** Implementation of a `org.reactivestreams.Subscription`.
  *
  * This is used by the [[StreamUnicastPublisher]] to send elements from a `fs2.Stream` to a downstream reactivestreams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code]]
  */
private[reactivestreams] final class StreamSubscription[F[_], A] private (
    requests: Queue[F, StreamSubscription.Request],
    cancelled: SignallingRef[F, Boolean],
    sub: Subscriber[A],
    stream: Stream[F, A],
    requestDispatcher: Dispatcher[F],
    completed: AtomicBoolean
)(implicit F: Async[F])
    extends Subscription {
  import StreamSubscription._

  // Ensure we are on a terminal state before signaling the subscriber.
  private def onError(e: Throwable): F[Unit] =
    cancelMe >> F.delay(sub.onError(e))

  private def onComplete: F[Unit] =
    cancelMe >> F.delay(sub.onComplete)

  private[reactivestreams] def run: F[Unit] = {
    def subscriptionPipe: Pipe[F, A, A] =
      in => {
        def go(s: Stream[F, A]): Pull[F, A, Unit] =
          Pull.eval(requests.take).flatMap {
            case Infinite => s.pull.echo
            case Finite(n) =>
              s.pull.take(n).flatMap {
                case None      => Pull.done
                case Some(rem) => go(rem)
              }
          }

        go(in).stream
      }

    stream
      .through(subscriptionPipe)
      .interruptWhen(cancelled)
      .evalMap(x => F.delay(sub.onNext(x)))
      .handleErrorWith(e => Stream.eval(onError(e)))
      .onFinalize(cancelled.get.ifM(ifTrue = F.unit, ifFalse = onComplete))
      .compile
      .drain
  }

  // According to the spec, it's acceptable for a concurrent cancel to not
  // be processed immediately, but if you have synchronous `cancel();
  // request()`, then the request _must_ be a no op. Fortunately,
  // ordering is guaranteed by a sequential dispatcher.
  // See https://github.com/zainab-ali/fs2-reactive-streams/issues/29
  // and https://github.com/zainab-ali/fs2-reactive-streams/issues/46
  private def cancelMe: F[Unit] =
    F.delay(completed.set(true)) >> cancelled.set(true)
  override def cancel(): Unit =
    if (!completed.get()) {
      requestDispatcher.unsafeRunSync(cancelMe)
    }

  override def request(n: Long): Unit =
    if (!completed.get()) {
      val prog =
        if (n == java.lang.Long.MAX_VALUE)
          requests.offer(Infinite)
        else if (n > 0)
          requests.offer(Finite(n))
        else
          onError(new IllegalArgumentException(s"Invalid number of elements [${n}]"))
      requestDispatcher.unsafeRunSync(prog)
    }
}

private[reactivestreams] object StreamSubscription {

  /** Represents a downstream subscriber's request to publish elements */
  private sealed trait Request
  private case object Infinite extends Request
  private final case class Finite(n: Long) extends Request

  // Mostly for testing purposes.
  def apply[F[_], A](stream: Stream[F, A], subscriber: Subscriber[A])(implicit
      F: Async[F]
  ): Resource[F, StreamSubscription[F, A]] =
    (
      Dispatcher.sequential[F](await = true),
      Resource.eval(SignallingRef(false)),
      Resource.eval(Queue.unbounded[F, Request]),
      Resource.eval(F.delay(new AtomicBoolean(false)))
    ).mapN { case (requestDispatcher, cancelled, requests, completed) =>
      new StreamSubscription(
        requests,
        cancelled,
        subscriber,
        stream,
        requestDispatcher,
        completed
      )
    }.evalTap { subscription =>
      F.delay(subscriber.onSubscribe(subscription))
    }

  def subscribe[F[_], A](stream: Stream[F, A], subscriber: Subscriber[A])(implicit
      F: Async[F]
  ): F[Unit] =
    apply(stream, subscriber).use { subscription =>
      F.onCancel(subscription.run, subscription.cancelMe)
    }
}
