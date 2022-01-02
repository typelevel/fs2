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

package fs2.compression

import cats.effect.Ref
import fs2.{Chunk, Pipe, Pull, Stream}

private[fs2] object CountPipe {

  def apply[F[_]](deferredCount: Ref[F, Long]): Pipe[F, Byte, Byte] = {
    def pull(count: Long): Stream[F, Byte] => Pull[F, Byte, Long] =
      _.pull.uncons.flatMap {
        case None => Pull.pure(count)
        case Some((c: Chunk[Byte], rest: Stream[F, Byte])) =>
          Pull.output(c) >> pull(count + c.size)(rest)
      }

    def calculateSizeOf(input: Stream[F, Byte]): Pull[F, Byte, Unit] =
      for {
        count <- pull(0)(input)
        _ <- Pull.eval(deferredCount.set(count))
      } yield ()

    calculateSizeOf(_).stream
  }

}
