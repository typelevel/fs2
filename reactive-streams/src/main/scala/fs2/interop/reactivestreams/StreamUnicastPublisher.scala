package fs2
package interop
package reactivestreams

import cats.effect._
import cats.implicits._
import org.reactivestreams._

/**
  * Implementation of a `org.reactivestreams.Publisher`
  *
  * This is used to publish elements from a `fs2.Stream` to a downstream reactivestreams system.
  *
  * @see [[https://github.com/reactive-streams/reactive-streams-jvm#1-publisher-code]]
  */
final class StreamUnicastPublisher[F[_]: ConcurrentEffect, A](val stream: Stream[F, A])
    extends Publisher[A] {
  def subscribe(subscriber: Subscriber[_ >: A]): Unit = {
    nonNull(subscriber)
    StreamSubscription(subscriber, stream)
      .flatMap { subscription =>
        Sync[F].delay {
          subscriber.onSubscribe(subscription)
          subscription.unsafeStart()
        }
      }
      .unsafeRunAsync()
  }

  private def nonNull[B](b: B): Unit = if (b == null) throw new NullPointerException()
}

object StreamUnicastPublisher {
  def apply[F[_]: ConcurrentEffect, A](s: Stream[F, A]): StreamUnicastPublisher[F, A] =
    new StreamUnicastPublisher(s)
}
