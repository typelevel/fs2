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

import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}

import cats.syntax.all._

@State(Scope.Thread)
class ChunkFlatten {
  @Param(Array("8", "32", "128"))
  var numberChunks: Int = _

  @Param(Array("1", "2", "10", "50"))
  var chunkSize: Int = _

  case class Obj(dummy: Boolean)
  object Obj {
    def create: Obj = Obj(true)
  }

  var chunkSeq: Seq[Chunk[Obj]] = _

  @Setup
  def setup() =
    chunkSeq = Seq.range(0, numberChunks).map(_ => Chunk.from(Seq.fill(chunkSize)(Obj.create)))

  @Benchmark
  def flatten(): Unit = {
    Chunk.from(chunkSeq).flatten
    ()
  }

}
