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

trait HashCompanionPlatform {

  private[fs2] def apply[F[_]: Sync](algorithm: HashAlgorithm): Resource[F, Hash[F]] =
    Resource.eval(Sync[F].delay(unsafe(algorithm)))

  private[fs2] def unsafe[F[_]: Sync](algorithm: HashAlgorithm): Hash[F] =
    new Hash[F] {
      private def newHash() = JsHash.createHash(toAlgorithmString(algorithm))
      private var h = newHash()

      def addChunk(bytes: Chunk[Byte]): F[Unit] =
        Sync[F].delay(unsafeAddChunk(bytes))

      def computeAndReset: F[Chunk[Byte]] =
        Sync[F].delay(unsafeComputeAndReset())

      def unsafeAddChunk(chunk: Chunk[Byte]): Unit =
        h.update(chunk.toUint8Array)

      def unsafeComputeAndReset(): Chunk[Byte] = {
        val result = Chunk.uint8Array(h.digest())
        h = newHash()
        result
      }
    }

  private def toAlgorithmString(algorithm: HashAlgorithm): String =
    algorithm match {
      case HashAlgorithm.MD5         => "MD5"
      case HashAlgorithm.SHA1        => "SHA1"
      case HashAlgorithm.SHA256      => "SHA256"
      case HashAlgorithm.SHA384      => "SHA384"
      case HashAlgorithm.SHA512      => "SHA512"
      case HashAlgorithm.Named(name) => name
    }
}

private[hashing] object JsHash {

  @js.native
  @JSImport("crypto", "createHash")
  @nowarn212("cat=unused")
  private[fs2] def createHash(algorithm: String): Hash = js.native

  @js.native
  @nowarn212("cat=unused")
  private[fs2] trait Hash extends js.Object {
    def update(data: Uint8Array): Unit = js.native
    def digest(): Uint8Array = js.native
  }
}
