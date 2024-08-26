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

import org.typelevel.scalaccompat.annotation._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

trait HasherCompanionPlatform {

  private[fs2] def apply[F[_]: Sync](algorithm: HashAlgorithm): Resource[F, Hasher[F]] =
    Resource.eval(Sync[F].delay(unsafe(algorithm)))

  private[hashing] def hmac[F[_]: Sync](
      algorithm: HashAlgorithm,
      key: Chunk[Byte]
  ): Resource[F, Hasher[F]] =
    Resource.eval(Sync[F].delay(unsafeHmac(algorithm, key)))

  private[fs2] def unsafe[F[_]: Sync](algorithm: HashAlgorithm): Hasher[F] =
    new SyncHasher[F] {
      private def newHash() = JsHash.createHash(toAlgorithmString(algorithm))
      private var h = newHash()

      def unsafeUpdate(chunk: Chunk[Byte]): Unit =
        h.update(chunk.toUint8Array)

      def unsafeHash(): Hash = {
        val result = Hash(Chunk.uint8Array(h.digest()))
        h = newHash()
        result
      }
    }

  private[hashing] def toAlgorithmString(algorithm: HashAlgorithm): String =
    algorithm match {
      case HashAlgorithm.MD5         => "MD5"
      case HashAlgorithm.SHA1        => "SHA1"
      case HashAlgorithm.SHA224      => "SHA224"
      case HashAlgorithm.SHA256      => "SHA256"
      case HashAlgorithm.SHA384      => "SHA384"
      case HashAlgorithm.SHA512      => "SHA512"
      case HashAlgorithm.SHA512_224  => "SHA512-224"
      case HashAlgorithm.SHA512_256  => "SHA512-256"
      case HashAlgorithm.SHA3_224    => "SHA3-224"
      case HashAlgorithm.SHA3_256    => "SHA3-256"
      case HashAlgorithm.SHA3_384    => "SHA3-384"
      case HashAlgorithm.SHA3_512    => "SHA3-512"
      case HashAlgorithm.Named(name) => name
      case other                     => sys.error(s"unsupported algorithm $other")
    }

  private[fs2] def unsafeHmac[F[_]: Sync](algorithm: HashAlgorithm, key: Chunk[Byte]): Hasher[F] =
    new SyncHasher[F] {
      private def newHash() = JsHash.createHmac(toAlgorithmString(algorithm), key.toUint8Array)
      private var h = newHash()

      def unsafeUpdate(chunk: Chunk[Byte]): Unit =
        h.update(chunk.toUint8Array)

      def unsafeHash(): Hash = {
        val result = Hash(Chunk.uint8Array(h.digest()))
        h = newHash()
        result
      }
    }
}

private[hashing] object JsHash {

  @js.native
  @JSImport("crypto", "createHash")
  @nowarn212("cat=unused")
  private[fs2] def createHash(algorithm: String): Hash = js.native

  @js.native
  @JSImport("crypto", "createHmac")
  @nowarn212("cat=unused")
  private[fs2] def createHmac(algorithm: String, key: Uint8Array): Hash = js.native

  @js.native
  @nowarn212("cat=unused")
  private[fs2] trait Hash extends js.Object {
    def update(data: Uint8Array): Unit = js.native
    def digest(): Uint8Array = js.native
  }
}
