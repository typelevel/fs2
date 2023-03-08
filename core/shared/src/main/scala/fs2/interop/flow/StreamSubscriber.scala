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

import java.util.Objects.requireNonNull
import java.util.concurrent.Flow.{Subscriber, Subscription}
import java.util.concurrent.atomic.AtomicReference
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

/** Implementation of a [[Subscriber]].
  *
  * This is used to obtain a [[Stream]] from an upstream reactive-streams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code]]
  */
private[flow] final class StreamSubscriber[F[_], A] private (
    chunkSize: Int,
    currentState: AtomicReference[(StreamSubscriber.State[A], () => Unit)]
)(implicit
    F: Async[F]
) extends Subscriber[A] {
  import StreamSubscriber.noop
  import StreamSubscriber.StreamSubscriberException._
  import StreamSubscriber.State._
  import StreamSubscriber.Input._

  // Subscriber API.

  /** Receives a subscription from the upstream reactive-streams system. */
  override def onSubscribe(subscription: Subscription): Unit = {
    requireNonNull(
      subscription,
      "The subscription provided to onSubscribe must not be null"
    )
    nextState(Subscribe(subscription))
  }

  /** Receives the next record from the upstream reactive-streams system. */
  override def onNext(a: A): Unit = {
    requireNonNull(
      a,
      "The element provided to onNext must not be null"
    )
    nextState(Next(a))
  }

  /** Called by the upstream reactive-streams system when it fails. */
  override def onError(ex: Throwable): Unit = {
    requireNonNull(
      ex,
      "The throwable provided to onError must not be null"
    )
    nextState(Error(ex))
  }

  /** Called by the upstream reactive-streams system when it has finished sending records. */
  override def onComplete(): Unit =
    nextState(Complete(canceled = false))

  // Interop API.

  /** Creates a downstream [[Stream]] from this [[Subscriber]]. */
  private[flow] def stream(subscribe: F[Unit]): Stream[F, A] = {
    // Called when downstream has finished consuming records.
    val finalize =
      F.delay(nextState(Complete(canceled = true)))

    // Producer for downstream.
    val dequeue1 =
      F.async[Option[Chunk[A]]] { cb =>
        F.delay {
          nextState(Dequeue(cb))

          Some(finalize)
        }
      }

    Stream.bracket(subscribe)(_ => finalize) >>
      Stream
        .repeatEval(dequeue1)
        .unNoneTerminate
        .unchunks
  }

  // Finite state machine.

  /** Helper to reduce noise when creating unary functions. */
  private def run(block: => Unit): () => Unit = () => block

  /** Runs a single step of the state machine. */
  private def step(in: Input[A]): State[A] => (State[A], () => Unit) =
    in match {
      case Subscribe(s) => {
        case Uninitialized(None) =>
          Idle(s) -> noop

        case Uninitialized(Some(cb)) =>
          WaitingOnUpstream(idx = 0, buffer = null, cb, s) -> run {
            s.request(chunkSize.toLong)
          }

        case state =>
          Failed(new InvalidStateException(operation = "Received subscription", state)) -> run {
            s.cancel()
          }
      }

      case Next(a) => {
        case WaitingOnUpstream(idx, buffer, cb, s) =>
          implicit val ct = ClassTag[A](a.getClass)
          val newIdx = idx + 1

          if (chunkSize == 1) {
            Idle(s) -> run {
              cb.apply(Right(Some(Chunk.singleton(a))))
            }
          } else if (idx == 0) {
            val newBuffer = new Array[A](chunkSize)
            WaitingOnUpstream(newIdx, newBuffer, cb, s) -> run {
              newBuffer.update(idx, a)
            }
          } else if (newIdx == chunkSize) {
            Idle(s) -> run {
              buffer.update(idx, a)
              cb.apply(Right(Some(Chunk.array(buffer))))
            }
          } else {
            WaitingOnUpstream(newIdx, buffer, cb, s) -> run {
              buffer.update(idx, a)
            }
          }

        case Terminal() =>
          Terminal() -> noop

        case state @ Idle(s) =>
          Failed(new InvalidStateException(operation = s"Received record [${a}]", state)) -> run {
            s.cancel()
          }

        case state =>
          Failed(new InvalidStateException(operation = s"Received record [${a}]", state)) -> noop
      }

      case Error(ex) => {
        case Uninitialized(Some(cb)) =>
          Terminal() -> run {
            cb.apply(Left(ex))
          }

        case WaitingOnUpstream(_, _, cb, _) =>
          Terminal() -> run {
            cb.apply(Left(ex))
          }

        case _ =>
          Failed(new UpstreamErrorException(ex)) -> noop
      }

      case Complete(canceled) => {
        case Uninitialized(Some(cb)) =>
          Terminal() -> run {
            cb.apply(Right(None))
          }

        case Idle(s) =>
          Terminal() -> run {
            if (canceled) {
              s.cancel()
            }
          }

        case WaitingOnUpstream(idx, buffer, cb, s) =>
          Terminal() -> run {
            if (idx == 0) {
              cb.apply(Right(None))
            } else {
              implicit val ct: ClassTag[A] = ClassTag[A](buffer.head.getClass)
              cb.apply(Right(Some(Chunk.array(buffer, offset = 0, length = idx))))
            }

            if (canceled) {
              s.cancel()
            }
          }

        case Failed(ex) =>
          Failed(ex) -> noop

        case _ =>
          Terminal() -> noop
      }

      case Dequeue(cb) => {
        case Uninitialized(None) =>
          Uninitialized(Some(cb)) -> noop

        case Idle(s) =>
          WaitingOnUpstream(idx = 0, buffer = null, cb, s) -> run {
            s.request(chunkSize.toLong)
          }

        case state @ Uninitialized(Some(otherCB)) =>
          Terminal() -> run {
            val ex = Left(new InvalidStateException(operation = "Received request", state))
            otherCB.apply(ex)
            cb.apply(ex)
          }

        case state @ WaitingOnUpstream(_, _, otherCB, s) =>
          Terminal() -> run {
            s.cancel()

            val ex = Left(new InvalidStateException(operation = "Received request", state))
            otherCB.apply(ex)
            cb.apply(ex)
          }

        case Failed(ex) =>
          Terminal() -> run {
            cb.apply(Left(ex))
          }

        case Terminal() =>
          Terminal() -> run {
            cb.apply(Right(None))
          }
      }
    }

  /** Runs the next step of the state machine. */
  private def nextState(in: Input[A]): Unit = {
    val (_, effect) = currentState.updateAndGet { case (state, _) =>
      step(in)(state)
    }
    effect()
  }
}

