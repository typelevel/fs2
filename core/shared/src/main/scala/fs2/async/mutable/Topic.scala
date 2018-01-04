package fs2
package async
package mutable

import scala.concurrent.ExecutionContext

import cats.effect.Effect
import cats.implicits._

import fs2.Stream._

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
    * [[Sink]] equivalent of `publish1`.
    */
  def publish: Sink[F, A]

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
  def subscribers: fs2.async.immutable.Signal[F, Int]

  /**
    * Returns an alternate view of this `Topic` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A): Topic[F, B] =
    new Topic[F, B] {
      def publish: Sink[F, B] = sfb => self.publish(sfb.map(g))
      def publish1(b: B): F[Unit] = self.publish1(g(b))
      def subscribe(maxQueued: Int): Stream[F, B] =
        self.subscribe(maxQueued).map(f)
      def subscribers: fs2.async.immutable.Signal[F, Int] = self.subscribers
      def subscribeSize(maxQueued: Int): Stream[F, (B, Int)] =
        self.subscribeSize(maxQueued).map { case (a, i) => f(a) -> i }
    }
}

object Topic {

  def apply[F[_], A](initial: A)(implicit F: Effect[F], ec: ExecutionContext): F[Topic[F, A]] = {
    // Id identifying each subscriber uniquely
    class ID

    sealed trait Subscriber {
      def publish(a: A): F[Unit]
      def id: ID
      def subscribe: Stream[F, A]
      def subscribeSize: Stream[F, (A, Int)]
      def unSubscribe: F[Unit]
    }

    async
      .refOf[F, (A, Vector[Subscriber])]((initial, Vector.empty[Subscriber]))
      .flatMap { state =>
        async.signalOf[F, Int](0).map { subSignal =>
          def mkSubscriber(maxQueued: Int): F[Subscriber] =
            for {
              q <- async.boundedQueue[F, A](maxQueued)
              firstA <- async.promise[F, A]
              done <- async.promise[F, Boolean]
              sub = new Subscriber {
                def unSubscribe: F[Unit] =
                  for {
                    _ <- state.modify {
                      case (a, subs) => a -> subs.filterNot(_.id == id)
                    }
                    _ <- subSignal.modify(_ - 1)
                    _ <- done.complete(true)
                  } yield ()
                def subscribe: Stream[F, A] = eval(firstA.get) ++ q.dequeue
                def publish(a: A): F[Unit] =
                  q.offer1(a).flatMap { offered =>
                    if (offered) F.unit
                    else {
                      eval(done.get)
                        .interruptWhen(q.full.discrete.map(!_))
                        .last
                        .flatMap {
                          case None    => eval(publish(a))
                          case Some(_) => Stream.empty
                        }
                        .compile
                        .drain
                    }
                  }

                def subscribeSize: Stream[F, (A, Int)] =
                  eval(firstA.get).map(_ -> 0) ++ q.dequeue.zip(q.size.continuous)
                val id: ID = new ID
              }
              c <- state.modify { case (a, s) => a -> (s :+ sub) }
              _ <- subSignal.modify(_ + 1)
              _ <- firstA.complete(c.now._1)
            } yield sub

          new Topic[F, A] {
            def publish: Sink[F, A] =
              _.flatMap(a => eval(publish1(a)))

            def subscribers: Signal[F, Int] = subSignal

            def publish1(a: A): F[Unit] =
              state.modify { case (_, subs) => a -> subs }.flatMap { c =>
                c.now._2.traverse(_.publish(a)).as(())
              }

            def subscribe(maxQueued: Int): Stream[F, A] =
              bracket(mkSubscriber(maxQueued))(_.subscribe, _.unSubscribe)

            def subscribeSize(maxQueued: Int): Stream[F, (A, Int)] =
              bracket(mkSubscriber(maxQueued))(_.subscribeSize, _.unSubscribe)
          }
        }
      }
  }
}
