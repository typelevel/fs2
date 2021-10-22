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

package fs2.protocols.mpeg

import scodec.bits._

object PesStreamId {
  // format:off
  val ProgramStreamMap =               bin"1011 1100".toInt(signed = false)
  val PrivateStream1 =                 bin"1011 1101".toInt(signed = false)
  val PaddingStream =                  bin"1011 1110".toInt(signed = false)
  val PrivateStream2 =                 bin"1011 1111".toInt(signed = false)
  val AudioStreamMin =                 bin"1100 0000".toInt(signed = false)
  val AudioStreamMax =                 bin"1101 1111".toInt(signed = false)
  val VideoStreamMin =                 bin"1110 0000".toInt(signed = false)
  val VideoStreamMax =                 bin"1110 1111".toInt(signed = false)
  val ECM =                            bin"1111 0000".toInt(signed = false)
  val EMM =                            bin"1111 0001".toInt(signed = false)
  val DSMCC =                          bin"1111 0010".toInt(signed = false)
  val `ISO/IEC 13522` =                bin"1111 0011".toInt(signed = false)
  val `ITU-T Rec. H.222.1 type A` =    bin"1111 0100".toInt(signed = false)
  val `ITU-T Rec. H.222.1 type B` =    bin"1111 0101".toInt(signed = false)
  val `ITU-T Rec. H.222.1 type C` =    bin"1111 0110".toInt(signed = false)
  val `ITU-T Rec. H.222.1 type D` =    bin"1111 0111".toInt(signed = false)
  val `ITU-T Rec. H.222.1 type E` =    bin"1111 1000".toInt(signed = false)
  val Ancillary =                      bin"1111 1001".toInt(signed = false)
  val `ISO/IEC14496-1 SL Packetized` = bin"1111 1010".toInt(signed = false)
  val `ISO/IEC14496-1 FlexMux` =       bin"1111 1011".toInt(signed = false)
  val ReservedMin =                    bin"1111 1100".toInt(signed = false)
  val ReservedMax =                    bin"1111 1110".toInt(signed = false)
  val ProgramStreamDirectory =         bin"1111 1111".toInt(signed = false)
  // format:on
}
