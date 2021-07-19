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
package io
package file
package internal

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Resource
import cats.effect.std.Hotswap
import cats.syntax.all._

private[file] object WriteRotate {

  def apply[F[_]: Concurrent](
      openNewFile: Resource[F, FileHandle[F]],
      newCursor: FileHandle[F] => F[WriteCursor[F]],
      limit: Long
  ): Pipe[F, Byte, INothing] = {

    def go(
        fileHotswap: Hotswap[F, FileHandle[F]],
        cursor: WriteCursor[F],
        acc: Long,
        s: Stream[F, Byte]
    ): Pull[F, Unit, Unit] = {
      val toWrite = (limit - acc).min(Int.MaxValue.toLong).toInt
      s.pull.unconsLimit(toWrite).flatMap {
        case Some((hd, tl)) =>
          val newAcc = acc + hd.size
          cursor.writePull(hd).flatMap { nc =>
            if (newAcc >= limit)
              Pull
                .eval {
                  fileHotswap
                    .swap(openNewFile)
                    .flatMap(newCursor)
                }
                .flatMap(nc => go(fileHotswap, nc, 0L, tl))
            else
              go(fileHotswap, nc, newAcc, tl)
          }
        case None => Pull.done
      }
    }

    in =>
      Stream
        .resource(Hotswap(openNewFile))
        .flatMap { case (fileHotswap, fileHandle) =>
          Stream.eval(newCursor(fileHandle)).flatMap { cursor =>
            go(fileHotswap, cursor, 0L, in).stream.drain
          }
        }
  }

}