private[flow] object StreamSubscriber {
  private final val noop = () => ()

  /** Instantiates a new [[StreamSubscriber]] for the given buffer size. */
  def apply[F[_], A](
      chunkSize: Int
  )(implicit F: Async[F]): F[StreamSubscriber[F, A]] = {
    require(chunkSize > 0, "The buffer size MUST be positive")

    F.delay {
      val currentState =
        new AtomicReference[(State[A], () => Unit)]((State.Uninitialized(cb = None), noop))

      new StreamSubscriber[F, A](
        chunkSize,
        currentState
      )
    }
  }

  private sealed abstract class StreamSubscriberException(msg: String, cause: Throwable = null)
      extends IllegalStateException(msg, cause)
      with NoStackTrace
  private object StreamSubscriberException {
    type StreamSubscriberException = StreamSubscriber.StreamSubscriberException

    final class InvalidStateException[A](operation: String, state: State[A])
        extends StreamSubscriberException(
          msg = s"${operation} in invalid state [${state}]"
        )
    final class UpstreamErrorException(ex: Throwable)
        extends StreamSubscriberException(
          msg = s"StreamSubscriber.onError: ${ex}",
          cause = ex
        )
  }

  private type CB[A] = Either[Throwable, Option[Chunk[A]]] => Unit

  /** A finite state machine describing the Subscriber. */
  private sealed trait State[A]
  private object State {
    type State[A] = StreamSubscriber.State[A]

    final case class Uninitialized[A](cb: Option[CB[A]]) extends State[A]
    final case class Idle[A](s: Subscription) extends State[A]
    final case class WaitingOnUpstream[A](idx: Int, buffer: Array[A], cb: CB[A], s: Subscription)
        extends State[A]
    final case class Failed[A](ex: StreamSubscriberException) extends State[A]
    final case class Terminal[A]() extends State[A]
  }

  private sealed trait Input[A]
  private object Input {
    type Input[A] = StreamSubscriber.Input[A]

    final case class Subscribe[A](s: Subscription) extends Input[A]
    final case class Next[A](a: A) extends Input[A]
    final case class Error[A](ex: Throwable) extends Input[A]
    final case class Complete[A](canceled: Boolean) extends Input[A]
    final case class Dequeue[A](cb: CB[A]) extends Input[A]
  }
}
