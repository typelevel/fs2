package scalaz.stream.async.mutable

import scalaz.stream.{Process, message}
import scalaz.concurrent.{Task, Actor}
import scalaz.stream.Process._

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
trait Topic[A] {

  import message.topic._

  private[stream] val actor: Actor[Msg[A]]

  /**
   * Gets publisher to this topic. There may be multiple publishers to this topic.
   */
  val publish: Sink[Task, A] = repeatEval(Task.now ( publishOne _ ))

  /**
   * Gets subscriber from this topic. There may be multiple subscribers to this topic. Subscriber
   * subscribes and un-subscribes when it is run or terminated.  
   * @return
   */
  val subscribe: Process[Task, A] = {
    await(Task.async[SubscriberRef[A]](reg => actor ! Subscribe(reg)))(
      sref =>
        repeatEval(Task.async[Seq[A]] { reg => actor ! Get(sref, reg) })
          .flatMap(l => emitAll(l))
          .onComplete(eval(Task.async[Unit](reg => actor ! UnSubscribe(sref, reg))).drain)
      , halt
      , halt)
  }

  /**
   * publishes single `A` to this topic. 
   */
  def publishOne(a:A) : Task[Unit] = Task.async[Unit](reg => actor ! Publish(a, reg))

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


}
  
