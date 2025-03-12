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

  /** OnNext state.
    * This is concurrent unsafe,
    * however the reactive-streams specification demands that these operations are called serially:
    * https://github.com/reactive-streams/reactive-streams-jvm?tab=readme-ov-file#1.3
    * Additionally, we ensure that the modifications happens only after we ensure they are safe;
    * since they are always done on the effect run after the state update took place.
    * Meaning this should be correct if the Producer is well-behaved.
    */
  private var inOnNextLoop: Boolean = false
  private var buffer: Array[Any] = null
  private var index: Int = 0

  /** Receives the next record from the upstream reactive-streams system. */
  override final def onNext(a: A): Unit = {
    requireNonNull(
      a,
      "The element provided to onNext must not be null"
    )

    // Optimized onNext loop.
    if (inOnNextLoop) {
      // If we are here, we can assume the array is properly initialized.
      buffer(index) = a
      index += 1
      if (index == chunkSize) {
        nextState(input = CompleteNext)
      }
    } else {
      nextState(input = InitialNext(a))
    }
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
          WaitingOnUpstream(cb, s) -> run {
            s.request(chunkSize.toLong)
          }

        case state =>
          Failed(new InvalidStateException(operation = "Received subscription", state)) -> run {
            s.cancel()
          }
      }

      case InitialNext(a) => {
        case state @ WaitingOnUpstream(cb, s) =>
          if (chunkSize == 1) {
            // Optimization for when the chunkSize is 1.
            Idle(s) -> run {
              cb.apply(Right(Some(Chunk.singleton(a))))
            }
          } else {
            // We start the onNext tight loop.
            state -> run {
              // We do the updates here,
              // to ensure they happen after we have secured the state.
              inOnNextLoop = true
              buffer = new Array(chunkSize)
              buffer(0) = a
              index = 1
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

      case CompleteNext => {
        case WaitingOnUpstream(cb, s) =>
          Idle(s) -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            val chunk = Chunk.array(buffer)
            inOnNextLoop = false
            buffer = null
            cb.apply(Right(Some(chunk)))
          }

        case state =>
          Failed(
            new InvalidStateException(operation = s"Received record [${buffer.last}]", state)
          ) -> run {
            inOnNextLoop = false
            buffer = null
          }
      }

      case Error(ex) => {
        case Uninitialized(Some(cb)) =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
            cb.apply(Left(ex))
          }

        case WaitingOnUpstream(cb, _) =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
            cb.apply(Left(ex))
          }

        case _ =>
          Failed(new UpstreamErrorException(ex)) -> noop
      }

      case Complete(canceled) => {
        case Uninitialized(Some(cb)) =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
            cb.apply(Right(None))
          }

        case Idle(s) =>
          Terminal -> run {
            if (canceled) {
              s.cancel()
            }
          }

        case WaitingOnUpstream(cb, s) =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            if (canceled) {
              s.cancel()
              inOnNextLoop = false
              buffer = null
              cb.apply(Right(None))
            } else if (buffer eq null) {
              inOnNextLoop = false
              cb.apply(Right(None))
            } else {
              val chunk = Chunk.array(buffer, offset = 0, length = index)
              inOnNextLoop = false
              buffer = null
              cb.apply(Right(Some(chunk)))
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
          WaitingOnUpstream(cb, s) -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
            s.request(chunkSize.toLong)
          }

        case state @ Uninitialized(Some(otherCB)) =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
            val ex = Left(new InvalidStateException(operation = "Received request", state))
            otherCB.apply(ex)
            cb.apply(ex)
          }

        case state @ WaitingOnUpstream(otherCB, s) =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
            s.cancel()
            val ex = Left(new InvalidStateException(operation = "Received request", state))
            otherCB.apply(ex)
            cb.apply(ex)
          }

        case Failed(ex) =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
            cb.apply(Left(ex))
          }

        case Terminal =>
          Terminal -> run {
            // We do the updates here,
            // to ensure they happen after we have secured the state.
            inOnNextLoop = false
            buffer = null
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
    final case class WaitingOnUpstream(cb: CB, s: Subscription) extends State
    final case class Failed(ex: StreamSubscriberException) extends State
    case object Terminal extends State
  }

  private sealed trait Input
  private object Input {
    type Input = StreamSubscriber.Input

    final case class Subscribe(s: Subscription) extends Input
    final case class InitialNext(a: Any) extends Input
    case object CompleteNext extends Input
    final case class Error(ex: Throwable) extends Input
    final case class Complete(canceled: Boolean) extends Input
    final case class Dequeue(cb: CB) extends Input
  }
}
