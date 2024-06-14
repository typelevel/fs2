package fs2
package interop
package flow

import java.util.concurrent.Flow
import cats.effect.{Async, Resource}

private[flow] final class StreamProcessor[F[_], I, O](
    streamSubscriber: StreamSubscriber[F, I],
    streamPublisher: StreamPublisher[F, O]
) extends Flow.Processor[I, O] {
  override def onSubscribe(subscription: Flow.Subscription): Unit =
    streamSubscriber.onSubscribe(subscription)

  override def onNext(i: I): Unit =
    streamSubscriber.onNext(i)

  override def onError(ex: Throwable): Unit =
    streamSubscriber.onError(ex)

  override def onComplete(): Unit =
    streamSubscriber.onComplete()

  override def subscribe(subscriber: Flow.Subscriber[? >: O <: Object]): Unit =
    streamPublisher.subscribe(subscriber)
}

private[flow] object StreamProcessor {
  def fromPipe[F[_], I, O](
      pipe: Pipe[F, I, O],
      chunkSize: Int
  )(implicit
      F: Async[F]
  ): Resource[F, StreamProcessor[F, I, O]] =
    for {
      streamSubscriber <- Resource.eval(StreamSubscriber[F, I](chunkSize))
      inputStream = streamSubscriber.stream(subscribe = F.unit)
      outputStream = pipe(inputStream)
      streamPublisher <- StreamPublisher(outputStream)
    } yield new StreamProcessor(
      streamSubscriber,
      streamPublisher
    )
}
