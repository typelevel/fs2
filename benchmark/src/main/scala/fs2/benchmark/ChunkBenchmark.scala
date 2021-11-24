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

import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}

@State(Scope.Thread)
class ChunkBenchmark {
  @Param(Array("16", "256", "4096"))
  var chunkSize: Int = _

  var ints: Chunk[Int] = _

  @Setup
  def setup() =
    ints = Chunk.seq((0 until chunkSize).map(_ + 1000)).compact

  @Benchmark
  def map(): Unit = {
    ints.map(_ + 1)
    ()
  }

  @Benchmark
  def filter(): Unit = {
    ints.filter(_ % 3 == 0)
    ()
  }
}
