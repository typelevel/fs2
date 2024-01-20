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
import org.reactivestreams.tck.SubscriberWhiteboxVerification.{
  SubscriberPuppet,
  WhiteboxSubscriberProbe
}
import org.reactivestreams.tck.flow.{
  FlowSubscriberBlackboxVerification,
  FlowSubscriberWhiteboxVerification
}
import org.scalatestplus.testng._

import java.util.concurrent.Flow.{Subscriber, Subscription}
import java.util.concurrent.atomic.AtomicInteger

final class SubscriberWhiteboxSpec
    extends FlowSubscriberWhiteboxVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {

  private val counter = new AtomicInteger()

  override def createFlowSubscriber(
      p: WhiteboxSubscriberProbe[Int]
  ): Subscriber[Int] =
    StreamSubscriber[IO, Int](chunkSize = 1)
      .map(s => new WhiteboxSubscriber(s, p))
      .unsafeRunSync()

  def createElement(i: Int): Int = counter.getAndIncrement
}

final class WhiteboxSubscriber[A](
    sub: StreamSubscriber[IO, A],
    probe: WhiteboxSubscriberProbe[A]
) extends Subscriber[A] {
  def onError(t: Throwable): Unit = {
    sub.onError(t)
    probe.registerOnError(t)
  }

  def onSubscribe(s: Subscription): Unit = {
    sub.onSubscribe(s)
    probe.registerOnSubscribe(new SubscriberPuppet {
      override def triggerRequest(elements: Long): Unit =
        s.request(elements)

      override def signalCancel(): Unit =
        s.cancel()
    })
  }

  def onComplete(): Unit = {
    sub.onComplete()
    probe.registerOnComplete()
  }

  def onNext(a: A): Unit = {
    sub.onNext(a)
    probe.registerOnNext(a)
  }
}

final class SubscriberBlackboxSpec
    extends FlowSubscriberBlackboxVerification[Int](new TestEnvironment(1000L))
    with TestNGSuiteLike {

  override def createFlowSubscriber(): StreamSubscriber[IO, Int] =
    StreamSubscriber[IO, Int](chunkSize = 1).unsafeRunSync()

  override def triggerFlowRequest(subscriber: Subscriber[? >: Int]): Unit =
    subscriber
      .asInstanceOf[StreamSubscriber[IO, Int]]
      .stream(IO.unit)
      .compile
      .drain
      .unsafeRunAndForget()

  override def createElement(i: Int): Int = i
}
