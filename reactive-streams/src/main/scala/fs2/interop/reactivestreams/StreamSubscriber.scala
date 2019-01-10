package fs2
package interop
package reactivestreams

import cats._
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import org.reactivestreams._

/**
  * Implementation of a `org.reactivestreams.Subscriber`.
  *
  * This is used to obtain a `fs2.Stream` from an upstream reactivestreams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#2-subscriber-code]]
  */
final class StreamSubscriber[F[_]: ConcurrentEffect, A](val sub: StreamSubscriber.FSM[F, A])
    extends Subscriber[A] {

  /** Called by an upstream reactivestreams system */
  def onSubscribe(s: Subscription): Unit = {
    nonNull(s)
    sub.onSubscribe(s).unsafeRunAsync
  }

  /** Called by an upstream reactivestreams system */
  def onNext(a: A): Unit = {
    nonNull(a)
    sub.onNext(a).unsafeRunAsync
  }

  /** Called by an upstream reactivestreams system */
  def onComplete(): Unit = sub.onComplete.unsafeRunAsync

  /** Called by an upstream reactivestreams system */
  def onError(t: Throwable): Unit = {
    nonNull(t)
    sub.onError(t).unsafeRunAsync
  }

  /** Obtain a fs2.Stream */
  def stream: Stream[F, A] = sub.stream

  private def nonNull[B](b: B): Unit = if (b == null) throw new NullPointerException()
}

object StreamSubscriber {
  def apply[F[_]: ConcurrentEffect, A]: F[StreamSubscriber[F, A]] =
    fsm[F, A].map(new StreamSubscriber(_))

  /** A finite state machine describing the subscriber */
  private[reactivestreams] trait FSM[F[_], A] {

    /** receives a subscription from upstream */
    def onSubscribe(s: Subscription): F[Unit]

    /** receives next record from upstream */
    def onNext(a: A): F[Unit]

    /** receives error from upstream */
    def onError(t: Throwable): F[Unit]

    /** called when upstream has finished sending records */
    def onComplete: F[Unit]

    /** called when downstream has finished consuming records */
    def onFinalize: F[Unit]

    /** producer for downstream */
    def dequeue1: F[Either[Throwable, Option[A]]]

    /** downstream stream */
    def stream(implicit ev: ApplicativeError[F, Throwable]): Stream[F, A] =
      Stream.eval(dequeue1).repeat.rethrow.unNoneTerminate.onFinalize(onFinalize)
  }

  private[reactivestreams] def fsm[F[_], A](implicit F: Concurrent[F]): F[FSM[F, A]] = {

    type Out = Either[Throwable, Option[A]]

    sealed trait Input
    case class OnSubscribe(s: Subscription) extends Input
    case class OnNext(a: A) extends Input
    case class OnError(e: Throwable) extends Input
    case object OnComplete extends Input
    case object OnFinalize extends Input
    case class OnDequeue(response: Deferred[F, Out]) extends Input

    sealed trait State
    case object Uninitialized extends State
    case class Idle(sub: Subscription) extends State
    case class RequestBeforeSubscription(req: Deferred[F, Out]) extends State
    case class WaitingOnUpstream(sub: Subscription, elementRequest: Deferred[F, Out]) extends State
    case object UpstreamCompletion extends State
    case object DownstreamCancellation extends State
    case class UpstreamError(err: Throwable) extends State

    def step(in: Input): State => (State, F[Unit]) = in match {
      case OnSubscribe(s) => {
        case RequestBeforeSubscription(req) => WaitingOnUpstream(s, req) -> F.delay(s.request(1))
        case Uninitialized                  => Idle(s) -> F.unit
        case o =>
          val err = new Error(s"received subscription in invalid state [$o]")
          o -> (F.delay(s.cancel) >> F.raiseError(err))
      }
      case OnNext(a) => {
        case WaitingOnUpstream(s, r) => Idle(s) -> r.complete(a.some.asRight)
        case DownstreamCancellation  => DownstreamCancellation -> F.unit
        case o                       => o -> F.raiseError(new Error(s"received record [$a] in invalid state [$o]"))
      }
      case OnComplete => {
        case WaitingOnUpstream(sub, r) => UpstreamCompletion -> r.complete(None.asRight)
        case o                         => UpstreamCompletion -> F.unit
      }
      case OnError(e) => {
        case WaitingOnUpstream(_, r) => UpstreamError(e) -> r.complete(e.asLeft)
        case o                       => UpstreamError(e) -> F.unit
      }
      case OnFinalize => {
        case WaitingOnUpstream(sub, r) =>
          DownstreamCancellation -> (F.delay(sub.cancel) >> r.complete(None.asRight))
        case Idle(sub) => DownstreamCancellation -> F.delay(sub.cancel)
        case o         => o -> F.unit
      }
      case OnDequeue(r) => {
        case Uninitialized          => RequestBeforeSubscription(r) -> F.unit
        case Idle(sub)              => WaitingOnUpstream(sub, r) -> F.delay(sub.request(1))
        case err @ UpstreamError(e) => err -> r.complete(e.asLeft)
        case UpstreamCompletion     => UpstreamCompletion -> r.complete(None.asRight)
        case o                      => o -> r.complete((new Error(s"received request in invalid state [$o]")).asLeft)
      }
    }

    Ref.of[F, State](Uninitialized).map { ref =>
      new FSM[F, A] {
        def nextState(in: Input): F[Unit] = ref.modify(step(in)).flatten
        def onSubscribe(s: Subscription): F[Unit] = nextState(OnSubscribe(s))
        def onNext(a: A): F[Unit] = nextState(OnNext(a))
        def onError(t: Throwable): F[Unit] = nextState(OnError(t))
        def onComplete: F[Unit] = nextState(OnComplete)
        def onFinalize: F[Unit] = nextState(OnFinalize)
        def dequeue1: F[Either[Throwable, Option[A]]] =
          Deferred[F, Out].flatMap { p =>
            ref.modify(step(OnDequeue(p))).flatten >> p.get
          }
      }
    }
  }

}
