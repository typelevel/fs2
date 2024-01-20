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
import scala.util.control.NoStackTrace

/** Implementation of a [[Subscriber]].
  *
  * This is used to obtain a [[Stream]] from an upstream reactive-streams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code]]
  */
private[flow] final class StreamSubscriber[F[_], A] private (
    chunkSize: Int,
    currentState: AtomicReference[(StreamSubscriber.State, () => Unit)]
)(implicit
    F: Async[F]
) extends Subscriber[A] {
  import StreamSubscriber.noop
  import StreamSubscriber.StreamSubscriberException._
  import StreamSubscriber.State._
  import StreamSubscriber.Input._

  // Subscriber API.

  /** Receives a subscription from the upstream reactive-streams system. */
  override final def onSubscribe(subscription: Subscription): Unit = {
    requireNonNull(
      subscription,
      "The subscription provided to onSubscribe must not be null"
    )
    nextState(input = Subscribe(subscription))
  }

  /** Receives the next record from the upstream reactive-streams system. */
  override final def onNext(a: A): Unit = {
    requireNonNull(
      a,
      "The element provided to onNext must not be null"
    )
    nextState(input = Next(a))
  }

  /** Called by the upstream reactive-streams system when it fails. */
  override final def onError(ex: Throwable): Unit = {
    requireNonNull(
      ex,
      "The throwable provided to onError must not be null"
    )
    nextState(input = Error(ex))
  }

  /** Called by the upstream reactive-streams system when it has finished sending records. */
  override final def onComplete(): Unit =
    nextState(input = Complete(canceled = false))

  // Interop API.

  /** Creates a downstream [[Stream]] from this [[Subscriber]]. */
  private[flow] def stream(subscribe: F[Unit]): Stream[F, A] = {
    // Called when downstream has finished consuming records.
    val finalize =
      F.delay(nextState(input = Complete(canceled = true)))

    // Producer for downstream.
    val dequeue1 =
      F.async[Option[Chunk[Any]]] { cb =>
        F.delay {
          nextState(input = Dequeue(cb))

          Some(finalize)
        }
      }

    Stream.bracket(subscribe)(_ => finalize) >>
      Stream
        .repeatEval(dequeue1)
        .unNoneTerminate
        .unchunks
        .asInstanceOf[Stream[F, A]] // This cast is safe, since all elements come from onNext(a: A).
  }

  // Finite state machine.

  /** Helper to reduce noise when creating unary functions. */
  private def run(block: => Unit): () => Unit = () => block

  /** Runs a single step of the state machine. */
  private def step(input: Input): State => (State, () => Unit) =
    input match {
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
          val newIdx = idx + 1

          if (chunkSize == 1) {
            Idle(s) -> run {
              cb.apply(Right(Some(Chunk.singleton(a))))
            }
          } else if (idx == 0) {
            val newBuffer = new Array[Any](chunkSize)
            WaitingOnUpstream(newIdx, newBuffer, cb, s) -> run {
              // We do the update here, to ensure it happens after we have secured access to the index.
              newBuffer.update(idx, a)
            }
          } else if (newIdx == chunkSize) {
            Idle(s) -> run {
              // We do the update here, to ensure it happens after we have secured access to the index.
              buffer.update(idx, a)
              cb.apply(Right(Some(Chunk.array(buffer))))
            }
          } else {
            WaitingOnUpstream(newIdx, buffer, cb, s) -> run {
              // We do the update here, to ensure it happens after we have secured access to the index.
              buffer.update(idx, a)
            }
          }

        case Terminal =>
          Terminal -> noop

        case state @ Idle(s) =>
          Failed(new InvalidStateException(operation = s"Received record [${a}]", state)) -> run {
            s.cancel()
          }

        case state =>
          Failed(new InvalidStateException(operation = s"Received record [${a}]", state)) -> noop
      }

      case Error(ex) => {
        case Uninitialized(Some(cb)) =>
          Terminal -> run {
            cb.apply(Left(ex))
          }

        case WaitingOnUpstream(_, _, cb, _) =>
          Terminal -> run {
            cb.apply(Left(ex))
          }

        case _ =>
          Failed(new UpstreamErrorException(ex)) -> noop
      }

      case Complete(canceled) => {
        case Uninitialized(Some(cb)) =>
          Terminal -> run {
            cb.apply(Right(None))
          }

        case Idle(s) =>
          Terminal -> run {
            if (canceled) {
              s.cancel()
            }
          }

        case WaitingOnUpstream(idx, buffer, cb, s) =>
          Terminal -> run {
            if (idx == 0) {
              cb.apply(Right(None))
            } else {
              cb.apply(Right(Some(Chunk.array(buffer, offset = 0, length = idx))))
            }

            if (canceled) {
              s.cancel()
            }
          }

        case Failed(ex) =>
          Failed(ex) -> noop

        case _ =>
          Terminal -> noop
      }

      case Dequeue(cb) => {
        case Uninitialized(None) =>
          Uninitialized(Some(cb)) -> noop

        case Idle(s) =>
          WaitingOnUpstream(idx = 0, buffer = null, cb, s) -> run {
            s.request(chunkSize.toLong)
          }

        case state @ Uninitialized(Some(otherCB)) =>
          Terminal -> run {
            val ex = Left(new InvalidStateException(operation = "Received request", state))
            otherCB.apply(ex)
            cb.apply(ex)
          }

        case state @ WaitingOnUpstream(_, _, otherCB, s) =>
          Terminal -> run {
            s.cancel()

            val ex = Left(new InvalidStateException(operation = "Received request", state))
            otherCB.apply(ex)
            cb.apply(ex)
          }

        case Failed(ex) =>
          Terminal -> run {
            cb.apply(Left(ex))
          }

        case Terminal =>
          Terminal -> run {
            cb.apply(Right(None))
          }
      }
    }

  /** Runs the next step of fsm.
    *
    * This function is concurrent safe,
    * because the reactive-streams specs mention that all the on methods are to be called sequentially.
    * Additionally, `Dequeue` and `Next` can't never happen concurrently, since they are tied together.
    * Thus, these are the only concurrent options and all are covered:
    * + `Subscribe` & `Dequeue`: No matter the order in which they are processed, we will end with a request call and a null buffer.
    * + `Error` & `Dequeue`: No matter the order in which they are processed, we will complete the callback with the error.
    * + cancellation & any other thing: Worst case, we will lose some data that we not longer care about; and eventually reach `Terminal`.
    */
  private def nextState(input: Input): Unit = {
    val (_, effect) = currentState.updateAndGet { case (state, _) =>
      step(input)(state)
    }
    // Only run the effect after the state update took place.
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
        new AtomicReference[(State, () => Unit)]((State.Uninitialized(cb = None), noop))

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

    final class InvalidStateException(operation: String, state: State)
        extends StreamSubscriberException(
          msg = s"${operation} in invalid state [${state}]"
        )
    final class UpstreamErrorException(ex: Throwable)
        extends StreamSubscriberException(
          msg = s"StreamSubscriber.onError: ${ex}",
          cause = ex
        )
  }

  private type CB = Either[Throwable, Option[Chunk[Any]]] => Unit

  /** A finite state machine describing the Subscriber. */
  private sealed trait State
  private object State {
    type State = StreamSubscriber.State

    final case class Uninitialized(cb: Option[CB]) extends State
    final case class Idle(s: Subscription) extends State
    // Having an Array inside the state is fine,
    // because the reactive streams spec ensures that all signals must be sent and processed sequentially.
    // Additionally, we ensure that the modifications happens only after we ensure they are safe;
    // since they are always done on the effect run after the state update took place.
    final case class WaitingOnUpstream(idx: Int, buffer: Array[Any], cb: CB, s: Subscription)
        extends State
    final case class Failed(ex: StreamSubscriberException) extends State
    case object Terminal extends State
  }

  private sealed trait Input
  private object Input {
    type Input = StreamSubscriber.Input

    final case class Subscribe(s: Subscription) extends Input
    final case class Next(a: Any) extends Input
    final case class Error(ex: Throwable) extends Input
    final case class Complete(canceled: Boolean) extends Input
    final case class Dequeue(cb: CB) extends Input
  }
}
