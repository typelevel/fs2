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

import cats.effect.kernel.{Async, Outcome}
import cats.effect.syntax.all._
import cats.syntax.all._

import java.util.concurrent.Flow.{Subscription, Subscriber}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

/** Implementation of a [[Subscription]].
  *
  * This is used by the [[StreamUnicastPublisher]] to send elements from a [[Stream]] to a downstream reactive-streams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code]]
  */
private[flow] final class StreamSubscription[F[_], A] private (
    stream: Stream[F, A],
    sub: Subscriber[A],
    requests: AtomicLong,
    resume: AtomicReference[() => Unit],
    canceled: AtomicReference[() => Unit]
)(implicit F: Async[F])
    extends Subscription {
  // Ensure we are on a terminal state; i.e. call `cancel`, before signaling the subscriber.
  private def onError(ex: Throwable): Unit = {
    cancel()
    sub.onError(ex)
  }

  private def onComplete(): Unit = {
    cancel()
    sub.onComplete()
  }

  private[flow] def run: F[Unit] = {
    val subscriptionPipe: Pipe[F, A, A] = in => {
      def go(s: Stream[F, A]): Pull[F, A, Unit] =
        Pull.eval(F.delay(requests.get())).flatMap { n =>
          if (n == Long.MaxValue)
            s.pull.echo
          else if (n == 0)
            Pull.eval(F.asyncCheckAttempt[Unit] { cb =>
              F.delay {
                // If there aren't more pending request,
                // we will wait until the next one.
                resume.set(() => cb.apply(Either.unit))
                // However, before blocking,
                // we must check if it has been a concurrent request.
                // In case it was, we abort the wait.
                if (requests.get() > 0)
                  Either.unit
                else
                  Left(Some(F.unit))
              }
            }) >> go(s)
          else
            Pull.eval(F.delay(requests.updateAndGet(r => r - n))) >>
              s.pull.take(n).flatMap {
                case None      => Pull.done
                case Some(rem) => go(rem)
              }
        }

      go(in).stream
    }

    val events =
      stream
        .through(subscriptionPipe)
        .foreach(x => F.delay(sub.onNext(x)))
        .compile
        .drain

    val cancellation = F.asyncCheckAttempt[Unit] { cb =>
      F.delay {
        // Check if we were already cancelled before calling run.
        if (!canceled.compareAndSet(StreamSubscription.Sentinel, () => cb.apply(Either.unit))) {
          Either.unit
        } else {
          Left(Some(F.unit))
        }
      }
    }

    events
      .race(cancellation)
      .guaranteeCase {
        case Outcome.Succeeded(result) =>
          result.flatMap {
            case Left(())  => F.delay(onComplete()) // Events finished normally.
            case Right(()) => F.unit // Events was canceled.
          }
        case Outcome.Errored(ex) => F.delay(onError(ex))
        case Outcome.Canceled()  => F.delay(onComplete())
      }
      .void
  }

  // According to the spec, it's acceptable for a concurrent cancel to not
  // be processed immediately, but if you have synchronous
  // `cancel(); request()`,
  // then the request must be a NOOP.
  // See https://github.com/zainab-ali/fs2-reactive-streams/issues/29
  // and https://github.com/zainab-ali/fs2-reactive-streams/issues/46
  override def cancel(): Unit = {
    val cancelCB = canceled.getAndSet(null)
    if (cancelCB ne null) {
      cancelCB.apply()
    }
  }

  override def request(n: Long): Unit =
    if (canceled.get() ne null) {
      if (n <= 0)
        onError(
          ex = new IllegalArgumentException(s"Invalid number of elements [${n}]")
        )
      else {
        requests.updateAndGet { r =>
          val result = r + n
          if (result < 0) {
            // Overflow.
            Long.MaxValue
          } else {
            result
          }
        }

        resume.get().apply()
      }
    }
}

private[flow] object StreamSubscription {
  private final val Sentinel = () => ()

  // Mostly for testing purposes.
  def apply[F[_], A](stream: Stream[F, A], subscriber: Subscriber[A])(implicit
      F: Async[F]
  ): F[StreamSubscription[F, A]] =
    F.delay {
      val requests = new AtomicLong(0L)
      val resume = new AtomicReference(Sentinel)
      val canceled = new AtomicReference(Sentinel)

      new StreamSubscription(
        stream,
        subscriber,
        requests,
        resume,
        canceled
      )
    }

  def subscribe[F[_], A](stream: Stream[F, A], subscriber: Subscriber[A])(implicit
      F: Async[F]
  ): F[Unit] =
    apply(stream, subscriber).flatMap { subscription =>
      F.delay(subscriber.onSubscribe(subscription)) >>
        subscription.run
    }
}
