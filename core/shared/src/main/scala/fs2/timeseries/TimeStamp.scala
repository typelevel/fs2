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

import scala.concurrent.duration.FiniteDuration

import cats.Functor
import cats.syntax.all._
import cats.effect.kernel.Clock

final case class TimeStamp(toEpochMilli: Long) extends Ordered[TimeStamp] {
  def +(millis: Long): TimeStamp = TimeStamp(toEpochMilli + millis)
  def +(duration: FiniteDuration): TimeStamp = TimeStamp(toEpochMilli + duration.toMillis)
  def isBefore(that: TimeStamp): Boolean = toEpochMilli < that.toEpochMilli
  def compare(that: TimeStamp): Int = toEpochMilli.compareTo(that.toEpochMilli)
}

object TimeStamp {
  def fromSeconds(seconds: Long): TimeStamp = apply(seconds * 1000L)
  def fromMillis(millis: Long): TimeStamp = apply(millis)
  def unsafeNow(): TimeStamp = TimeStamp(System.currentTimeMillis())
  def now[F[_]: Functor: Clock]: F[TimeStamp] = Clock[F].realTime.map(d => TimeStamp(d.toMillis))
}
