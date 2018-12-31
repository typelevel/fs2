package fs2
package interop
package reactivestreams

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import fs2._
import fs2.concurrent.{Queue, SignallingRef}
import org.reactivestreams._

/**
  * Implementation of a `org.reactivestreams.Subscription`.
  *
  * This is used by the [[StreamUnicastPublisher]] to send elements from a `fs2.Stream` to a downstream reactivestreams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code]]
  */
private[reactivestreams] final class StreamSubscription[F[_], A](
    requests: Queue[F, StreamSubscription.Request],
    cancelled: SignallingRef[F, Boolean],
    sub: Subscriber[A],
    stream: Stream[F, A])(implicit F: ConcurrentEffect[F])
    extends Subscription {
  import StreamSubscription._

  // We want to make sure `cancelled` is set _before_ signalling the subscriber
  def onError(e: Throwable) = cancelled.set(true) >> F.delay(sub.onError(e))
  def onComplete = cancelled.set(true) >> F.delay(sub.onComplete)

  def unsafeStart(): Unit = {
    def subscriptionPipe: Pipe[F, A, A] =
      in => {
        def go(s: Stream[F, A]): Pull[F, A, Unit] =
          Pull.eval(requests.dequeue1).flatMap {
            case Infinite => s.pull.echo
            case Finite(n) =>
              s.pull.take(n).flatMap {
                case None      => Pull.done
                case Some(rem) => go(rem)
              }
          }

        go(in).stream
      }

    val s =
      stream
        .through(subscriptionPipe)
        .interruptWhen(cancelled)
        .evalMap(x => F.delay(sub.onNext(x)))
        .handleErrorWith(e => Stream.eval(onError(e)))
        .onFinalize(cancelled.get.ifM(ifTrue = F.unit, ifFalse = onComplete))
        .compile
        .drain

    s.unsafeRunAsync
  }

  // According to the spec, it's acceptable for a concurrent cancel to not
  // be processed immediately, bt if you have synchronous `cancel();
  // request()`, then the request _must_ be a no op. For this reason, we
  // need to make sure that `cancel()` does not return until the
  // `cancelled` signal has been set.
  // See https://github.com/zainab-ali/fs2-reactive-streams/issues/29
  // and https://github.com/zainab-ali/fs2-reactive-streams/issues/46
  def cancel(): Unit =
    cancelled.set(true).toIO.unsafeRunSync

  def request(n: Long): Unit = {
    val request: F[Request] =
      if (n == java.lang.Long.MAX_VALUE) (Infinite: Request).pure[F]
      else if (n > 0) (Finite(n): Request).pure[F]
      else F.raiseError(new IllegalArgumentException(s"3.9 - invalid number of elements [$n]"))

    val prog = cancelled.get
      .ifM(ifTrue = F.unit, ifFalse = request.flatMap(requests.enqueue1).handleErrorWith(onError))

    prog.unsafeRunAsync
  }
}

private[reactivestreams] object StreamSubscription {

  /** Represents a downstream subscriber's request to publish elements */
  sealed trait Request
  case object Infinite extends Request
  case class Finite(n: Long) extends Request

  def apply[F[_]: ConcurrentEffect, A](sub: Subscriber[A],
                                       stream: Stream[F, A]): F[StreamSubscription[F, A]] =
    SignallingRef(false).flatMap { cancelled =>
      Queue.unbounded[F, Request].map { requests =>
        new StreamSubscription(requests, cancelled, sub, stream)
      }
    }
}
