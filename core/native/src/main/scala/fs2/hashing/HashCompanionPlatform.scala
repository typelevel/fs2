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

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

trait HashCompanionPlatform {
  import openssl._

  def apply[F[_]](algorithm: String)(implicit F: Sync[F]): Resource[F, Hash[F]] = {
    val zoneResource = Resource.make(F.delay(Zone.open()))(z => F.delay(z.close()))
    zoneResource.flatMap { zone =>
      val acquire = F.delay {
        val ctx = EVP_MD_CTX_new()
        if (ctx == null)
          throw new RuntimeException(s"EVP_MD_CTX_new: ${getOpensslError()}")
        ctx
      }
      Resource
        .make(acquire)(ctx => F.delay(EVP_MD_CTX_free(ctx)))
        .evalMap { ctx =>
          F.delay {
            val `type` = EVP_get_digestbyname(toCString(algorithm)(zone))
            if (`type` == null)
              throw new RuntimeException(s"EVP_get_digestbyname: ${getOpensslError()}")
            val init = () =>
              if (EVP_DigestInit_ex(ctx, `type`, null) != 1)
                throw new RuntimeException(s"EVP_DigestInit_ex: ${getOpensslError()}")
            init()
            (ctx, init)
          }
        }
        .map { case (ctx, init) =>
          new Hash[F] {
            def addChunk(bytes: Chunk[Byte]): F[Unit] =
              F.delay(unsafeAddChunk(bytes.toArraySlice))

            def computeAndReset: F[Chunk[Byte]] =
              F.delay(unsafeComputeAndReset())

            def unsafeAddChunk(slice: Chunk.ArraySlice[Byte]): Unit =
              if (
                EVP_DigestUpdate(ctx, slice.values.atUnsafe(slice.offset), slice.size.toULong) != 1
              )
                throw new RuntimeException(s"EVP_DigestUpdate: ${getOpensslError()}")

            def unsafeComputeAndReset(): Chunk[Byte] = {
              val md = new Array[Byte](EVP_MAX_MD_SIZE)
              val size = stackalloc[CUnsignedInt]()
              if (EVP_DigestFinal_ex(ctx, md.atUnsafe(0), size) != 1)
                throw new RuntimeException(s"EVP_DigestFinal_ex: ${getOpensslError()}")
              val result = Chunk.ArraySlice(md, 0, (!size).toInt)
              init()
              result
            }
          }
        }
    }
  }

  private[this] def getOpensslError(): String =
    fromCString(ERR_reason_error_string(ERR_get_error()))
}

@link("crypto")
@extern
@nowarn212("cat=unused")
private[fs2] object openssl {

  final val EVP_MAX_MD_SIZE = 64

  type EVP_MD
  type EVP_MD_CTX
  type ENGINE

  def ERR_get_error(): ULong = extern
  def ERR_reason_error_string(e: ULong): CString = extern

  def EVP_get_digestbyname(name: Ptr[CChar]): Ptr[EVP_MD] = extern

  def EVP_MD_CTX_new(): Ptr[EVP_MD_CTX] = extern
  def EVP_MD_CTX_free(ctx: Ptr[EVP_MD_CTX]): Unit = extern

  def EVP_DigestInit_ex(ctx: Ptr[EVP_MD_CTX], `type`: Ptr[EVP_MD], impl: Ptr[ENGINE]): CInt =
    extern
  def EVP_DigestUpdate(ctx: Ptr[EVP_MD_CTX], d: Ptr[Byte], cnt: CSize): CInt = extern
  def EVP_DigestFinal_ex(ctx: Ptr[EVP_MD_CTX], md: Ptr[Byte], s: Ptr[CUnsignedInt]): CInt = extern
  def EVP_Digest(
      data: Ptr[Byte],
      count: CSize,
      md: Ptr[Byte],
      size: Ptr[CUnsignedInt],
      `type`: Ptr[EVP_MD],
      impl: Ptr[ENGINE]
  ): CInt = extern
}
