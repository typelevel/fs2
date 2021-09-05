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

class TimeSeriesSuite extends Fs2Suite {

  def ts(value: Int) = TimeStamped(value.seconds, value)

  test("interpolating time ticks in a timestamped stream") {
    val events = Stream(ts(1), ts(2), ts(3))
    val withTicks1s = events.through(TimeSeries.interpolateTicks(1.second)).toList
    assertEquals(
      withTicks1s,
      List(
        Some(1) at 1.seconds,
        None at 2.seconds,
        Some(2) at 2.seconds,
        None at 3.seconds,
        Some(3) at 3.seconds
      )
    )
    val withTicks300ms = events.through(TimeSeries.interpolateTicks(300.millis)).toList
    assertEquals(
      withTicks300ms,
      List(
        Some(1) at 1.second,
        None at 1.3.seconds,
        None at 1.6.seconds,
        None at 1.9.seconds,
        Some(2) at 2.seconds,
        None at 2.2.seconds,
        None at 2.5.seconds,
        None at 2.8.seconds,
        Some(3) at 3.seconds
      )
    )
  }
}
