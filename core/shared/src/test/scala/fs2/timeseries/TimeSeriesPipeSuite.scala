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

// Adapted from scodec-protocols, licensed under 3-clause BSD

package fs2
package timeseries

import scala.concurrent.duration._

import TimeStamped.syntax._

class TimeSeriesTransducerTest extends Fs2Suite {

  test("support combining two transducers via an either") {
    val add1: Scan[Unit, Int, Int] = Scan.lift(_ + 1)
    val add2: Scan[Unit, Int, Int] = Scan.lift(_ + 2)
    val x: Scan[Unit, Either[Int, Int], Int] = add1.choice(add2).imapState(_._1)(u => (u, u))
    val source: TimeSeries[Pure, Either[Int, Int]] =
      Stream(
        Right(1).at(0.seconds),
        Left(2).at(0.5.seconds),
        Right(3).at(1.5.seconds)
      ).through(TimeSeries.interpolateTicks(1.second))
    assertEquals(
      source.through(TimeSeries.preserve(x).toPipe).toList,
      List(
        TimeSeriesValue(0.millis, 3),
        TimeSeriesValue(500.millis, 3),
        TimeSeriesValue.tick(1000.millis),
        TimeSeriesValue(1500.millis, 5)
      )
    )
  }
}
