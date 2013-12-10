package scalaz.stream.async.mutable

import scalaz.{\/-, -\/, \/}
import scalaz.concurrent.{Actor, Task}
import scalaz.stream.Process
import scalaz.stream.Process._
import scalaz.stream.actor.TopicActor
import scalaz.stream.actor.TopicActor._


/**
 * Represents topic, that asynchronously exchanges messages between one or more publisher(s) 
 * and one or more subscriber(s). 
 *
 * Guarantees:
 * - Order of messages from publisher is guaranteed to be preserved to all subscribers
 * - Messages from publishers may interleave in non deterministic order before they are read by subscribers
 * - Once the `subscriber` is run it will receive all messages from all `publishers` starting with very first message
 *   arrived AFTER the subscriber was run
 *
 * Please not that topic is `active` even when there are no publishers or subscribers attached to it. However
 * once the `close` or `fail` is called all the publishers and subscribers will terminate or fail.
 *
 */
trait TopicOps[S, A, B] {

  private[stream] val actor: Actor[TopicActor.Msg[S, A, B]]

  /**
   * Gets subscriber from this topic. There may be multiple subscribers to this topic. Subscriber
   * subscribes and un-subscribes when it is run or terminated.
   * @return
   */
   val subscribe0: Process[Task,S \/ B] = {
    await(Task.async[SubscriberRef[S \/ B]]( reg => actor ! Subscribe(reg)) )(
      sref =>
        repeatEval(Task.async[Seq[S \/ B]] { reg => actor ! GetOne(sref, reg) })
        .flatMap(l => emitAll(l))
        .onComplete(eval(Task.async[Unit]( reg => actor ! UnSubscribe(sref, reg)) ).drain)
      , halt
      , halt)
  }


  /**
   * Will `finish` this topic. Once `finished` all publishers and subscribers are halted via `halt`.
   * When this topic is `finished` or `failed` this is no-op
   * @return
   */
  def close: Task[Unit] = fail(End)

  /**
   * Will `fail` this topic. Once `failed` all publishers and subscribers will terminate with cause `err`.
   * When this topic is `finished` or `failed` this is no-op
   * @param err
   * @return
   */
  def fail(err: Throwable): Task[Unit] = Task.async[Unit](reg => actor ! Fail(err, reg)).attempt.map(_ => ())

  /**
   * Gets publisher to this topic. There may be multiple publishers to this topic.
   */
  val publish: Sink[Task, A] = Process.constant(publishOne _)

  /**
   * publishes single `A` to this topic.
   */
  def publishOne(a: A): Task[Unit] = Task.async[Unit](reg => actor ! Publish(a, reg))

}

trait Topic[A] extends TopicOps[Nothing,A, A] {
  /**
   * Gets subscriber from this topic. There may be multiple subscribers to this topic. Subscriber
   * subscribes and un-subscribes when it is run or terminated.
   * @return
   */
  val subscribe: Process[Task,A] = subscribe0.collect { case \/-(a) => a }
}


/**
 * Like a `Topic`, but allows to specify `Writer1` to eventually `write` the state `S` from arriving messages of `A`
 * to topic
 *
 * @tparam S
 * @tparam A
 */
trait WriterTopic[S, A, B] extends TopicOps[S,A,B] {
  self =>

  /**
   * Gets subscriber from this topic. There may be multiple subscribers to this topic. Subscriber
   * subscribes and un-subscribes when it is run or terminated.
   *
   * WriterTopic guarantees, that the very first message received is always -\/(S) with A \/ S following.
   */
  val subscribe = subscribe0

  def signal: scalaz.stream.async.immutable.Signal[S] =
    new scalaz.stream.async.immutable.Signal[S] {
      def changed: Process[Task, Boolean] =
        discrete.map(_ => true).wye(Process.constant(false))(scalaz.stream.wye.merge)

      def discrete: Process[Task, S] =
        self.subscribe0.collect { case -\/(s) => s }

      def continuous: Process[Task, S] =
        discrete.wye(Process.constant(()))(scalaz.stream.wye.echoLeft)

      def changes: Process[Task, Unit] =
        discrete.map(_ => ())
    }


}
  
