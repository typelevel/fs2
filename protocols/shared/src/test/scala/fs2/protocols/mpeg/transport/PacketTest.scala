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
package protocols
package mpeg
package transport
package psi

import scodec.bits._

class PacketTest extends Fs2Suite {

  test("support packetizing multiple sections in to a single packet") {
    val a = ByteVector.fill(10)(0).bits
    val b = ByteVector.fill(10)(1).bits
    val c = ByteVector.fill(10)(2).bits
    val sections = Vector(a, b, c)
    val packets = Packet.packetizeMany(Pid(0), ContinuityCounter(0), sections)
    assertEquals(
      packets,
      Vector(
        Packet.payload(
          Pid(0),
          ContinuityCounter(0),
          Some(0),
          a ++ b ++ c ++ BitVector.fill((183 * 8) - a.size - b.size - c.size)(true)
        )
      )
    )
  }

  test("support packetizing multiple sections across multiple packets") {
    val sections = (0 until 256).map(x => ByteVector.fill(10)(x).bits).toVector
    val data = sections.foldLeft(BitVector.empty)(_ ++ _)
    val packets = Packet.packetizeMany(Pid(0), ContinuityCounter(0), sections)

    packets.zipWithIndex.foreach { case (packet, idx) =>
      val payloadOffset = if (idx == 0) 0 else 10 * ((idx * 183) / 10 + 1) - (idx * 183)
      val offset = 183L * 8 * idx
      assertEquals(
        packets(idx),
        Packet.payload(
          Pid(0),
          ContinuityCounter(idx),
          Some(payloadOffset),
          data.drop(offset).take(183 * 8)
        )
      )
    }
  }
}
