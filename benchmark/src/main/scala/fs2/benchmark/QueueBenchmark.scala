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

package fs2.benchmark

import cats.effect.{Concurrent, IO}
import cats.effect.unsafe.implicits.global
import fs2._
import fs2.concurrent.Queue
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

@State(Scope.Thread)
class QueueBenchmark {

  val size = 100000

  @Param(Array("1", "2", "5", "10", "50", "100"))
  var n: Int = _

  @Benchmark
  def chunkedQueue10k(): Unit =
    Queue
      .unbounded[IO, Int]
      .flatMap { q =>
        implicitly[Concurrent[IO]].start(Stream.constant(1, n).take(size).through(q.enqueue).compile.drain) >>
          q.dequeue.take(size).compile.drain
      }
      .unsafeRunSync()

  @Benchmark
  def chunkedQueueManually10k(): Unit =
    Queue
      .unbounded[IO, Chunk[Int]]
      .flatMap { q =>
        implicitly[Concurrent[IO]].start(
          Stream.constant(1, n).take(size).chunks.through(q.enqueue).compile.drain
        ) >>
          q.dequeue.flatMap(Stream.chunk).take(size).compile.drain
      }
      .unsafeRunSync()
}
