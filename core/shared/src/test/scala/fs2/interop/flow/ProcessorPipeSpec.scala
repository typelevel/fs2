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
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.effect.PropF.forAllF

final class ProcessorPipeSpec extends Fs2Suite {
  test("should process upstream input and propagate results to downstream") {
    forAllF(Arbitrary.arbitrary[Seq[Int]], Gen.posNum[Int]) { (ints, bufferSize) =>
      // Since creating a Flow.Processor is very complex,
      // we will reuse our Pipe => Processor logic.
      val processor = ((stream: Stream[IO, Int]) => stream.map(_ * 1)).unsafeToProcessor(
        chunkSize = bufferSize
      )

      val pipe = Pipe.fromProcessor[IO](
        processor,
        chunkSize = bufferSize
      )

      val inputStream = Stream.emits(ints)
      val outputStream = pipe(inputStream)
      val program = outputStream.compile.toVector

      program.assertEquals(ints.toVector)
    }
  }
}
