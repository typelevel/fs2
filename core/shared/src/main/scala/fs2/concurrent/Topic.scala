package fs2
package concurrent

import cats.effect.Concurrent

/**
  * Asynchronous Topic.
  *
  * Topic allows you to distribute `A` published by arbitrary number of publishers to arbitrary number of subscribers.
  *
  * Topic has built-in back-pressure support implemented as maximum bound (`maxQueued`) that a subscriber is allowed to enqueue.
  * Once that bound is hit, publishing may semantically block until the lagging subscriber consumes some of its queued elements.
  *
  * Additionally the subscriber has possibility to terminate whenever size of enqueued elements is over certain size
  * by using `subscribeSize`.
  */
abstract class Topic[F[_], A] { self =>

  /**
    * Publishes elements from source of `A` to this topic.
    * [[Pipe]] equivalent of `publish1`.
    */
  def publish: Pipe[F, A, Unit]

  /**
    * Publishes one `A` to topic.
    *
    * This waits until `a` is published to all subscribers.
    * If any of the subscribers is over the `maxQueued` limit, this will wait to complete until that subscriber processes
    * enough of its elements such that `a` is enqueued.
    */
  def publish1(a: A): F[Unit]

  /**
    * Subscribes for `A` values that are published to this topic.
    *
    * Pulling on the returned stream opens a "subscription", which allows up to
    * `maxQueued` elements to be enqueued as a result of publication.
    *
    * The first element in the stream is always the last published `A` at the time
    * the stream is first pulled from, followed by each published `A` value from that
    * point forward.
    *
    * If at any point, the queue backing the subscription has `maxQueued` elements in it,
    * any further publications semantically block until elements are dequeued from the
    * subscription queue.
    *
    * @param maxQueued maximum number of elements to enqueue to the subscription
    * queue before blocking publishers
    */
  def subscribe(maxQueued: Int): Stream[F, A]

  /**
    * Like [[subscribe]] but emits an approximate number of queued elements for this subscription
    * with each emitted `A` value.
    */
  def subscribeSize(maxQueued: Int): Stream[F, (A, Int)]

  /**
    * Signal of current active subscribers.
    */
  def subscribers: Stream[F, Int]

  /**
    * Returns an alternate view of this `Topic` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A): Topic[F, B] =
    new Topic[F, B] {
      def publish: Pipe[F, B, Unit] = sfb => self.publish(sfb.map(g))
      def publish1(b: B): F[Unit] = self.publish1(g(b))
      def subscribe(maxQueued: Int): Stream[F, B] =
        self.subscribe(maxQueued).map(f)
      def subscribers: Stream[F, Int] = self.subscribers
      def subscribeSize(maxQueued: Int): Stream[F, (B, Int)] =
        self.subscribeSize(maxQueued).map { case (a, i) => f(a) -> i }
    }
}

object Topic {
  import cats.effect.concurrent.Ref
  import cats.implicits._

  def apply[F[_], A](initial: A)(implicit F: Concurrent[F]): F[Topic[F, A]] =
    Ref
      .of[F, List[fs2.concurrent.Queue[F, A]]](List.empty)
      .map(cache ⇒
        new Topic[F, A] {
          override def publish: Pipe[F, A, Unit] =
            _.evalMap(publish1)

          override def publish1(a: A): F[Unit] =
            cache.get.flatMap { subscribers ⇒
              subscribers.traverse(_.enqueue1(a)).void
            }

          override def subscribe(maxQueued: Int): fs2.Stream[F, A] = {
            def emptyQueue(maxQueued: Int): fs2.Stream[F, fs2.concurrent.Queue[F, A]] =
              fs2.Stream.bracket(fs2.concurrent.Queue.bounded[F, A](maxQueued))(
                queue ⇒ cache.update(_.filter(_ ne queue))
              )
            emptyQueue(maxQueued)
              .evalTap(_.enqueue1(initial))
              .evalTap(q ⇒ cache.update(_ :+ q))
              .flatMap(_.dequeue)
          }

          override def subscribeSize(maxQueued: Int): fs2.Stream[F, (A, Int)] = {
            def emptyQueue(maxQueued: Int): fs2.Stream[F, fs2.concurrent.InspectableQueue[F, A]] =
              fs2.Stream.bracket(fs2.concurrent.InspectableQueue.bounded[F, A](maxQueued))(
                queue ⇒ cache.update(_.filter(_ ne queue))
              )
            for {
              queue ← emptyQueue(maxQueued)
                .evalTap(_.enqueue1(initial))
                .evalTap(q ⇒ cache.update(_ :+ q))
              element ← queue.dequeue
              number ← queue.size
            } yield {
              (element, number)
            }
          }

          override def subscribers: fs2.Stream[F, Int] =
            fs2.Stream.eval(cache.get.map(_.size))
      })
}
