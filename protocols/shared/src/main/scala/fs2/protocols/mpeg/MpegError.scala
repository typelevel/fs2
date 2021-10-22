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

import scodec.Err
import scodec.bits.BitVector

trait MpegError {
  def message: String
}

object MpegError {

  case class General(message: String) extends MpegError {
    override def toString = message
  }
  case class Decoding(data: BitVector, err: Err) extends MpegError {
    def message = s"error encountered when decoding: $err ${data.toHex}"
    override def toString = message
  }

  def joinErrors[S, I, O](
      t: Scan[S, I, Either[MpegError, O]]
  ): Scan[S, Either[MpegError, I], Either[MpegError, O]] =
    t.semipass(_.fold(e => Left(Left(e)), i => Right(i)))

  def passErrors[S, I, O](t: Scan[S, I, O]): Scan[S, Either[MpegError, I], Either[MpegError, O]] =
    t.right
}
