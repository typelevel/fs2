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
  * The [[hasher]] method returns a fresh `Hasher` object as a resource. `Hasher` is a
  * mutable object that supports incremental computation of hashes.
  *
  * A `Hasher` instance should be created for each hash you want to compute, though `Hasher`
  * objects may be reused to compute consecutive hashes. When doing so, care must be taken
  * to ensure no concurrent usage.
  *
  * The `hashWith` operation converts a `Resource[F, Hasher[F]]` to a `Pipe[F, Byte, Hash]`.
  * The resulting pipe outputs a single `Hash` once the source byte stream terminates.
  *
  * Alternatively, a `Resource[F, Hasher[F]]` can be used directly (via `.use` or via
  * `Stream.resource`). The `Hasher[F]` trait provides lower level operations for computing
  * hashes, both at an individual chunk level (via `update` and `digest`) and at stream level
  * (e.g., via `observe` and `drain`).
  *
  * Finally, the `Hashing` companion object offers utilities for computing pure hashes:
  * `hashPureStream` and `hashChunk`.
  */
sealed trait Hashing[F[_]] {

  /** Creates a new hasher using the specified hashing algorithm. */
  def hasher(algorithm: HashAlgorithm): Resource[F, Hasher[F]]

  /** Creates a new MD-5 hasher. */
  def md5: Resource[F, Hasher[F]] = hasher(HashAlgorithm.MD5)

  /** Creates a new SHA-1 hasher. */
  def sha1: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA1)

  /** Creates a new SHA-224 hasher. */
  def sha224: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA224)

  /** Creates a new SHA-256 hasher. */
  def sha256: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA256)

  /** Creates a new SHA-384 hasher. */
  def sha384: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA384)

  /** Creates a new SHA-512 hasher. */
  def sha512: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA512)

  /** Creates a new SHA-512/224 hasher. */
  def sha512_224: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA512_224)

  /** Creates a new SHA-512/256 hasher. */
  def sha512_256: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA512_256)

  /** Creates a new SHA3-224 hasher. */
  def sha3_224: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA3_224)

  /** Creates a new SHA3-256 hasher. */
  def sha3_256: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA3_256)

  /** Creates a new SHA3-384 hasher. */
  def sha3_384: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA3_384)

  /** Creates a new SHA3-512 hasher. */
  def sha3_512: Resource[F, Hasher[F]] = hasher(HashAlgorithm.SHA3_512)

  /** Creates a new hasher using the specified HMAC algorithm. */
  def hmac(algorithm: HashAlgorithm, key: Chunk[Byte]): Resource[F, Hasher[F]]

  /** Returns a pipe that hashes the source byte stream and outputs the hash.
    *
    * For more sophisticated use cases, such as writing the contents of a stream
    * to a file while simultaneously computing a hash, use `hasher` or `sha256` or
    * similar to create a `Hasher[F]`.
    */
  def hashWith(hash: Resource[F, Hasher[F]]): Pipe[F, Byte, Hash]
}

object Hashing {

  def apply[F[_]](implicit F: Hashing[F]): F.type = F

  def forSync[F[_]: Sync]: Hashing[F] = new Hashing[F] {
    def hasher(algorithm: HashAlgorithm): Resource[F, Hasher[F]] =
      Hasher[F](algorithm)

    def hmac(algorithm: HashAlgorithm, key: Chunk[Byte]): Resource[F, Hasher[F]] =
      Hasher.hmac[F](algorithm, key)

    def hashWith(hash: Resource[F, Hasher[F]]): Pipe[F, Byte, Hash] =
      source => Stream.resource(hash).flatMap(_.drain(source))
  }

  implicit def forSyncIO: Hashing[SyncIO] = forSync

  def forIO: Hashing[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Sync: LiftIO]: Hashing[F] = {
    val _ = LiftIO[F]
    forSync
  }

  /** Returns the hash of the supplied stream. */
  def hashPureStream(algorithm: HashAlgorithm, source: Stream[Pure, Byte]): Hash =
    Hashing[SyncIO]
      .hasher(algorithm)
      .use(h => h.drain(source).compile.lastOrError)
      .unsafeRunSync()

  /** Returns the hash of the supplied chunk. */
  def hashChunk(algorithm: HashAlgorithm, chunk: Chunk[Byte]): Hash =
    Hashing[SyncIO]
      .hasher(algorithm)
      .use(h => h.update(chunk) >> h.hash)
      .unsafeRunSync()
}
