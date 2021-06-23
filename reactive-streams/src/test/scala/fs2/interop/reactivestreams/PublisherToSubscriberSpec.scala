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
import org.scalacheck.Prop.forAll

final class PublisherToSubscriberSpec extends Fs2Suite {

  test("should have the same output as input") {
    forAll { (ints: Seq[Int]) =>
      val subscriberStream = Stream.emits(ints).covary[IO].toUnicastPublisher.toStream[IO]

      assert(subscriberStream.compile.toVector.unsafeRunSync() == (ints.toVector))
    }
  }

  object TestError extends Exception("BOOM")

  test("should propagate errors downstream".ignore) {
    // TODO unsafeRunSync hangs
    val input: Stream[IO, Int] = Stream(1, 2, 3) ++ Stream.raiseError[IO](TestError)
    val output: Stream[IO, Int] = input.toUnicastPublisher.toStream[IO]

    assert(output.compile.drain.attempt.unsafeRunSync() == (Left(TestError)))
  }

  test("should cancel upstream if downstream completes") {
    forAll { (as: Seq[Int], bs: Seq[Int]) =>
      val subscriberStream =
        Stream.emits(as ++ bs).covary[IO].toUnicastPublisher.toStream[IO].take(as.size.toLong)

      assert(subscriberStream.compile.toVector.unsafeRunSync() == (as.toVector))
    }
  }
}
