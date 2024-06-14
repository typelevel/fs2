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

import cats.effect.{IO, Resource}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.effect.PropF.forAllF
import java.util.concurrent.Flow.Publisher

final class StreamProcessorSpec extends Fs2Suite {
  test("should process upstream input and propagate results to downstream") {
    forAllF(Arbitrary.arbitrary[Seq[Int]], Gen.posNum[Int]) { (ints, bufferSize) =>
      val processor = pipeToProcessor[IO, Int, Int](
        pipe = stream => stream.map(_ * 1),
        chunkSize = bufferSize
      )

      val publisher = toPublisher(
        Stream.emits(ints).covary[IO]
      )

      def subscriber(publisher: Publisher[Int]): IO[Vector[Int]] =
        Stream
          .fromPublisher[IO](
            publisher,
            chunkSize = bufferSize
          )
          .compile
          .toVector

      val program = Resource.both(processor, publisher).use { case (pr, p) =>
        IO(p.subscribe(pr)) >> subscriber(pr)
      }

      program.assertEquals(ints.toVector)
    }
  }
}
