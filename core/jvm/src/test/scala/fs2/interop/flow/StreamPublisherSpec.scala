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

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.flow.FlowPublisherVerification
import org.scalatestplus.testng._

final class StreamPublisherSpec
    extends FlowPublisherVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {

  override def createFlowPublisher(n: Long): StreamPublisher[IO, Int] = {
    val nums = Stream.constant(3)

    StreamPublisher[IO, Int](
      stream = if (n == Long.MaxValue) nums else nums.take(n)
    ).allocated.unsafeRunSync()._1
  }

  override def createFailedFlowPublisher(): StreamPublisher[IO, Int] = {
    val publisher = // If the resource is closed then the publisher is failed.
      StreamPublisher[IO, Int](Stream.empty).use(IO.pure).unsafeRunSync()
    publisher
  }
}
