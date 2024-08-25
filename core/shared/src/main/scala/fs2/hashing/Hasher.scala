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
  * To compute a hash, call `update` one or more times and then call `hash`.
  * The result of `hash` is the hash value of all the bytes since the last call
  * to `hash`.
  *
  * A `Hasher` does **not** store all bytes between calls to `hash` and hence is safe
  * for computing hashes over very large data sets using constant memory.
  *
  * A `Hasher` may be called from different fibers but operations on a hash should not be called
  * concurrently.
  */
trait Hasher[F[_]] {

  /** Adds the specified bytes to the current hash computation.
    */
  def update(bytes: Chunk[Byte]): F[Unit]

  /** Finalizes the hash computation, returns the result, and resets this hasher for a fresh computation.
    */
  def hash: F[Hash]

  protected def unsafeUpdate(chunk: Chunk[Byte]): Unit
  protected def unsafeHash(): Hash

  /** Returns a pipe that updates this hash computation with chunks of bytes pulled from the pipe.
    */
  def update: Pipe[F, Byte, Byte] =
    _.mapChunks { c =>
      unsafeUpdate(c)
      c
    }

  /** Returns a pipe that observes chunks from the source to the supplied sink, updating this hash with each
    * observed chunk. At completion of the source and sink, a single hash is emitted.
    */
  def observe(sink: Pipe[F, Byte, Nothing]): Pipe[F, Byte, Hash] =
    source => sink(update(source)) ++ Stream.eval(hash)

  /** Returns a pipe that outputs the hash of the source.
    */
  def drain: Pipe[F, Byte, Hash] = observe(_.drain)

  /** Returns a pipe that, at termination of the source, verifies the hash of seen bytes matches the expected value
    * or otherwise fails with a [[HashVerificationException]].
    */
  def verify(expected: Hash): Pipe[F, Byte, Byte] =
    source =>
      update(source)
        .onComplete(
          Stream
            .eval(hash)
            .flatMap(actual =>
              if (actual == expected) Stream.empty
              else Pull.fail(HashVerificationException(expected, actual)).streamNoScope
            )
        )
}

object Hasher extends HasherCompanionPlatform

private[hashing] abstract class SyncHasher[F[_]: Sync] extends Hasher[F] {
  def update(bytes: Chunk[Byte]): F[Unit] =
    Sync[F].delay(unsafeUpdate(bytes))

  def hash: F[Hash] =
    Sync[F].delay(unsafeHash())
}
