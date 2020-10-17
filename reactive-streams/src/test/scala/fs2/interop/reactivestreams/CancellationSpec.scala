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

import org.reactivestreams._
import cats.effect._
import cats.effect.std.Dispatcher

import java.util.concurrent.atomic.AtomicBoolean

/** This behaviour is already tested by the Reactive Stream test
  * suite, but it's proven difficult to enforce, so we add our own
  * tests that run the assertions multiple times to make possible
  * failures due to race conditions more repeatable
  */
class CancellationSpec extends Fs2Suite {

  case class Sub[A](b: AtomicBoolean) extends Subscriber[A] {
    def onNext(t: A) = b.set(true)
    def onComplete() = b.set(true)
    def onError(e: Throwable) = b.set(true)
    def onSubscribe(s: Subscription) = b.set(true)
  }

  val s = Stream.range(0, 5).covary[IO]

  val attempts = 10000

  def withRunner(f: Dispatcher.Runner[IO] => Unit): Unit =
    Dispatcher[IO, Dispatcher.Runner[IO]](Resource.pure)
      .use(runner => IO(f(runner)))
      .unsafeRunSync()

  test("after subscription is cancelled request must be noOps") {
    withRunner { runner =>
      var i = 0
      val b = new AtomicBoolean(false)
      while (i < attempts) {
        val sub = StreamSubscription(Sub[Int](b), s, runner).unsafeRunSync()
        sub.unsafeStart()
        sub.cancel()
        sub.request(1)
        sub.request(1)
        sub.request(1)
        i = i + 1
      }
      if (b.get) fail("onNext was called after the subscription was cancelled")
    }
  }

  test("after subscription is cancelled additional cancelations must be noOps") {
    withRunner { runner =>
      var i = 0
      val b = new AtomicBoolean(false)
      while (i < attempts) {
        val sub = StreamSubscription(Sub[Int](b), s, runner).unsafeRunSync()
        sub.unsafeStart()
        sub.cancel()
        sub.cancel()
        i = i + 1
      }
      if (b.get) fail("onCancel was called after the subscription was cancelled")
    }
  }
}
