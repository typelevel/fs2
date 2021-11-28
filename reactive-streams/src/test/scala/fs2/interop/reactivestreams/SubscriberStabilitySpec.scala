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
import cats.effect.std.Random
import org.reactivestreams._

import scala.concurrent.duration._

import java.nio.ByteBuffer

class SubscriberStabilitySpec extends Fs2Suite {
  test("StreamSubscriber has no race condition") {
    val publisher = new Publisher[ByteBuffer] {

      class SubscriptionImpl(val s: Subscriber[_ >: ByteBuffer]) extends Subscription {
        override def request(n: Long): Unit = {
          s.onNext(ByteBuffer.wrap(new Array[Byte](1)))
          s.onComplete()
        }

        override def cancel(): Unit = {}
      }

      override def subscribe(s: Subscriber[_ >: ByteBuffer]): Unit =
        s.onSubscribe(new SubscriptionImpl(s))
    }

    def randomDelay(rnd: Random[IO]): IO[Unit] =
      for {
        ms <- rnd.nextIntBounded(50)
        _ <- IO.sleep(ms.millis)
      } yield ()

    val randomStream = Stream.eval(Random.scalaUtilRandom[IO]).flatMap { rnd =>
      Stream.repeatEval(randomDelay(rnd))
    }

    val stream = fromPublisher[IO, ByteBuffer](publisher, bufferSize = 16)
      .map(Left(_))
      .interleave(randomStream.map(Right(_)))
      .collect { case Left(buf) => buf }

    val N = 100
    @volatile var failed: Boolean = false
    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
      def uncaughtException(thread: Thread, error: Throwable): Unit =
        failed = true
    })

    def loop(remaining: Int): IO[Unit] =
      IO.delay(failed).flatMap { alreadyFailed =>
        if (!alreadyFailed) {
          val f = stream.compile.drain
          if (remaining > 0)
            f *> loop(remaining - 1)
          else
            f
        } else
          IO.unit
      }

    loop(N).unsafeRunSync()

    if (failed)
      fail("Uncaught exception was reported")
  }
}
