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

import cats.syntax.all.*

import java.util.concurrent.Flow
import cats.effect.Async

private[fs2] final class ProcessorPipe[F[_], I, O](
    processor: Flow.Processor[I, O],
    chunkSize: Int
)(implicit
    F: Async[F]
) extends Pipe[F, I, O] {
  override def apply(stream: Stream[F, I]): Stream[F, O] =
    (
      Stream.resource(StreamPublisher[F, I](stream)),
      Stream.eval(StreamSubscriber[F, O](chunkSize))
    ).flatMapN { (publisher, subscriber) =>
      val initiateUpstreamProduction = F.delay(publisher.subscribe(processor))
      val initiateDownstreamConsumption = F.delay(processor.subscribe(subscriber))

      subscriber.stream(
        subscribe = initiateUpstreamProduction >> initiateDownstreamConsumption
      )
    }
}
