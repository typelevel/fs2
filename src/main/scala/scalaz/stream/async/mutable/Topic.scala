package scalaz.stream.async.mutable

import scalaz.stream.Process
import scalaz.concurrent.{Task, Actor}
import scalaz.stream.Process._
import scalaz.stream.actor.message

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
 * Please note that topic is `active` even when there are no publishers or subscribers attached to it. However
 * once the `close` or `fail` is called all the publishers and subscribers will terminate or fail.
 *
 * Once topic if closed or failed, all new publish or subscribe attempts will fail with reason that was used to
 * close or fail the topic.
 *
 */
trait Topic[A] {



  /**
   * Gets publisher to this topic. There may be multiple publishers to this topic.
   */
  def publish: Sink[Task, A]

  /**
   * Gets subscriber from this topic. There may be multiple subscribers to this topic. Subscriber
   * subscribes and un-subscribes when it is run or terminated.  
   * @return
   */
  def subscribe: Process[Task, A]

  /**
   * publishes single `A` to this topic. 
   */
  def publishOne(a:A) : Task[Unit]

  /**
   * Will `finish` this topic. Once `finished` all publishers and subscribers are halted via `halt`.
   * When this topic is `finished` or `failed` this is no-op
   *
   * The resulting task is completed _after_ all publishers and subscribers finished
   *
   * @return
   */
  def close: Task[Unit] = fail(End)

  /**
   * Will `fail` this topic. Once `failed` all publishers and subscribers will terminate with cause `err`.
   * When this topic is `finished` or `failed` this is no-op
   *
   * The resulting task is completed _after_ all publishers and subscribers finished
   *
   */
  def fail(err: Throwable): Task[Unit]


}
  
