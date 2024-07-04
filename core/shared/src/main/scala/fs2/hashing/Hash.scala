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

/** Mutable data structure that incrementally computes a hash from chunks of bytes.
  *
  * To compute a hash, call `addChunk` one or more times and then call `computeAndReset`.
  * The result of `computeAndReset` is the hash value of all the bytes since the last call
  * to `computeAndReset`.
  *
  * A `Hash` does **not** store all bytes between calls to `computeAndReset` and hence is safe
  * for computing hashes over very large data sets using constant memory.
  *
  * A `Hash` may be called from different fibers but operations on a hash should not be called
  * concurrently.
  */
trait Hash[F[_]] {

  /** Adds the specified bytes to the current hash computation.
    */
  def addChunk(bytes: Chunk[Byte]): F[Unit]

  /** Finalizes the hash computation, returns the result, and resets this hash for a fresh computation.
    */
  def computeAndReset: F[Chunk[Byte]]

  protected def unsafeAddChunk(slice: Chunk.ArraySlice[Byte]): Unit
  protected def unsafeComputeAndReset(): Chunk[Byte]

  /** Returns a pipe that updates this hash computation with chunks of bytes pulled from the pipe.
    */
  def update: Pipe[F, Byte, Byte] =
    _.mapChunks { c =>
      unsafeAddChunk(c.toArraySlice)
      c
    }

  /** Returns a stream that when pulled, pulls on the source, updates this hash with bytes emitted,
    * and sends those bytes to the supplied sink. Upon termination of the source and sink, the hash is emitted.
    */
  def observe(source: Stream[F, Byte], sink: Pipe[F, Byte, Nothing]): Stream[F, Byte] =
    update(source).through(sink) ++ Stream.evalUnChunk(computeAndReset)

  /** Pipe that outputs the hash of the source after termination of the source.
    */
  def hash: Pipe[F, Byte, Byte] =
    source => observe(source, _.drain)

  /** Pipe that, at termination of the source, verifies the hash of seen bytes matches the expected value
    * or otherwise fails with a [[HashVerificationException]].
    */
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
