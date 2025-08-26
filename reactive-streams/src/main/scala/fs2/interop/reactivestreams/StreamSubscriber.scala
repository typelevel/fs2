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

import cats._
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._
import org.reactivestreams._

import java.util.concurrent.atomic.AtomicReference

/** Implementation of a `org.reactivestreams.Subscriber`.
  *
  * This is used to obtain a `fs2.Stream` from an upstream reactivestreams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code]]
  */
final class StreamSubscriber[F[_], A](
    val sub: StreamSubscriber.FSM[F, A]
)(implicit
    F: ApplicativeError[F, Throwable]
) extends Subscriber[A] {

  @deprecated("Use a constructor without dispatcher instead", "3.1.4")
  def this(sub: StreamSubscriber.FSM[F, A], dispatcher: Dispatcher[F])(implicit
      F: ApplicativeError[F, Throwable]
  ) = this(sub)

  /** Called by an upstream reactivestreams system */
  def onSubscribe(s: Subscription): Unit = {
    nonNull(s)
    sub.onSubscribe(s)
  }

  /** Called by an upstream reactivestreams system */
  def onNext(a: A): Unit = {
    nonNull(a)
    sub.onNext(a)
  }

  /** Called by an upstream reactivestreams system */
  def onComplete(): Unit = sub.onComplete()

  /** Called by an upstream reactivestreams system */
  def onError(t: Throwable): Unit = {
    nonNull(t)
    sub.onError(t)
  }

  def stream(subscribe: F[Unit]): Stream[F, A] = sub.stream(subscribe)

  private def nonNull[B](b: B): Unit = if (b == null) throw new NullPointerException()
}

object StreamSubscriber {

  def apply[F[_]: Async, A](bufferSize: Int): F[StreamSubscriber[F, A]] =
    fsm[F, A](bufferSize).map(new StreamSubscriber(_))

  @deprecated(
    "Use apply method with a buffer size. Use a buffer size of 1 to keep the same behavior.",
    "3.1.4"
  )
  def apply[F[_]: Async, A]: F[StreamSubscriber[F, A]] =
    apply(bufferSize = 1)

  @deprecated("Use apply method without dispatcher instead", "3.1.4")
  def apply[F[_]: Async, A](dispatcher: Dispatcher[F]): F[StreamSubscriber[F, A]] =
    apply[F, A]

  /** A finite state machine describing the subscriber */
  private[reactivestreams] trait FSM[F[_], A] {

    /** receives a subscription from upstream */
    def onSubscribe(s: Subscription): Unit

    /** receives next record from upstream */
    def onNext(a: A): Unit

    /** receives error from upstream */
    def onError(t: Throwable): Unit

    /** called when upstream has finished sending records */
    def onComplete(): Unit

    /** called when downstream has finished consuming records */
    def onFinalize: F[Unit]

    /** producer for downstream */
    def dequeue1: F[Either[Throwable, Option[Chunk[A]]]]

    /** downstream stream */
    def stream(subscribe: F[Unit])(implicit ev: ApplicativeError[F, Throwable]): Stream[F, A] =
      Stream.bracket(subscribe)(_ => onFinalize) >> Stream
        .eval(dequeue1)
        .repeat
        .rethrow
        .unNoneTerminate
        .unchunks
  }

  private[reactivestreams] def fsm[F[_], A](
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
    case class WaitingOnUpstream(sub: Subscription, buffer: Chunk[A], elementRequest: Out => Unit)
        extends State
    case object UpstreamCompletion extends State
    case object DownstreamCancellation extends State
    case class UpstreamError(err: Throwable) extends State

    def step(in: Input)(reportFailure: Throwable => Unit): State => (State, () => Unit) =
      in match {
        case OnSubscribe(s) => {
          case RequestBeforeSubscription(req) =>
            WaitingOnUpstream(s, Chunk.empty, req) -> (() => s.request(bufferSize.toLong))
          case Uninitialized => Idle(s, Chunk.empty) -> (() => ())
          case o             =>
            val err = new Error(s"received subscription in invalid state [$o]")
            o -> { () =>
              s.cancel()
              reportFailure(err)
            }
        }
        case OnNext(a) => {
          case WaitingOnUpstream(s, buffer, r) =>
            val newBuffer = buffer ++ Chunk(a)
            if (newBuffer.size == bufferSize) {
              Idle(s, Chunk.empty) -> (() => r(newBuffer.some.asRight))
            } else
              WaitingOnUpstream(s, newBuffer, r) -> (() => ())
          case DownstreamCancellation => DownstreamCancellation -> (() => ())
          case o                      =>
            o -> (() => reportFailure(new Error(s"received record [$a] in invalid state [$o]")))
        }
        case OnComplete => {
          case WaitingOnUpstream(_, buffer, r) =>
            if (buffer.nonEmpty) {
              UpstreamCompletion -> (() => r(buffer.some.asRight))
            } else {
              UpstreamCompletion -> (() => r(None.asRight))
            }
          case _ => UpstreamCompletion -> (() => ())
        }
        case OnError(e) => {
          case WaitingOnUpstream(_, _, r) => UpstreamError(e) -> (() => r(e.asLeft))
          case _                          => UpstreamError(e) -> (() => ())
        }
        case OnFinalize => {
          case WaitingOnUpstream(sub, _, r) =>
            DownstreamCancellation -> { () =>
              sub.cancel()
              r(None.asRight)
            }
          case Idle(sub, _) => DownstreamCancellation -> (() => sub.cancel())
          case o            => o -> (() => ())
        }
        case OnDequeue(r) => {
          case Uninitialized     => RequestBeforeSubscription(r) -> (() => ())
          case Idle(sub, buffer) =>
            WaitingOnUpstream(sub, buffer, r) -> (() => sub.request(bufferSize.toLong))
          case err @ UpstreamError(e) => err -> (() => r(e.asLeft))
          case UpstreamCompletion     => UpstreamCompletion -> (() => r(None.asRight))
          case o => o -> (() => r(new Error(s"received request in invalid state [$o]").asLeft))
        }
      }

    for {
      ref <- F.delay(new AtomicReference[(State, () => Unit)]((Uninitialized, () => ())))
      executionContext <- F.executionContext
    } yield new FSM[F, A] {
      def nextState(in: Input): Unit = {
        val (_, effect) = ref.updateAndGet { case (state, _) =>
          step(in)(executionContext.reportFailure)(state)
        }
        effect()
      }
      def onSubscribe(s: Subscription): Unit = nextState(OnSubscribe(s))
      def onNext(a: A): Unit = nextState(OnNext(a))
      def onError(t: Throwable): Unit = nextState(OnError(t))
      def onComplete(): Unit = nextState(OnComplete)
      def onFinalize: F[Unit] = F.delay(nextState(OnFinalize))
      def dequeue1: F[Either[Throwable, Option[Chunk[A]]]] =
        F.async_[Either[Throwable, Option[Chunk[A]]]] { cb =>
          nextState(OnDequeue(out => cb(Right(out))))
        }
    }
  }
}
