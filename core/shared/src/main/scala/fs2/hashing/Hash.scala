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

/** Mutable data structure that incrementally computes a hash from chunks of bytes.
  *
  * To compute a hash, call `update` one or more times and then call `digest`.
  * The result of `digest` is the hash value of all the bytes since the last call
  * to `digest`.
  *
  * A `Hash` does **not** store all bytes between calls to `digest` and hence is safe
  * for computing hashes over very large data sets using constant memory.
  *
  * A `Hash` may be called from different fibers but operations on a hash should not be called
  * concurrently.
  */
trait Hash[F[_]] {

  /** Adds the specified bytes to the current hash computation.
    */
  def update(bytes: Chunk[Byte]): F[Unit]

  /** Finalizes the hash computation, returns the result, and resets this hash for a fresh computation.
    */
  def digest: F[Digest]

  protected def unsafeUpdate(chunk: Chunk[Byte]): Unit
  protected def unsafeDigest(): Digest

  /** Returns a pipe that updates this hash computation with chunks of bytes pulled from the pipe.
    */
  def update: Pipe[F, Byte, Byte] =
    _.mapChunks { c =>
      unsafeUpdate(c)
      c
    }

  /** Returns a pipe that observes chunks from the source to the supplied sink, updating this hash with each
    * observed chunk. At completion of the source and sink, a single digest is emitted.
    */
  def observe(sink: Pipe[F, Byte, Nothing]): Pipe[F, Byte, Digest] =
    source => sink(update(source)) ++ Stream.eval(digest)

  /** Returns a pipe that outputs the digest of the source.
    */
  def drain: Pipe[F, Byte, Digest] = observe(_.drain)

  /** Returns a pipe that, at termination of the source, verifies the digest of seen bytes matches the expected value
    * or otherwise fails with a [[HashVerificationException]].
    */
  def verify(expected: Digest): Pipe[F, Byte, Byte] =
    source =>
      update(source)
        .onComplete(
          Stream
            .eval(digest)
            .flatMap(actual =>
              if (actual == expected) Stream.empty
              else Pull.fail(HashVerificationException(expected, actual)).streamNoScope
            )
        )
}

object Hash extends HashCompanionPlatform
