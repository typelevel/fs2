package fs2.experimental.concurrent.pubsub

import cats.syntax.all._
import cats.effect.Concurrent

trait PubSub[F[_], I, O, Selector] extends fs2.concurrent.pubsub.PubSub[F, I, O, Selector]

object PubSub {

  def apply[F[_]: Concurrent, I, O, QS, Selector](
      strategy: PubSubStrategy[I, O, QS, Selector]): F[PubSub[F, I, O, Selector]] =
    fs2.concurrent.pubsub.PubSub(strategy).map { self =>
      new PubSub[F, I, O, Selector] {
        def get(selector: Selector): F[O] = self.get(selector)
        def tryGet(selector: Selector): F[Option[O]] = self.tryGet(selector)
        def subscribe(selector: Selector): F[Boolean] = self.subscribe(selector)
        def unsubscribe(selector: Selector): F[Unit] = self.unsubscribe(selector)
        def publish(a: I): F[Unit] = self.publish(a)
        def tryPublish(a: I): F[Boolean] = self.tryPublish(a)
      }
    }

}
