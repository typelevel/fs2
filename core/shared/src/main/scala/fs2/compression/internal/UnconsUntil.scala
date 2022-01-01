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

package fs2.compression.internal

import fs2.{Chunk, INothing, Pull, Stream}
import scodec.bits.BitVector
import scodec.bits.crc.CrcBuilder

private[compression] object UnconsUntil {

  /** Like Stream.unconsN, but returns a chunk of elements that do not satisfy the predicate, splitting chunk as necessary.
    * Elements will not be dropped after the soft limit is breached.
    *
    * `Pull.pure(None)` is returned if the end of the source stream is reached.
    */
  def apply[F[_]](
      predicate: Byte => Boolean,
      softLimit: Int,
      crc32: CrcBuilder[BitVector]
  ): Stream[F, Byte] => Pull[F, INothing, Option[
    (Chunk[Byte], CrcBuilder[BitVector], Stream[F, Byte])
  ]] = {

    def checksumOnly(
        acc: Chunk[Byte],
        crc32: CrcBuilder[BitVector],
        rest: Stream[F, Byte]
    ): Pull[F, INothing, Option[(Chunk[Byte], CrcBuilder[BitVector], Stream[F, Byte])]] =
      rest.pull.uncons.flatMap {
        case None =>
          Pull.pure(None)
        case Some((hd, tl)) =>
          hd.indexWhere(predicate) match {
            case Some(i) =>
              val (pfx, sfx) = hd.splitAt(i + 1)
              Pull.pure(
                Some(
                  (
                    acc,
                    crc32.updated(pfx.toBitVector),
                    tl.cons(sfx)
                  )
                )
              )
            case None =>
              checksumOnly(acc, crc32.updated(hd.toBitVector), tl)
          }
      }

    def go(
        acc: Chunk[Byte],
        crc32: CrcBuilder[BitVector],
        rest: Stream[F, Byte],
        size: Int = 0
    ): Pull[F, INothing, Option[(Chunk[Byte], CrcBuilder[BitVector], Stream[F, Byte])]] =
      rest.pull.uncons.flatMap {
        case None =>
          Pull.pure(None)
        case Some((hd, tl)) =>
          hd.indexWhere(predicate) match {
            case Some(i) =>
              val (pfx, sfx) = hd.splitAt(i + 1)
              Pull.pure(
                Some(
                  (
                    (acc ++ pfx),
                    crc32.updated(pfx.toBitVector),
                    tl.cons(sfx)
                  )
                )
              )
            case None =>
              val newSize = size + hd.size
              if (newSize < softLimit) go(acc ++ hd, crc32.updated(hd.toBitVector), tl, newSize)
              else checksumOnly((acc ++ hd), crc32.updated(hd.toBitVector), tl)
          }
      }

    go(Chunk.empty, crc32, _)
  }

}
