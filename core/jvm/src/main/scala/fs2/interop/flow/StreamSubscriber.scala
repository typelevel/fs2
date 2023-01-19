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

import cats.MonadThrow
import cats.effect.kernel.Async
import cats.syntax.all._

import java.util.Objects.requireNonNull
import java.util.concurrent.Flow.{Subscriber, Subscription}
import java.util.concurrent.atomic.AtomicReference

/** Implementation of a [[Subscriber]].
  *
  * This is used to obtain a [[Stream]] from an upstream reactive-streams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code]]
  */
private[flow] final class StreamSubscriber[F[_], A] private (
    private[flow] val subscriber: StreamSubscriber.FSM[F, A]
)(implicit
    F: MonadThrow[F]
) extends Subscriber[A] {

  /** Called by an upstream reactive-streams system. */
  override def onSubscribe(subscription: Subscription): Unit = {
    requireNonNull(
      subscription,
      "The subscription provided to onSubscribe must not be null"
    )
    subscriber.onSubscribe(subscription)
  }

  /** Called by an upstream reactive-streams system. */
  override def onNext(a: A): Unit = {
    requireNonNull(
      a,
      "The element provided to onNext must not be null"
    )
    subscriber.onNext(a)
  }

  /** Called by an upstream reactive-streams system. */
  override def onComplete(): Unit =
    subscriber.onComplete()

  /** Called by an upstream reactive-streams system. */
  override def onError(t: Throwable): Unit = {
    requireNonNull(
      t,
      "The throwable provided to onError must not be null"
    )
    subscriber.onError(t)
  }

  /** Creates a [[Stream]] from this [[Subscriber]]. */
  def stream(subscribe: F[Unit]): Stream[F, A] =
    subscriber.stream(subscribe)
}

