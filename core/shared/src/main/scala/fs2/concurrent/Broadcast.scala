package fs2.concurrent

import cats.effect.{Concurrent, Sync}
import fs2.internal.Token
import fs2._

/** Provides mechanisms for broadcast distribution of elements to multiple streams. */
object Broadcast {

  /**
    * Allows elements of a stream to be broadcast to multiple workers.
    *
    * As the elements arrive, they are broadcast to all `workers` that have started evaluation before the
    * element was pulled.
    *
    * Elements are pulled as chunks from the source and the next chunk is pulled when all workers are done
    * with processing the current chunk. This behaviour may slow down processing of incoming chunks by
    * faster workers. If this is not desired, consider using the `prefetch` and `prefetchN` combinators on workers to compensate
    * for slower workers.
    *
    * Often this combinator is used together with parJoin, such as :
    *
    * {{{
    *   Stream(1,2,3,4).broadcast.map { worker =>
    *     worker.evalMap { o => IO.println("1:" + o.toString) }
    *   }.take(3).parJoinUnbounded.compile.drain.unsafeRunSync
    * }}}
    *
    * Note that in the example, above the workers are not guaranteed to see all elements emitted. This is
    * due to different subscription times of each worker and speed of emitting the elements by the source.
    * If this is not desired, consider using `broacastThrough` and `broadcastTo`, which are built on top of `Broadcast.through`, as an alternative.
    * They will hold on pulling from  source if there are no workers ready.
    *
    * When `source` terminates, then the inner streams (workers) are terminated once all elements pulled
    * from `source` are processed by all workers. However, note that when that `source` terminates,
    * resulting stream will not terminate until the inner streams terminate.
    *
    * @param minReady specifies that broadcasting will hold off until at least `minReady` subscribers will
    *                 be ready
    */
  def apply[F[_]: Concurrent, O](minReady: Int): Pipe[F, O, Stream[F, O]] = { source =>
    Stream.eval(PubSub(PubSub.Strategy.closeDrainFirst(strategy[Chunk[O]](minReady)))).flatMap {
      pubSub =>
        def subscriber =
          Stream.bracket(Sync[F].delay(new Token))(pubSub.unsubscribe).flatMap { selector =>
            pubSub
              .getStream(selector)
              .unNoneTerminate
              .flatMap(Stream.chunk)
          }

        def publish =
          source.chunks
            .evalMap(chunk => pubSub.publish(Some(chunk)))
            .onFinalize(pubSub.publish(None))

        Stream.constant(subscriber).concurrently(publish)
    }
  }

  /**
    * Like [[apply]] but instead of providing a stream of worker streams, it runs each inner stream through
    * the supplied pipes.
    *
    * Supplied pipes are run concurrently with each other. Hence, the number of pipes determines concurrency.
    * Also, this guarantees that each pipe will view all `O` pulled from source stream, unlike `broadcast`.
    *
    * Resulting values are collected and returned in a single stream of `O2` values.
    *
    * @param pipes pipes that will concurrently process the work
    */
  def through[F[_]: Concurrent, O, O2](pipes: Pipe[F, O, O2]*): Pipe[F, O, O2] =
    _.through(apply(pipes.size))
      .take(pipes.size)
      .zipWith(Stream.emits(pipes)) { case (src, pipe) => src.through(pipe) }
      .parJoinUnbounded

  /**
    * State of the strategy
    *  - AwaitSub:   Awaiting minimum number of subscribers
    *  - Empty:      Awaiting single publish
    *  - Processing: Subscribers are processing the elememts, awaiting them to confirm done.
    */
  private sealed trait State[O] {
    def awaitSub: Boolean
    def isEmpty: Boolean
    def subscribers: Set[Token]
  }

  private object State {
    case class AwaitSub[O](subscribers: Set[Token]) extends State[O] {
      def awaitSub = true
      def isEmpty = false
    }

    case class Empty[O](subscribers: Set[Token]) extends State[O] {
      def awaitSub = false
      def isEmpty = true
    }

    case class Processing[O](
        subscribers: Set[Token],
        processing: Set[Token], // added when we enter to Processing state, and removed whenever sub takes current `O`
        remains: Set[Token], // removed when subscriber requests another `O` but already seen `current`
        current: O
    ) extends State[O] {
      def awaitSub = false
      def isEmpty = false
    }

  }

  private def strategy[O](minReady: Int): PubSub.Strategy[O, O, State[O], Token] =
    new PubSub.Strategy[O, O, State[O], Token] {
      def initial: State[O] =
        State.AwaitSub(Set.empty)

      def accepts(i: O, queueState: State[O]): Boolean =
        queueState.isEmpty && !queueState.awaitSub

      def publish(i: O, queueState: State[O]): State[O] =
        State.Processing(
          subscribers = queueState.subscribers,
          processing = queueState.subscribers,
          remains = queueState.subscribers,
          current = i
        )

      def get(selector: Token, queueState: State[O]): (State[O], Option[O]) = queueState match {
        case State.AwaitSub(subscribers) =>
          val nextSubs = subscribers + selector
          if (nextSubs.size >= minReady) (State.Empty(nextSubs), None)
          else (State.AwaitSub(nextSubs), None)
        case State.Empty(subscribers) => (State.Empty(subscribers + selector), None)
        case State.Processing(subscribers, processing, remains, o) =>
          if (subscribers.contains(selector)) {
            if (processing.contains(selector))
              (State.Processing(subscribers, processing - selector, remains, o), Some(o))
            else {
              val remains1 = remains - selector
              if (remains1.nonEmpty) (State.Processing(subscribers, processing, remains1, o), None)
              else (State.Empty(subscribers), None)
            }
          } else
            (State.Processing(subscribers + selector, processing, remains + selector, o), Some(o))

      }

      def empty(queueState: State[O]): Boolean = queueState.isEmpty

      def subscribe(selector: Token, queueState: State[O]): (State[O], Boolean) =
        (queueState, false)

      def unsubscribe(selector: Token, queueState: State[O]): State[O] = queueState match {
        case State.AwaitSub(subscribers) => State.AwaitSub(subscribers - selector)
        case State.Empty(subscribers)    => State.Empty(subscribers - selector)
        case State.Processing(subscribers, processing, remains, o) =>
          val remains1 = remains - selector
          if (remains1.nonEmpty)
            State.Processing(subscribers - selector, processing - selector, remains1, o)
          else State.Empty(subscribers - selector)
      }

    }
}
