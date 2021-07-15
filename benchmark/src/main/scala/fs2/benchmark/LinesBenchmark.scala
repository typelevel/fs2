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
package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}
import cats.instances.string._

@State(Scope.Thread)
class LinesBenchmark {
  @Param(Array("0", "1", "10", "100"))
  var asciiLineSize: Int = _
  @Param(Array("4", "16", "64"))
  var chunkSize: Int = _
  val lines = 250

  var stringStream: Stream[IO, String] = _
  @Setup
  def setup(): Unit =
    stringStream =
      if (asciiLineSize == 0) Stream.empty
      else {
        val rng = new java.util.Random(7919)
        val line = Array.fill(asciiLineSize)((rng.nextInt(126 - 32) + 32).toByte)
        val List(string) = Stream.emits(line).through(text.utf8.decode).foldMonoid.toList

        ((string + "\n") * lines)
          .grouped(chunkSize)
          .grouped(chunkSize)
          .foldLeft(Stream[IO, String]()) { case (acc, strings) =>
            acc ++ Stream.chunk(Chunk.seq(strings))
          }
      }

  @Benchmark
  def linesBenchmark(): Option[String] =
    stringStream
      .through(text.lines[IO])
      .compile
      .last
      .unsafeRunSync()
}
