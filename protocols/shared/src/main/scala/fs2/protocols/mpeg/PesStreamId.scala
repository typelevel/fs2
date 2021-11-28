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

object PesStreamId {
  // format: off
  val ProgramStreamMap =               0xb6
  val PrivateStream1 =                 0xb7
  val PaddingStream =                  0xbe
  val PrivateStream2 =                 0xbf
  val AudioStreamMin =                 0xc0
  val AudioStreamMax =                 0xdf
  val VideoStreamMin =                 0xe0
  val VideoStreamMax =                 0xef
  val ECM =                            0xf0
  val EMM =                            0xf1
  val DSMCC =                          0xf2
  val `ISO/IEC 13522` =                0xf3
  val `ITU-T Rec. H.222.1 type A` =    0xf4
  val `ITU-T Rec. H.222.1 type B` =    0xf5
  val `ITU-T Rec. H.222.1 type C` =    0xf6
  val `ITU-T Rec. H.222.1 type D` =    0xf7
  val `ITU-T Rec. H.222.1 type E` =    0xf8
  val Ancillary =                      0xf9
  val `ISO/IEC14496-1 SL Packetized` = 0xfa
  val `ISO/IEC14496-1 FlexMux` =       0xfb
  val ReservedMin =                    0xfc
  val ReservedMax =                    0xfe
  val ProgramStreamDirectory =         0xff
  // format: on
}
