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
package compression

import cats.syntax.all._
import cats.effect.kernel.Sync

sealed trait Checksum[F[_]] {

  /** Calculates the CRC32 checksum. Emits the current (accumulated) value for every chunk consumed.
    */
  def crc32: Pipe[F, Byte, Long]

}

object Checksum {

  def apply[F[_]](implicit cs: Checksum[F]): Checksum[F] = cs

  implicit def forSync[F[_]](implicit F: Sync[F]): Checksum[F] = new Checksum[F] {

    override def crc32: Pipe[F, Byte, Long] = in =>
      Stream.eval(F.delay(new CRC32)).flatMap { crc32 =>
        in.chunks.evalMap(c => F.delay(crc32.update(c.toArray)) >> F.delay(crc32.getValue()))
      }

  }

}
