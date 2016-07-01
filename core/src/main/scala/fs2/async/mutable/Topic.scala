package fs2.async.mutable

import fs2._
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
trait Topic[F[_], A] { self =>

  /**
    * Published any elements from source of `A` to this topic.
    * If any of the subscribers reach its `maxQueued` limit, then this will hold to publish next element
    * before that subscriber consumes it's elements or terminates.
    */
  def publish: Sink[F, A]

  /**
    * Publish one `A` to topic.
    *
    * This will wait until `A` is published to all subscribers.
    * If one of the subscribers is over the `maxQueued` limit, this will wait to complete until that subscriber processes
    * some of its elements to get room for this new. published `A`
    *
    */
  def publish1(a: A): F[Unit]

  /**
    * Subscribes to receive any published `A` to this topic.
    *
    * Always returns last `A` published first, and then any next `A` published.
    *
    * If the subscriber is over `maxQueued` bound of messages awaiting to be processed,
    * then publishers will hold into publishing to the queue.
    *
    */
  def subscribe(maxQueued: Int): Stream[F, A]

  /**
    * Subscribes to receive published `A` to this topic.
    *
    * Always returns last `A` published first, and then any next `A` available
    *
    * Additionally this emits current size of the queue of `A` for this subscriber allowing
    * you to terminate (or adjust) the subscription if subscriber is way behind the elements available.
    *
    * Note that queue size is approximate and may not be exactly the size when `A` was taken.
    *
    * If the subscriber is over `maxQueued` bound of messages awaiting to be processed,
    * then publishers will hold into publishing to the queue.
    *
    */
  def subscribeSize(maxQueued: Int): Stream[F, (A, Int)]

  /**
    * Signal of current active subscribers
    */
  def subscribers: fs2.async.immutable.Signal[F, Int]

  /**
   * Returns an alternate view of this `Topic` where its elements are of type [[B]],
   * given back and forth function from `A` to `B`.
   */
  def imap[B](f: A => B)(g: B => A): Topic[F, B] =
    new Topic[F, B] {
      def publish: Sink[F, B] = sfb => self.publish(sfb.map(g))
      def publish1(b: B): F[Unit] = self.publish1(g(b))
      def subscribe(maxQueued: Int): Stream[F, B] = self.subscribe(maxQueued).map(f)
      def subscribers: fs2.async.immutable.Signal[F, Int] = self.subscribers
      def subscribeSize(maxQueued: Int): Stream[F, (B, Int)] =
        self.subscribeSize(maxQueued).map { case (a, i) => f(a) -> i }
    }
}

object Topic {

  def apply[F[_], A](initial:A)(implicit F: Async[F]):F[Topic[F,A]] = {
    // Id identifying each subscriber uniquely
    class ID

    sealed trait Subscriber {
      def publish(a:A):F[Unit]
      def id:ID
      def subscribe:Stream[F,A]
      def subscribeSize:Stream[F,(A,Int)]
      def unSubscribe:F[Unit]
    }

    F.bind(F.refOf((initial,Vector.empty[Subscriber]))) { state =>
    F.map(async.signalOf[F,Int](0)) { subSignal =>

      def mkSubscriber(maxQueued: Int):F[Subscriber] = {
        F.bind(async.boundedQueue[F,A](maxQueued)) { q =>
        F.bind(F.ref[A]) { firstA =>
        F.bind(F.ref[Boolean]) { done =>
          val sub = new Subscriber {
            def unSubscribe: F[Unit] = {
              F.bind(state.modify { case (a,subs) => a -> subs.filterNot(_.id == id) }) { _ =>
                F.bind(subSignal.modify(_ - 1))(_ => done.setPure(true))
              }
            }
            def subscribe: Stream[F, A] = eval(firstA.get) ++ q.dequeue
            def publish(a: A): F[Unit] = {
              F.bind(q.offer1(a)) { offered =>
                if (offered) F.pure(())
                else {
                  eval(done.get).interruptWhen(q.full.discrete.map(! _ )).last.flatMap {
                    case None => eval(publish(a))
                    case Some(_) => Stream.empty
                  }.run
                }
              }

            }
            def subscribeSize: Stream[F, (A,Int)] = eval(firstA.get).map(_ -> 0) ++ q.dequeue.zip(q.size.continuous)
            val id: ID = new ID
          }

          F.bind(state.modify { case(a,s) => a -> (s :+ sub) }) { c =>
            F.bind(subSignal.modify(_ + 1))(_ => F.map(firstA.setPure(c.now._1))(_ => sub))
          }
        }}}
      }

      new Topic[F,A] {
        def publish:Sink[F,A] =
          _ flatMap( a => eval(publish1(a)))

        def subscribers: Signal[F, Int] = subSignal

        def publish1(a: A): F[Unit] =
          F.bind(state.modify{ case (_,subs) => a -> subs }) { c => F.map(F.traverse(c.now._2)(_.publish(a)))(_ => ()) }

        def subscribe(maxQueued: Int): Stream[F, A] =
          bracket(mkSubscriber(maxQueued))(_.subscribe, _.unSubscribe)

        def subscribeSize(maxQueued: Int): Stream[F, (A, Int)] =
          bracket(mkSubscriber(maxQueued))(_.subscribeSize, _.unSubscribe)
      }
    }}
  }
}
