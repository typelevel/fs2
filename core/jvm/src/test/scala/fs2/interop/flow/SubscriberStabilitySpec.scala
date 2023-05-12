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
import cats.effect.std.Random

import java.nio.ByteBuffer
import java.util.concurrent.Flow.{Publisher, Subscriber, Subscription}
import scala.concurrent.duration._

class SubscriberStabilitySpec extends Fs2Suite {
  val attempts = 100

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

    def randomDelays(seed: Long) =
      Stream.eval(Random.scalaUtilRandomSeedLong[IO](seed)).flatMap { rnd =>
        Stream.repeatEval(
          rnd.nextIntBounded(50).flatMap(ms => IO.sleep(ms.millis))
        )
      }

    def stream(seed: Long) =
      fromPublisher[IO](publisher, chunkSize = 16).zipLeft(randomDelays(seed))

    Random.scalaUtilRandom[IO].flatMap { seedRnd =>
      seedRnd.nextLong
        .flatMap { seed =>
          stream(seed).compile.drain.as(true).assert
        }
        .replicateA_(attempts)
    }
  }
}
