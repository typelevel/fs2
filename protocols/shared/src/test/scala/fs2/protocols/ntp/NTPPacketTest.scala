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
package protocols
package ntp
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop
import scodec.*
import scodec.Attempt.Successful
import scodec.bits.*
import munit.Location
import org.typelevel.scalaccompat.annotation.*

@nowarn3("msg=unused import")
class NTPPacketTest extends Fs2Suite {
  import scodec.compat.*
  import NTPPacketTestData._
  test("ntp packet default roundtrip") {
    roundtrip[NTPPacket](NTPPacket.codec, NTPPacket.default)
  }

  test("ulong64 roundtrip without long overflow") {
    val Successful(encode) = NTPPacket.ulong64.encode(BigInt(Long.MaxValue) + 1): @unchecked
    val Successful(decode) = NTPPacket.ulong64.decode(encode): @unchecked
    assertEquals(decode.value, BigInt(Long.MaxValue) + 1)
  }

  test("arbitrary li vi mode roundtrip") {
    Prop.forAll((tuple: LeapIndicator *: Int *: NTPMode *: EmptyTuple) =>
      roundtrip(NTPPacket.liVnMode, tuple)
    )
  }

  test("arbitrary ntp packet roundtrip") {
    Prop.forAll((packet: NTPPacket) => roundtrip(NTPPacket.codec, packet))
  }

  private def roundtrip[A](codec: Codec[A], value: A)(implicit loc: Location): Unit = {
    val Successful(encode) = codec.encode(value): @unchecked
    val Successful(decode) = codec.decode(encode): @unchecked
    assertEquals(decode.value, value)
  }
}

@nowarn3("msg=unused import")
object NTPPacketTestData {
  import scodec.compat.*
  lazy val genUInt8 = Gen.choose(0, 0xff)
  lazy val genUInt32 = Gen.choose(0L, 0xffffffffL)
  lazy val genULong64 = Gen.choose(BigInt(0), BigInt("18446744073709551615"))
  lazy val genLeapIndicator = Gen.choose[Int](0, 3).map(LeapIndicator.from)
  lazy val genNTPMode = Gen.choose[Int](0, 7).map(NTPMode.from)
  lazy val genLiVnMode = for {
    li <- genLeapIndicator
    vn <- Gen.choose[Int](bin"001".toInt(false), bin"111".toInt(false))
    mode <- genNTPMode
  } yield li *: vn *: mode *: EmptyTuple
  lazy val genNTPPacket = for {
    liVnMode <- genLiVnMode
    li *: vn *: mode *: EmptyTuple = liVnMode
    stratum <- genUInt8
    poll <- genUInt8
    precision <- genUInt8
    rootDelay <- genUInt32
    rootDispersion <- genUInt32
    referenceID <- genUInt32
    referenceTime <- genULong64
    originTime <- genULong64
    receiveTime <- genULong64
    transmitTime <- genULong64
  } yield NTPPacket(
    li,
    vn,
    mode,
    stratum,
    poll,
    precision,
    rootDelay,
    rootDispersion,
    referenceID,
    referenceTime,
    originTime,
    receiveTime,
    transmitTime
  )
  implicit val arbitraryLiVnMode: Arbitrary[LeapIndicator *: Int *: NTPMode *: EmptyTuple] =
    Arbitrary(genLiVnMode)
  implicit val arbitraryNTPPacket: Arbitrary[NTPPacket] = Arbitrary(genNTPPacket)
}
