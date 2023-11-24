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
import cats.syntax.all._

import java.util.concurrent.Flow.{Subscriber, Subscription}
import java.util.concurrent.atomic.AtomicBoolean

/** This behaviour is already tested by the Reactive Stream test
  * suite, but it's proven difficult to enforce, so we add our own
  * tests that run the assertions multiple times to make possible
  * failures due to race conditions more repeatable
  */
class CancellationSpec extends Fs2Suite {
  final class DummySubscriber(b: AtomicBoolean, program: Subscription => Unit)
      extends Subscriber[Int] {
    def onNext(i: Int): Unit = b.set(true)
    def onComplete(): Unit = b.set(true)
    def onError(e: Throwable): Unit = b.set(true)
    def onSubscribe(s: Subscription): Unit =
      program(s)
  }

  val s = Stream.range(0, 5).covary[IO]

  def attempts = if (isJVM) 10000 else 100

  def testStreamSubscription(clue: String)(program: Subscription => Unit): IO[Unit] =
    IO(new AtomicBoolean(false))
      .flatMap { flag =>
        val subscriber = new DummySubscriber(flag, program)
        StreamSubscription(s, subscriber).flatMap { subscription =>
          (
            subscription.run.compile.drain,
            IO(subscriber.onSubscribe(subscription))
          ).parTupled
        } >>
          IO(flag.get()).assertEquals(false, clue)
      }
      .replicateA_(attempts)

  test("After subscription is canceled request must be NOOPs") {
    testStreamSubscription(clue = "onNext was called after the subscription was canceled") { sub =>
      sub.cancel()
      sub.request(1)
      sub.request(1)
      sub.request(1)
    }
  }

  test("after subscription is canceled additional cancellations must be NOOPs") {
    testStreamSubscription(clue = "onComplete was called after the subscription was canceled") {
      sub =>
        sub.cancel()
        sub.cancel()
    }
  }
}
