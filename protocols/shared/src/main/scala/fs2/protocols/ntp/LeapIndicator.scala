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

package fs2.protocols.ntp

sealed trait LeapIndicator {
  def value: Int = this match {
    case LeapIndicator.NoAdjustment   => 0
    case LeapIndicator.AddSecond      => 1
    case LeapIndicator.SubtractSecond => 2
    case LeapIndicator.Unsynchronized => 3
  }
}

object LeapIndicator {
  def from(value: Int): LeapIndicator = value match {
    case 0 => LeapIndicator.NoAdjustment
    case 1 => LeapIndicator.AddSecond
    case 2 => LeapIndicator.SubtractSecond
    case 3 => LeapIndicator.Unsynchronized
  }
  case object NoAdjustment extends LeapIndicator
  case object AddSecond extends LeapIndicator
  case object SubtractSecond extends LeapIndicator
  case object Unsynchronized extends LeapIndicator
}
