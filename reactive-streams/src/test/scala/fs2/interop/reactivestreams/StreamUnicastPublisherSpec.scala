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
package reactivestreams

import cats.effect._
import org.reactivestreams._
import org.reactivestreams.tck.{PublisherVerification, TestEnvironment}

final class FailedSubscription extends Subscription {
  def cancel(): Unit = {}
  def request(n: Long): Unit = {}
}

final class FailedPublisher extends Publisher[Int] {
  def subscribe(subscriber: Subscriber[_ >: Int]): Unit = {
    subscriber.onSubscribe(new FailedSubscription)
    subscriber.onError(new Error("BOOM"))
  }
}

final class StreamUnicastPublisherSpec
    extends PublisherVerification[Int](new TestEnvironment(1000L))
    with UnsafeTestNGSuite {

  def createPublisher(n: Long): StreamUnicastPublisher[IO, Int] = {
    val s =
      if (n == java.lang.Long.MAX_VALUE) Stream.range(1, 20).repeat
      else Stream(1).repeat.scan(1)(_ + _).map(i => if (i > n) None else Some(i)).unNoneTerminate

    StreamUnicastPublisher(s.covary[IO], dispatcher)
  }

  def createFailedPublisher(): FailedPublisher = new FailedPublisher()
}
