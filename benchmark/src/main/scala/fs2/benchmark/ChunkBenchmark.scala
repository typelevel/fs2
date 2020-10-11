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

import cats.syntax.all._

@State(Scope.Thread)
class ChunkBenchmark {
  @Param(Array("16", "256", "4096"))
  var chunkSize: Int = _

  @Param(Array("1", "20", "50", "100"))
  var chunkCount: Int = _

  case class Obj(dummy: Boolean)
  object Obj {
    def create: Obj = Obj(true)
  }

  var chunkSeq: Seq[Chunk[Obj]] = _
  var flattened: Chunk[Obj] = _
  var sizeHint: Int = _

  @Setup
  def setup() = {
    chunkSeq = Seq.range(0, chunkCount).map(_ => Chunk.seq(Seq.fill(chunkSize)(Obj.create)))
    sizeHint = chunkSeq.foldLeft(0)(_ + _.size)
    flattened = Chunk.seq(chunkSeq).flatten
  }

  @Benchmark
  def concat(): Unit =
    Chunk.concat(chunkSeq, sizeHint)

  @Benchmark
  def traverse(): Boolean = {
    val fn: () => Chunk[Boolean] =
      flattened.traverse { obj =>
        if (obj.dummy) { () => true }
        else { () => false }
      }

    fn().isEmpty
  }

}