private[flow] object StreamSubscriber {

  /** Instantiates a new [[StreamSubscriber]] for the given buffer size. */
  def apply[F[_], A](bufferSize: Int)(implicit F: Async[F]): F[StreamSubscriber[F, A]] = {
    require(bufferSize > 0, "The buffer size MUST be positive")
    fsm[F, A](bufferSize).map(fsm => new StreamSubscriber(subscriber = fsm))
  }

  /** A finite state machine describing the subscriber. */
  private[flow] trait FSM[F[_], A] {

    /** Receives a subscription from upstream. */
    def onSubscribe(s: Subscription): Unit

    /** Receives next record from upstream. */
    def onNext(a: A): Unit

    /** Receives error from upstream. */
    def onError(t: Throwable): Unit

    /** Called when upstream has finished sending records. */
    def onComplete(): Unit

    /** Called when downstream has finished consuming records. */
    def onFinalize: F[Unit]

    /** Producer for downstream. */
    def dequeue1: F[Either[Throwable, Option[Chunk[A]]]]

    /** Downstream [[Stream]]. */
    final def stream(subscribe: F[Unit])(implicit ev: MonadThrow[F]): Stream[F, A] =
      Stream.bracket(subscribe)(_ => onFinalize) >>
        Stream
          .eval(dequeue1)
          .repeat
          .rethrow
          .unNoneTerminate
          .unchunks
  }

  private def fsm[F[_], A](
      bufferSize: Int
  )(implicit F: Async[F]): F[FSM[F, A]] = {
    type Out = Either[Throwable, Option[Chunk[A]]]

    sealed trait Input
    case class OnSubscribe(s: Subscription) extends Input
    case class OnNext(a: A) extends Input
    case class OnError(e: Throwable) extends Input
    case object OnComplete extends Input
    case object OnFinalize extends Input
    case class OnDequeue(response: Out => Unit) extends Input

    sealed trait State
    case object Uninitialized extends State
    case class Idle(sub: Subscription, buffer: Chunk[A]) extends State
    case class RequestBeforeSubscription(req: Out => Unit) extends State
    case class WaitingOnUpstream(
        sub: Subscription,
        buffer: Chunk[A],
        elementRequest: Out => Unit
    ) extends State
    case object UpstreamCompletion extends State
    case object DownstreamCancellation extends State
    case class UpstreamError(err: Throwable) extends State

    def reportFailure(e: Throwable): Unit =
      Thread.getDefaultUncaughtExceptionHandler match {
        case null => e.printStackTrace()
        case h    => h.uncaughtException(Thread.currentThread(), e)
      }

    def step(in: Input): State => (State, () => Unit) =
      in match {
        case OnSubscribe(s) => {
          case RequestBeforeSubscription(req) =>
            WaitingOnUpstream(s, Chunk.empty, req) -> (() => s.request(bufferSize.toLong))

          case Uninitialized =>
            Idle(s, Chunk.empty) -> (() => ())

          case o =>
            val err = new Error(s"Received subscription in invalid state [${o}]")
            o -> { () =>
              s.cancel()
              reportFailure(err)
            }
        }

        case OnNext(a) => {
          case WaitingOnUpstream(s, buffer, r) =>
            val newBuffer = buffer ++ Chunk(a)
            if (newBuffer.size == bufferSize)
              Idle(s, Chunk.empty) -> (() => r(newBuffer.some.asRight))
            else
              WaitingOnUpstream(s, newBuffer, r) -> (() => ())

          case DownstreamCancellation =>
            DownstreamCancellation -> (() => ())

          case o =>
            o -> (() => reportFailure(new Error(s"Received record [${a}] in invalid state [${o}]")))
        }

        case OnComplete => {
          case WaitingOnUpstream(_, buffer, r) =>
            if (buffer.nonEmpty)
              UpstreamCompletion -> (() => r(buffer.some.asRight))
            else
              UpstreamCompletion -> (() => r(None.asRight))

          case _ =>
            UpstreamCompletion -> (() => ())
        }

        case OnError(e) => {
          case WaitingOnUpstream(_, _, r) =>
            UpstreamError(e) -> (() => r(e.asLeft))

          case _ =>
            UpstreamError(e) -> (() => ())
        }

        case OnFinalize => {
          case WaitingOnUpstream(sub, _, r) =>
            DownstreamCancellation -> { () =>
              sub.cancel()
              r(None.asRight)
            }

          case Idle(sub, _) =>
            DownstreamCancellation -> (() => sub.cancel())

          case o =>
            o -> (() => ())
        }

        case OnDequeue(r) => {
          case Uninitialized =>
            RequestBeforeSubscription(r) -> (() => ())

          case Idle(sub, buffer) =>
            WaitingOnUpstream(sub, buffer, r) -> (() => sub.request(bufferSize.toLong))

          case err @ UpstreamError(e) =>
            err -> (() => r(e.asLeft))

          case UpstreamCompletion =>
            UpstreamCompletion -> (() => r(None.asRight))

          case o =>
            o -> (() => r(new Error(s"Received request in invalid state [${o}]").asLeft))
        }
      }

    F.delay(new AtomicReference[(State, () => Unit)]((Uninitialized, () => ()))).map { ref =>
      new FSM[F, A] {
        def nextState(in: Input): Unit = {
          val (_, effect) = ref.updateAndGet { case (state, _) =>
            step(in)(state)
          }
          effect()
        }

        override final def onSubscribe(s: Subscription): Unit =
          nextState(OnSubscribe(s))

        override final def onNext(a: A): Unit =
          nextState(OnNext(a))

        override final def onError(t: Throwable): Unit =
          nextState(OnError(t))

        override final def onComplete(): Unit =
          nextState(OnComplete)

        override final val onFinalize: F[Unit] =
          F.delay(nextState(OnFinalize))

        override final val dequeue1: F[Either[Throwable, Option[Chunk[A]]]] =
          F.async_ { cb =>
            nextState(OnDequeue(out => cb(Right(out))))
          }
      }
    }
  }
}
