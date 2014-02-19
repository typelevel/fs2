package scalaz.stream.async.mutable

import scalaz.concurrent.Task
import scalaz.stream.Process
import scalaz.stream.Process._


/**
 * Like a `Topic`, but allows to specify `Writer1` to eventually `write` the state `W` or produce `O`
 * from arriving messages of `I` to this topic
 *
 */
trait WriterTopic[W, I, O] {

  /**
   * Consumes the supplied source in this topic.
   * Please note that, supplied process is run immediately once the resulting Task is run.
   * Consumption will stop when this topic is terminate.
   * @param p
   * @return
   */
  def consumeOne(p: Process[Task,I]) : Task[Unit]

  /**
   * Sink that when supplied with stream of processes will consume these process to this topic.
   * Supplied processes are run non-deterministically and in parallel and will terminate when this topic terminates
   * whenever
   * @return
   */
  def consume:Sink[Task,Process[Task,I]]

  /**
   * Gets publisher to this writer topic. There may be multiple publishers to this writer topic.
   */
  def publish: Sink[Task, I]

  /**
   * Gets subscriber from this writer topic. There may be multiple subscribers to this writer topic. Subscriber
   * subscribes and un-subscribes when it is run or terminated.
   *
   * If writer topic has `W` written, it will be first value written by this topic, following any `B` or `W` produced after.
   * @return
   */
  def subscribe: Writer[Task, W, O]

  /**
   * Subscribes to `O` values from this writer topic only.
   */
  def subscribeO: Process[Task, O]

  /** Subscribes to `W` values only from this Writer topic **/
  def subscribeW: Process[Task, W]

  /**
   * Provides signal of `W` values as they were emitted by Writer1 of this Writer topic
   **/
  def signal: scalaz.stream.async.immutable.Signal[W]


  /**
   * Publishes single `I` to this writer topic.
   */
  def publishOne(i: I): Task[Unit]

  /**
   * Will `finish` this writer topic. Once `finished` all publishers and subscribers are halted via `halt`.
   * When this writer topic is `finished` or `failed` this is no-op
   *
   * The resulting task is completed _after_ all publishers and subscribers finished
   *
   * @return
   */
  def close: Task[Unit] = fail(End)

  /**
   * Will `fail` this writer topic. Once `failed` all publishers and subscribers will terminate with cause `err`.
   * When this writer topic is `finished` or `failed` this is no-op
   *
   * The resulting task is completed _after_ all publishers and subscribers finished
   *
   */
  def fail(err: Throwable): Task[Unit]


}
