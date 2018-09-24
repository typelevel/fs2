package fs2.concurrent

import cats.effect.Concurrent
import fs2._

object Balance {

  /**
    * Allows to balance processing of this stream to parallel streams.
    *
    * This could be viewed as Stream `fan-out` operation allowing to process incoming `O` in parallel.
    *
    * As the elements arrive, they are balanced to streams that already started their evaluation.
    * To control the fairness of the balance, the `chunkSize` parameter is available, that controls
    * a maximum number of element pulled by single `stream`.
    *
    * Note that this will pull only that much `O` to satisfy needs of all workers currently being evaluated.
    * When there are no stream awaiting the elements, this will stop pulling more elements from source.
    *
    * If there is need to achieve high throughput, `balance` may be used together with `prefetch` to initially prefetch
    * large chunks that will be available for immediate distribution to streams. For example
    * {{{
    *   source.prefetch(100).balance(chunkSize=10).take(10)
    * }}}
    * Constructs stream of 10 subscribers, that always takes 100 elements, and gives 10 elements to each subscriber. While
    * subscriber processes the elements, this will pull another 100 elements, that will be again available, shall
    * balance be done with supplying 10 elements to each of its subscribers.
    *
    * Usually this combinator is used together with parJoin, such as :
    *
    * {{{
    *   Stream(1,2,3,4).balance.map { worker =>
    *     worker.map(_.toString)
    *   }.take(3).parJoinUnbounded.compile.toVector.unsafeRunSync.toSet
    * }}}
    *
    *
    * When `source` terminates, the resulting streams (workers) are terminated once all elements so far pulled
    * from `source` are processed.
    *
    * When `source` terminates, the resulting stream won't terminate.
    *
    * When the resulting stream is evaluated, then `source` will terminate if resulting stream will terminate.
    *
    * @return
    */
  def apply[F[_]: Concurrent, O](chunkSize: Int): Pipe[F, O, Stream[F, O]] = { source =>
    Stream.eval(PubSub(PubSub.Strategy.closeDrainFirst(strategy[O]))).flatMap { pubSub =>
      def subscriber =
        Stream
          .repeatEval(pubSub.get(chunkSize))
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
    * Like `distribute` but instead of providing stream as source, it runs each stream through
    * supplied pipe.
    *
    * Each supplied pipe is run concurrently with other. This means that amount of pipes determines parallelism.
    * Each pipe may have different implementation, if required, for example one pipe may process elements, the other
    * may send elements for processing to another machine.
    *
    * Results from pipes are collected and emitted as resulting stream.
    *
    * This will terminate when :
    *
    *  - this terminates
    *  - any pipe fails
    *  - all pipes terminate
    *
    * @param pipes        Pipes to use to process work, that will be concurrently evaluated
    * @param chunkSize    A maximum chunk to present to every pipe. This allows fair distribution of the work
    *                     between pipes.
    * @return
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

      def accepts(i: Chunk[O], queueState: Option[Chunk[O]]): Boolean =
        queueState.isEmpty

      def publish(i: Chunk[O], queueState: Option[Chunk[O]]): Option[Chunk[O]] =
        Some(i).filter(_.nonEmpty)

      def get(selector: Int, queueState: Option[Chunk[O]]): (Option[Chunk[O]], Option[Chunk[O]]) =
        queueState match {
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

      def empty(queueState: Option[Chunk[O]]): Boolean =
        queueState.isEmpty

      def subscribe(selector: Int, queueState: Option[Chunk[O]]): (Option[Chunk[O]], Boolean) =
        (queueState, false)

      def unsubscribe(selector: Int, queueState: Option[Chunk[O]]): Option[Chunk[O]] =
        queueState
    }

}
