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

import cats.effect.IO
import cats.syntax.all._
import org.reactivestreams.{Subscriber, Subscription}

import java.util.concurrent.atomic.AtomicBoolean

/** This behaviour is already tested by the Reactive Stream test
  * suite, but it's proven difficult to enforce, so we add our own
  * tests that run the assertions multiple times to make possible
  * failures due to race conditions more repeatable
  */
class CancellationSpec extends Fs2Suite {
  final class Sub[A](b: AtomicBoolean) extends Subscriber[A] {
    def onNext(t: A) = b.set(true)
    def onComplete() = b.set(true)
    def onError(e: Throwable) = b.set(true)
    def onSubscribe(s: Subscription) = ()
  }

  val s = Stream.range(0, 5).covary[IO]

  val attempts = 1000

  def testStreamSubscription(clue: String)(program: Subscription => Unit): IO[Unit] =
    IO(new AtomicBoolean(false))
      .flatMap { flag =>
        StreamSubscription(s, new Sub(flag)).use { subscription =>
          (
            subscription.run,
            IO(program(subscription))
          ).parTupled
        } >>
          IO(flag.get()).assertEquals(false, clue)
      }
      .replicateA_(attempts)

  test("After subscription is cancelled request must be noOps") {
    testStreamSubscription(clue = "onNext was called after the subscription was cancelled") { sub =>
      sub.cancel()
      sub.request(1)
      sub.request(1)
      sub.request(1)
    }
  }

  test("after subscription is cancelled additional cancelations must be noOps") {
    testStreamSubscription(clue = "onComplete was called after the subscription was cancelled") {
      sub =>
        sub.cancel()
        sub.cancel()
    }
  }
}
