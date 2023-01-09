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
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._
import fs2.concurrent.SignallingRef

import java.util.concurrent.Flow.{Subscription, Subscriber}

/** Implementation of a [[Subscription]].
  *
  * This is used by the [[StreamUnicastPublisher]] to send elements from a [[Stream]] to a downstream reactive-streams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code]]
  */
private[flow] final class StreamSubscription[F[_], A] private (
    stream: Stream[F, A],
    sub: Subscriber[A],
    requestDispatcher: Dispatcher[F],
    requests: Queue[F, StreamSubscription.Request],
    canceled: SignallingRef[F, Boolean]
)(implicit F: Async[F])
    extends Subscription {
  // Ensure we are on a terminal state before signaling the subscriber.
  private def onError(e: Throwable): F[Unit] =
    cancelMe >> F.delay(sub.onError(e))

  private def onComplete: F[Unit] =
    cancelMe >> F.delay(sub.onComplete())

  private[flow] def run: F[Unit] = {
    def subscriptionPipe: Pipe[F, A, A] =
      in => {
        def go(s: Stream[F, A]): Pull[F, A, Unit] =
          Pull.eval(requests.take).flatMap {
            case StreamSubscription.Request.Infinite =>
              s.pull.echo

            case StreamSubscription.Request.Finite(n) =>
              s.pull.take(n).flatMap {
                case None      => Pull.done
                case Some(rem) => go(rem)
              }
          }

        go(in).stream
      }

    stream
      .through(subscriptionPipe)
      .foreach(a => F.delay(sub.onNext(a)))
      .interruptWhen(canceled)
      .onFinalizeCase {
        case Resource.ExitCase.Succeeded =>
          canceled.get.flatMap {
            case true =>
              F.unit

            case false =>
              onComplete
          }

        case Resource.ExitCase.Errored(e) =>
          onError(e)

        case Resource.ExitCase.Canceled =>
          F.unit
      }
      .compile
      .drain
  }

  // According to the spec, it's acceptable for a concurrent cancel to not
  // be processed immediately, but if you have synchronous `cancel();
  // request()`, then the request _must_ be a NOOP. Fortunately,
  // ordering is guaranteed by a sequential dispatcher.
  // See https://github.com/zainab-ali/fs2-reactive-streams/issues/29
  // and https://github.com/zainab-ali/fs2-reactive-streams/issues/46
  private def cancelMe: F[Unit] =
    canceled.set(true)
  override def cancel(): Unit =
    try
      requestDispatcher.unsafeRunAndForget(cancelMe)
    catch {
      case _: IllegalStateException =>
      // Dispatcher already shutdown, we are on terminal state, NOOP.
    }

  override def request(n: Long): Unit = {
    val prog =
      canceled.get.flatMap {
        case false =>
          if (n == java.lang.Long.MAX_VALUE)
            requests.offer(StreamSubscription.Request.Infinite)
          else if (n > 0)
            requests.offer(StreamSubscription.Request.Finite(n))
          else
            onError(new IllegalArgumentException(s"Invalid number of elements [${n}]"))
        case true =>
          F.unit
      }
    try
      requestDispatcher.unsafeRunAndForget(prog)
    catch {
      case _: IllegalStateException =>
      // Dispatcher already shutdown, we are on terminal state, NOOP.
    }
  }
}

private[flow] object StreamSubscription {

  /** Represents a downstream subscriber's request to publish elements. */
  private sealed trait Request
  private object Request {
    case object Infinite extends Request
    final case class Finite(n: Long) extends Request
  }

  // Mostly for testing purposes.
  def apply[F[_], A](stream: Stream[F, A], subscriber: Subscriber[A])(implicit
      F: Async[F]
  ): Resource[F, StreamSubscription[F, A]] =
    (
      Dispatcher.sequential[F](await = true),
      Resource.eval(Queue.unbounded[F, Request]),
      Resource.eval(SignallingRef(false))
    ).mapN { case (requestDispatcher, requests, canceled) =>
      new StreamSubscription(
        stream,
        subscriber,
        requestDispatcher,
        requests,
        canceled
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
