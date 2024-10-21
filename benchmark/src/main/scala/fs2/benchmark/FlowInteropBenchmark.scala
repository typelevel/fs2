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

import org.openjdk.jmh.annotations.{
  Benchmark,
  BenchmarkMode,
  Mode,
  OutputTimeUnit,
  Param,
  Scope,
  State
}

import java.util.concurrent.TimeUnit
import java.util.concurrent.Flow.{Publisher, Subscriber, Subscription}

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class FlowInteropBenchmark {
  @Param(Array("1024", "5120", "10240", "51200", "512000"))
  var totalElements: Long = _

  @Param(Array("1000"))
  var iterations: Int = _

  @Benchmark
  def fastPublisher(): Unit = {
    def publisher =
      new Publisher[Unit] {
        override final def subscribe(subscriber: Subscriber[? >: Unit]): Unit =
          subscriber.onSubscribe(
            new Subscription {
              var i: Long = 0
              @volatile var canceled: Boolean = false

              // Sequential fast Publisher.
              override final def request(n: Long): Unit = {
                val elementsToProduce = math.min(i + n, totalElements)

                while (i < elementsToProduce) {
                  subscriber.onNext(())
                  i += 1
                }

                if (i == totalElements || canceled) {
                  subscriber.onComplete()
                }
              }

              override final def cancel(): Unit =
                canceled = true
            }
          )
      }

    val stream =
      interop.flow.fromPublisher[IO](publisher, chunkSize = 512)

    val program =
      stream.compile.drain

    program.replicateA_(iterations).unsafeRunSync()
  }
}
