package fs2.concurrent

import cats.effect.Concurrent
import fs2._

/** Provides mechanisms for balancing the distribution of chunks across multiple streams. */
object Balance {

  /**
    * Allows balanced processing of this stream via multiple concurrent streams.
    *
    * This could be viewed as Stream "fan-out" operation, allowing concurrent processing of elements.
    * As the elements arrive, they are evenly distributed to streams that have already started their
    * evaluation. To control the fairness of the balance, the `chunkSize` parameter is available,
    * which controls the maximum number of elements pulled by single inner stream.
    *
    * Note that this will pull only enough elements to satisfy needs of all inner streams currently
    * being evaluated. When there are no stream awaiting the elements, this will stop pulling more
    * elements from source.
    *
    * If there is need to achieve high throughput, `balance` may be used together with `prefetch`
    * to initially prefetch large chunks that will be available for immediate distribution to streams.
    * For example:
    * {{{
    *   source.prefetch(100).balance(chunkSize=10).take(10)
    * }}}
    * This constructs a stream of 10 subscribers, which always takes 100 elements from the source,
    * and gives 10 elements to each subscriber. While the subscribers process the elements, this will
    * pull another 100 elements, which will be available for distribution when subscribers are ready.
    *
    * Often this combinator is used together with `parJoin`, such as :
    *
    * {{{
    *   Stream(1,2,3,4).balance.map { worker =>
    *     worker.map(_.toString)
    *   }.take(3).parJoinUnbounded.compile.to[Set].unsafeRunSync
    * }}}
    *
    *
    * When `source` terminates, the resulting streams (workers) are terminated once all elements
    * so far pulled from `source` are processed.
    *
    * The resulting stream terminates after the source stream terminates and all workers terminate.
    * Conversely, if the resulting stream is terminated early, the source stream will be terminated.
    */
  def apply[F[_]: Concurrent, O](chunkSize: Int): Pipe[F, O, Stream[F, O]] = { source =>
    Stream.eval(PubSub(PubSub.Strategy.closeDrainFirst(strategy[O]))).flatMap { pubSub =>
      def subscriber =
        pubSub
          .getStream(chunkSize)
          .unNoneTerminate
          .flatMap(Stream.chunk)

      def push =
        source.chunks
          .evalMap(chunk => pubSub.publish(Some(chunk)))
          .onFinalize(pubSub.publish(None))

      Stream.constant(subscriber).concurrently(push)
    }
  }

  /**
    * Like `apply` but instead of providing a stream of worker streams, the supplied pipes are
    * used to transform each worker.
    *
    * Each supplied pipe is run concurrently with other. This means that amount of pipes
    * determines concurrency.
    *
    * Each pipe may have a different implementation, if required; for example one pipe may process
    * elements while another may send elements for processing to another machine.
    *
    * Results from pipes are collected and emitted as the resulting stream.
    *
    * This will terminate when :
    *
    *  - this terminates
    *  - any pipe fails
    *  - all pipes terminate
    *
    * @param pipes pipes to use to process work
    * @param chunkSize maximum chunk to present to each pipe, allowing fair distribution between pipes
    */
  def through[F[_]: Concurrent, O, O2](chunkSize: Int)(pipes: Pipe[F, O, O2]*): Pipe[F, O, O2] =
    _.balance(chunkSize)
      .take(pipes.size)
      .zipWith(Stream.emits(pipes)) { case (stream, pipe) => stream.through(pipe) }
      .parJoinUnbounded

  private def strategy[O]: PubSub.Strategy[Chunk[O], Chunk[O], Option[Chunk[O]], Int] =
    new PubSub.Strategy[Chunk[O], Chunk[O], Option[Chunk[O]], Int] {
      def initial: Option[Chunk[O]] =
        Some(Chunk.empty) // causes to block first push, hence all the other chunks must be non-empty.

      def accepts(i: Chunk[O], state: Option[Chunk[O]]): Boolean =
        state.isEmpty

      def publish(i: Chunk[O], state: Option[Chunk[O]]): Option[Chunk[O]] =
        Some(i).filter(_.nonEmpty)

      def get(selector: Int, state: Option[Chunk[O]]): (Option[Chunk[O]], Option[Chunk[O]]) =
        state match {
          case None => (None, None)
          case Some(chunk) =>
            if (chunk.isEmpty)
              (None, None) // first case for first subscriber, allows to publish to first publisher
            else {
              val (head, keep) = chunk.splitAt(selector)
              if (keep.isEmpty) (None, Some(head))
              else (Some(keep), Some(head))
            }

        }

      def empty(state: Option[Chunk[O]]): Boolean =
        state.isEmpty

      def subscribe(selector: Int, state: Option[Chunk[O]]): (Option[Chunk[O]], Boolean) =
        (state, false)

      def unsubscribe(selector: Int, state: Option[Chunk[O]]): Option[Chunk[O]] =
        state
    }
}
