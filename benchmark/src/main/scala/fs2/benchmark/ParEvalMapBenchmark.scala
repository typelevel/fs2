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
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

@State(Scope.Thread)
class ParEvalMapBenchmark {
  @Param(Array("100", "10000"))
  var size: Int = _

  @Param(Array("10", "100"))
  var chunkSize: Int = _

  private def dummyLoad = IO.delay(())

  @Benchmark
  def evalMap(): Unit =
    execute(getStream.evalMap(_ => dummyLoad))

  @Benchmark
  def parEvalMap10(): Unit =
    execute(getStream.parEvalMap(10)(_ => dummyLoad))

  @Benchmark
  def parEvalMapUnordered10(): Unit =
    execute(getStream.parEvalMapUnordered(10)(_ => dummyLoad))

  private def getStream: Stream[IO, Unit] =
    Stream.constant((), chunkSize).take(size.toLong).covary[IO]
  private def execute(s: Stream[IO, Unit]): Unit = s.compile.drain.unsafeRunSync()
}
