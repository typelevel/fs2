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

import cats.effect.{IO, LiftIO, Resource, Sync, SyncIO}

/** Capability trait that provides hashing.
  *
  * The [[create]] method returns a fresh `Hash` object as a resource. `Hash` is a
  * mutable object that supports incremental computation of hashes.
  *
  * A `Hash` instance should be created for each hash you want to compute, though `Hash`
  * objects may be reused to compute consecutive hashes. When doing so, care must be taken
  * to ensure no concurrent usage.
  *
  * The `hashWith` operation converts a `Resource[F, Hash[F]]` to a `Pipe[F, Byte, Digest]`.
  * The resulting pipe outputs a single `Digest` once the source byte stream terminates.
  *
  * Alternatively, a `Resource[F, Hash[F]]` can be used directly (via `.use` or via
  * `Stream.resource`). The `Hash[F]` trait provides lower level operations for computing
  * hashes, both at an individual chunk level (via `update` and `digest`) and at stream level
  * (e.g., via `observe` and `drain`).
  *
  * Finally, the `Hashing` companion object offers utilities for computing pure hashes:
  * `hashPureStream` and `hashChunk`.
  */
sealed trait Hashing[F[_]] {

  /** Creates a new hash using the specified hashing algorithm. */
  def create(algorithm: HashAlgorithm): Resource[F, Hash[F]]

  /** Creates a new MD-5 hash. */
  def md5: Resource[F, Hash[F]] = create(HashAlgorithm.MD5)

  /** Creates a new SHA-1 hash. */
  def sha1: Resource[F, Hash[F]] = create(HashAlgorithm.SHA1)

  /** Creates a new SHA-224 hash. */
  def sha224: Resource[F, Hash[F]] = create(HashAlgorithm.SHA224)

  /** Creates a new SHA-256 hash. */
  def sha256: Resource[F, Hash[F]] = create(HashAlgorithm.SHA256)

  /** Creates a new SHA-384 hash. */
  def sha384: Resource[F, Hash[F]] = create(HashAlgorithm.SHA384)

  /** Creates a new SHA-512 hash. */
  def sha512: Resource[F, Hash[F]] = create(HashAlgorithm.SHA512)

  /** Creates a new SHA-512/224 hash. */
  def sha512_224: Resource[F, Hash[F]] = create(HashAlgorithm.SHA512_224)

  /** Creates a new SHA-512/256 hash. */
  def sha512_256: Resource[F, Hash[F]] = create(HashAlgorithm.SHA512_256)

  /** Creates a new SHA3-224 hash. */
  def sha3_224: Resource[F, Hash[F]] = create(HashAlgorithm.SHA3_224)

  /** Creates a new SHA3-256 hash. */
  def sha3_256: Resource[F, Hash[F]] = create(HashAlgorithm.SHA3_256)

  /** Creates a new SHA3-384 hash. */
  def sha3_384: Resource[F, Hash[F]] = create(HashAlgorithm.SHA3_384)

  /** Creates a new SHA3-512 hash. */
  def sha3_512: Resource[F, Hash[F]] = create(HashAlgorithm.SHA3_512)

  /** Returns a pipe that hashes the source byte stream and outputs the hash.
    *
    * For more sophisticated use cases, such as writing the contents of a stream
    * to a file while simultaneously computing a hash, use `create` or `sha256` or
    * similar to create a `Hash[F]`.
    */
  def hashWith(hash: Resource[F, Hash[F]]): Pipe[F, Byte, Digest]
}

object Hashing {

  def apply[F[_]](implicit F: Hashing[F]): F.type = F

  def forSync[F[_]: Sync]: Hashing[F] = new Hashing[F] {
    def create(algorithm: HashAlgorithm): Resource[F, Hash[F]] =
      Hash[F](algorithm)

    def hashWith(hash: Resource[F, Hash[F]]): Pipe[F, Byte, Digest] =
      source => Stream.resource(hash).flatMap(_.drain(source))
  }

  implicit def forSyncIO: Hashing[SyncIO] = forSync

  def forIO: Hashing[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Sync: LiftIO]: Hashing[F] = {
    val _ = LiftIO[F]
    forSync
  }

  /** Returns the hash of the supplied stream. */
  def hashPureStream(algorithm: HashAlgorithm, source: Stream[Pure, Byte]): Digest =
    Hashing[SyncIO]
      .create(algorithm)
      .use(h => h.drain(source).compile.lastOrError)
      .unsafeRunSync()

  /** Returns the hash of the supplied chunk. */
  def hashChunk(algorithm: HashAlgorithm, chunk: Chunk[Byte]): Digest =
    Hashing[SyncIO]
      .create(algorithm)
      .use(h => h.update(chunk) >> h.digest)
      .unsafeRunSync()
}
