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

import cats.Functor
import cats.effect.kernel.Clock

object TimeSeriesValue {
  def apply[A](time: TimeStamp, value: A): TimeSeriesValue[A] = TimeStamped(time, Some(value))
  def tick(time: TimeStamp): TimeSeriesValue[Nothing] = TimeStamped(time, None)

  def unsafeNow[A](value: A): TimeSeriesValue[A] = TimeStamped.unsafeNow(Some(value))
  def now[F[_]: Functor: Clock, A](value: A): F[TimeSeriesValue[A]] = TimeStamped.now(Some(value))

  def unsafeNowTick: TimeSeriesValue[Nothing] = TimeStamped.unsafeNow(None)
  def nowTick[F[_]: Functor: Clock]: F[TimeSeriesValue[Nothing]] = TimeStamped.now(None)

  def lift[A](t: TimeStamped[A]): TimeSeriesValue[A] = t.map(Some.apply)
}
