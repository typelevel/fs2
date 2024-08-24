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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private[hashing] trait HashCompanionPlatform {

  private[hashing] def apply[F[_]: Sync](algorithm: HashAlgorithm): Resource[F, Hash[F]] =
    Resource.eval(Sync[F].delay(unsafe(algorithm)))

  private[hashing] def hmac[F[_]: Sync](
      algorithm: HashAlgorithm,
      key: Chunk[Byte]
  ): Resource[F, Hash[F]] =
    Resource.eval(Sync[F].delay(unsafeHmac(algorithm, key)))

  private[hashing] def unsafe[F[_]: Sync](algorithm: HashAlgorithm): Hash[F] =
    unsafeFromMessageDigest(MessageDigest.getInstance(toAlgorithmString(algorithm)))

  private[hashing] def toAlgorithmString(algorithm: HashAlgorithm): String =
    algorithm match {
      case HashAlgorithm.MD5         => "MD5"
      case HashAlgorithm.SHA1        => "SHA-1"
      case HashAlgorithm.SHA224      => "SHA-224"
      case HashAlgorithm.SHA256      => "SHA-256"
      case HashAlgorithm.SHA384      => "SHA-384"
      case HashAlgorithm.SHA512      => "SHA-512"
      case HashAlgorithm.SHA512_224  => "SHA-512/224"
      case HashAlgorithm.SHA512_256  => "SHA-512/256"
      case HashAlgorithm.SHA3_224    => "SHA3-224"
      case HashAlgorithm.SHA3_256    => "SHA3-256"
      case HashAlgorithm.SHA3_384    => "SHA3-384"
      case HashAlgorithm.SHA3_512    => "SHA3-512"
      case HashAlgorithm.Named(name) => name
    }

  private[hashing] def unsafeHmac[F[_]: Sync](
      algorithm: HashAlgorithm,
      key: Chunk[Byte]
  ): Hash[F] = {
    val name = toMacAlgorithmString(algorithm)
    val mac = Mac.getInstance(name)
    mac.init(new SecretKeySpec(key.toArray, name))
    unsafeFromMac(mac)
  }

  private[hashing] def toMacAlgorithmString(algorithm: HashAlgorithm): String =
    algorithm match {
      case HashAlgorithm.MD5         => "HmacMD5"
      case HashAlgorithm.SHA1        => "HmacSHA1"
      case HashAlgorithm.SHA224      => "HmacSHA224"
      case HashAlgorithm.SHA256      => "HmacSHA256"
      case HashAlgorithm.SHA384      => "HmacSHA384"
      case HashAlgorithm.SHA512      => "HmacSHA512"
      case HashAlgorithm.SHA512_224  => "HmacSHA512/224"
      case HashAlgorithm.SHA512_256  => "HmacSHA512/256"
      case HashAlgorithm.SHA3_224    => "HmacSHA3-224"
      case HashAlgorithm.SHA3_256    => "HmacSHA3-256"
      case HashAlgorithm.SHA3_384    => "HmacSHA3-384"
      case HashAlgorithm.SHA3_512    => "HmacSHA3-512"
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

  def unsafeFromMac[F[_]: Sync](d: Mac): Hash[F] =
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
        Digest(Chunk.array(d.doFinal()))
    }
}
