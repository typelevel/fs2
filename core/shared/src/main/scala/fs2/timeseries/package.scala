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

package object timeseries {

  /** A single value in a `TimeSeries`. Provides a timestamp along with either a value of type `A` or
    * a clock tick (represented by a none).
    */
  type TimeSeriesValue[+A] = TimeStamped[Option[A]]

  /** A stream of timestamped values or clock ticks.
    *
    * Values are represented as right values in a `TimeStamped[Option[A]]`, whereas
    * clock ticks are represented as nones. This encoding allows for an indication
    * of time passage with no observed values.
    *
    * Generally, time series appear in increasing order, and many combinators that work with
    * time series will rely on that. For streams that are globally ordered, but not locally ordered,
    * i.e., near adjacent values might be out of order but values at great distance from each other
    * are ordered, consider using `TimeStamped.reorderLocally` to adjust.
    */
  type TimeSeries[F[_], +A] = Stream[F, TimeSeriesValue[A]]

  /** Alias for a pipe on a time series. */
  type TimeSeriesPipe[F[_], -A, +B] = Stream[F, TimeSeriesValue[A]] => Stream[F, TimeSeriesValue[B]]
}
