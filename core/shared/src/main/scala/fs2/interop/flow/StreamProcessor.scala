/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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

  override def subscribe(subscriber: Flow.Subscriber[? >: O]): Unit =
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
