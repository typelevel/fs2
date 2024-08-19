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

import cats.effect.{Resource, Sync}

import java.security.MessageDigest

private[hashing] trait HashCompanionPlatform {

  private[hashing] def apply[F[_]: Sync](algorithm: HashAlgorithm): Resource[F, Hash[F]] =
    Resource.eval(Sync[F].delay(unsafe(algorithm)))

  private[hashing] def unsafe[F[_]: Sync](algorithm: HashAlgorithm): Hash[F] =
    unsafeFromMessageDigest(MessageDigest.getInstance(toAlgorithmString(algorithm)))

  private def toAlgorithmString(algorithm: HashAlgorithm): String =
    algorithm match {
      case HashAlgorithm.MD5         => "MD5"
      case HashAlgorithm.SHA1        => "SHA-1"
      case HashAlgorithm.SHA256      => "SHA-256"
      case HashAlgorithm.SHA384      => "SHA-384"
      case HashAlgorithm.SHA512      => "SHA-512"
      case HashAlgorithm.Named(name) => name
    }

  def unsafeFromMessageDigest[F[_]: Sync](d: MessageDigest): Hash[F] =
    new Hash[F] {
      def update(bytes: Chunk[Byte]): F[Unit] =
        Sync[F].delay(unsafeUpdate(bytes))

      def digest: F[Digest] =
        Sync[F].delay(unsafeDigest())

      def unsafeUpdate(chunk: Chunk[Byte]): Unit = {
        val slice = chunk.toArraySlice
        d.update(slice.values, slice.offset, slice.size)
      }

      def unsafeDigest(): Digest =
        Digest(Chunk.array(d.digest()))
    }
}
