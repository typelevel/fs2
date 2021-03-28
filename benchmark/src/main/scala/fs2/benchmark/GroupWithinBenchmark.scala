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
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

import scala.concurrent.duration._

@State(Scope.Thread)
class GroupWithinBenchmark {

  /*  For Branch 2.5.x
  import cats.effect.{Timer, ContextShift}
  import scala.concurrent.ExecutionContext
  lazy protected implicit val ioTimer: Timer[IO] = IO.timer(ExecutionContext.global)

  lazy protected implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)
   */

  // only for branch 3.x
  import cats.effect.unsafe.implicits.global

  val bufferWindow = 100.micros

  @Param(Array("100", "10000", "100000"))
  var rangeLength: Int = _

  @Param(Array("16", "256", "4096"))
  var bufferSize: Int = _

  @Benchmark
  def groupWithin(): Unit =
    Stream
      .range(0, rangeLength)
      .covary[IO]
      .groupWithin(bufferSize, bufferWindow)
      .compile
      .drain
      .unsafeRunSync()
}
