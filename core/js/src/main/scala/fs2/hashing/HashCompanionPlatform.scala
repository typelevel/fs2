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

  def apply[F[_]: Sync](algorithm: String): Resource[F, Hash[F]] =
    Resource.eval(Sync[F].delay(unsafe(algorithm)))

  def unsafe[F[_]: Sync](algorithm: String): Hash[F] =
    unsafeFromHash(JsHash.createHash(algorithm))

  private def unsafeFromHash[F[_]: Sync](h: JsHash.Hash): Hash[F] =
    new Hash[F] {
      def addChunk(bytes: Chunk[Byte]): F[Unit] = Sync[F].delay(unsafeAddChunk(bytes.toArraySlice))
      def computeAndReset: F[Chunk[Byte]] = Sync[F].delay(unsafeComputeAndReset())

      def unsafeAddChunk(slice: Chunk.ArraySlice[Byte]): Unit =
        h.update(slice.toUint8Array)

      def unsafeComputeAndReset(): Chunk[Byte] = Chunk.uint8Array(h.digest())
    }
}

private[fs2] object JsHash {

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
