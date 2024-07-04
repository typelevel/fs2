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
package hashing

import cats.effect.Sync

trait Hash[F[_]] {
  def addChunk(bytes: Chunk[Byte]): F[Unit]
  def computeAndReset: F[Chunk[Byte]]

  protected def unsafeAddChunk(slice: Chunk.ArraySlice[Byte]): Unit
  protected def unsafeComputeAndReset(): Chunk[Byte]

  def update: Pipe[F, Byte, Byte] =
    _.mapChunks { c =>
      unsafeAddChunk(c.toArraySlice)
      c
    }

  def observe(source: Stream[F, Byte], sink: Pipe[F, Byte, Nothing]): Stream[F, Byte] =
    update(source).through(sink) ++ Stream.evalUnChunk(computeAndReset)

  def hash: Pipe[F, Byte, Byte] =
    source => observe(source, _.drain)

  def verify(expected: Chunk[Byte])(implicit F: RaiseThrowable[F]): Pipe[F, Byte, Byte] =
    source =>
      update(source)
        .onComplete(
          Stream
            .eval(computeAndReset)
            .flatMap(actual =>
              if (actual == expected) Stream.empty
              else Stream.raiseError(HashVerificationException(expected, actual))
            )
        )
}

object Hash extends HashCompanionPlatform {
  def apply[F[_]: Sync](algorithm: String): F[Hash[F]] =
    Sync[F].delay(unsafe(algorithm))
}
