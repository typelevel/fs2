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
package fs2.timeseries

object TimeSeriesPipe {

  def lift[F[_], A, B](f: A => B): TimeSeriesPipe[F, A, B] =
    _.map(_.map(_.map(f)))

  def drainRight[F[_], L, R]: TimeSeriesPipe[F, Either[L, R], L] = _.collect {
    case tick @ TimeStamped(ts, None)   => tick.asInstanceOf[TimeSeriesValue[L]]
    case TimeStamped(ts, Some(Left(l))) => TimeStamped(ts, Some(l))
  }

  def drainLeft[F[_], L, R]: TimeSeriesPipe[F, Either[L, R], R] = _.collect {
    case tick @ TimeStamped(ts, None)    => tick.asInstanceOf[TimeSeriesValue[R]]
    case TimeStamped(ts, Some(Right(r))) => TimeStamped(ts, Some(r))
  }
}
