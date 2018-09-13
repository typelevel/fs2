package fs2.concurrent.pubsub

trait PubSubStrategy[I, O, S, Selector] {

  /** provides initial state **/
  def initial: S

  /**
    * Verifies if `I` can be accepted in the queue.
    * If, this yields to true, then queue can accept this element, and interpreter is free to invoke `publish`.
    * If this yields to false, then interpreter holds the publisher, until there is at least one  `get`
    * (either successsful or not) in which case this is consulted again.
    *
    * @param i            `I` to publish
    * @return
    */
  def accepts(i: I, queueState: S): Boolean

  /**
    * Publishes `I`. This must always succeed.
    * Interpreter guarantees to invoke this only when `accepts` yields to true.
    *
    * @param i An `I` to publish
    */
  def publish(i: I, queueState: S): S

  /**
    * Gets `O`, selected by `selector`.
    *
    * Yields to None, if subscriber cannot be satisfied, causing the subscriber to hold, until next successful `publish`
    * Yields to Some((s,o)) if the subscriber may be satisfied.
    *
    * @param selector     Selector, to select any `O` this `get` is interested in. In case of the subscription
    *                     based strategy, the `Selector` shall hold identity of the subscriber.
    */
  def get(selector: Selector, queueState: S): (S, Option[O])

  /**
    * Yields to true, indicating there are no elements to `get`.
    */
  def empty(queueState: S): Boolean

  /**
    * Consulted by interpreter to subscribe given selector. Subscriptions allows to manage context across multiple `Get` requests.
    * Yields to false, in case the subscription cannot be satisfied.
    *
    * @param selector     Selector, that shall be used with mulitple subsequent `get` operations
    */
  def subscribe(selector: Selector, queueState: S): (S, Boolean)

  /**
    * When strategy supports long-term subscriptions, this is used by interpreter to signal, that such long
    * term subscriber is no longer interested in getting more data.
    *
    * @param selector Selector, whose selection shall be cancelled. Shall contain subscriber's identity.
    */
  def unsubscribe(selector: Selector, queueState: S): S
}
