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

import scodec.*
import scodec.bits.*
import scodec.codecs.*
import org.typelevel.scalaccompat.annotation.*

case class NTPPacket(
    li: LeapIndicator,
    version: Int,
    mode: NTPMode,
    stratum: Int,
    poll: Int,
    precision: Int,
    rootDelay: Long,
    rootDispersion: Long,
    referenceID: Long,
    referenceTime: BigInt,
    originTime: BigInt,
    receiveTime: BigInt,
    transmitTime: BigInt
)
@nowarn3("msg=unused import")
object NTPPacket extends NTPPacketCompat {
  import scodec.compat.*

  val default = NTPPacket(
    li = LeapIndicator.NoAdjustment,
    version = 3,
    mode = NTPMode.Client,
    stratum = 0,
    poll = 0,
    precision = 0x20,
    rootDelay = 0,
    rootDispersion = 0,
    referenceID = 0,
    referenceTime = 0,
    originTime = 0,
    receiveTime = 0,
    transmitTime = 0
  )

  private[ntp] val ulong64: Codec[BigInt] = bytes(8)
    .xmap(
      _.toBigInt(false),
      ByteVector.fromBigInt(_, Some(8))
    )

  private[ntp] val liVnMode: Codec[LeapIndicator *: Int *: NTPMode *: EmptyTuple] = uint8.xmap(
    octet => {
      val li = LeapIndicator.from(octet >> 6)
      val version = (octet >> 3) & 0x07
      val mode = NTPMode.from(octet & 0x07)
      li *: version *: mode *: EmptyTuple
    },
    { case li *: version *: mode *: EmptyTuple =>
      ((li.value & 0x3) << 6) | ((version & 0x07) << 3) | (mode.value & 0x07)
    }
  )

  private[ntp] def layout =
    ("li / version / mode" | liVnMode) ::
      ("stratum" | uint8) ::
      ("poll" | uint8) ::
      ("precision" | uint8) ::
      ("root delay" | uint32) ::
      ("root dispersion" | uint32) ::
      ("reference id" | uint32) ::
      ("reference time" | ulong64) ::
      ("origin time" | ulong64) ::
      ("receive time" | ulong64) ::
      ("transmit time" | ulong64)
}
