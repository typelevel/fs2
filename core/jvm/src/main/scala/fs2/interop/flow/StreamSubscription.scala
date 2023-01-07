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

import cats.effect.kernel.Async
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
private[flow] final class StreamSubscription[F[_], A](
    requests: Queue[F, StreamSubscription.Request],
    cancelled: SignallingRef[F, Boolean],
    sub: Subscriber[A],
    stream: Stream[F, A],
    startDispatcher: Dispatcher[F],
    requestDispatcher: Dispatcher[F]
)(implicit
    F: Async[F]
) extends Subscription {
  // We want to make sure `cancelled` is set before signalling the subscriber.
  def onError(e: Throwable): F[Unit] =
    cancelled.set(true) >> F.delay(sub.onError(e))

  // We want to make sure `cancelled` is set before signalling the subscriber.
  def onComplete: F[Unit] =
    cancelled.set(true) >> F.delay(sub.onComplete)

  def unsafeStart(): Unit = {
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

    startDispatcher.unsafeRunAndForget {
      stream
        .through(subscriptionPipe)
        .interruptWhen(cancelled)
        .evalMap(x => F.delay(sub.onNext(x)))
        .handleErrorWith(e => Stream.eval(onError(e)))
        .onFinalize(cancelled.get.ifM(ifTrue = F.unit, ifFalse = onComplete))
        .compile
        .drain
    }
  }

  // According to the spec, it's acceptable for a concurrent cancel to not
  // be processed immediately, but if you have synchronous `cancel();
  // request()`, then the request _must_ be a no op. Fortunately,
  // ordering is guaranteed by a sequential d
  // See https://github.com/zainab-ali/fs2-reactive-streams/issues/29
  // and https://github.com/zainab-ali/fs2-reactive-streams/issues/46
  def cancel(): Unit =
    requestDispatcher.unsafeRunAndForget(cancelled.set(true))

  def request(n: Long): Unit = {
    val request: F[StreamSubscription.Request] =
      if (n == java.lang.Long.MAX_VALUE)
        F.pure(StreamSubscription.Request.Infinite)
      else if (n > 0)
        F.pure(StreamSubscription.Request.Finite(n))
      else
        F.raiseError(new IllegalArgumentException(s"Invalid number of elements [${n}]"))

    val prog =
      cancelled.get.flatMap {
        case true =>
          F.unit

        case false =>
          request.flatMap(requests.offer).handleErrorWith(onError)
      }

    requestDispatcher.unsafeRunAndForget(prog)
  }
}

private[flow] object StreamSubscription {

  /** Represents a downstream subscriber's request to publish elements. */
  sealed trait Request
  object Request {
    case object Infinite extends Request
    final case class Finite(n: Long) extends Request
  }

  def apply[F[_], A](
      sub: Subscriber[A],
      stream: Stream[F, A],
      startDispatcher: Dispatcher[F],
      requestDispatcher: Dispatcher[F]
  )(implicit
      F: Async[F]
  ): F[StreamSubscription[F, A]] =
    SignallingRef(false).flatMap { cancelled =>
      Queue.unbounded[F, Request].map { requests =>
        new StreamSubscription(
          requests,
          cancelled,
          sub,
          stream,
          startDispatcher,
          requestDispatcher
        )
      }
    }
}
