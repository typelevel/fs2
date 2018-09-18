package fs2.experimental.concurrent.pubsub

trait PubSubStrategy[I, O, S, Selector]
    extends fs2.concurrent.pubsub.PubSubStrategy[I, O, S, Selector]
