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

sealed trait NTPMode {
  def value: Int = this match {
    case NTPMode.Reserved         => 0
    case NTPMode.SymmetricActive  => 1
    case NTPMode.SymmetricPassive => 2
    case NTPMode.Client           => 3
    case NTPMode.Server           => 4
    case NTPMode.Broadcast        => 5
    case NTPMode.ControlMessage   => 6
    case NTPMode.ReservedPrivate  => 7
  }
}
object NTPMode {
  case object Reserved extends NTPMode
  case object SymmetricActive extends NTPMode
  case object SymmetricPassive extends NTPMode
  case object Client extends NTPMode
  case object Server extends NTPMode

  case object Broadcast extends NTPMode
  case object ControlMessage extends NTPMode
  case object ReservedPrivate extends NTPMode

  def from(mode: Int): NTPMode = mode match {
    case 0 => Reserved
    case 1 => SymmetricActive
    case 2 => SymmetricPassive
    case 3 => Client
    case 4 => Server
    case 5 => Broadcast
    case 6 => ControlMessage
    case 7 => ReservedPrivate
  }
}
