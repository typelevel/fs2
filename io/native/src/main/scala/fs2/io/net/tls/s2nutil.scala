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

package fs2.io.net.tls

import cats.effect.SyncIO
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import scodec.bits.ByteVector

import java.util.Collections
import java.util.IdentityHashMap
import scala.scalanative.annotation.alwaysinline
import scala.scalanative.runtime
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.unsafe._

import s2n._

private[tls] object s2nutil {
  @alwaysinline def guard_(thunk: => CInt): Unit =
    if (thunk != S2N_SUCCESS) {
      val error = !s2n_errno_location()
      val errorType = s2n_error_get_type(error)
      if (errorType != S2N_ERR_T_BLOCKED)
        throw new S2nException(error)
    }

  @alwaysinline def guard(thunk: => CInt): CInt = {
    val rtn = thunk
    if (rtn < 0) {
      val error = !s2n_errno_location()
      val errorType = s2n_error_get_type(error)
      if (errorType != S2N_ERR_T_BLOCKED)
        throw new S2nException(error)
      else rtn
    } else rtn
  }

  @alwaysinline def guard[A](thunk: => Ptr[A]): Ptr[A] = {
    val rtn = thunk
    if (rtn == null)
      throw new S2nException(!s2n_errno_location())
    else rtn
  }

  @alwaysinline def toPtr(a: AnyRef): Ptr[Byte] =
    runtime.fromRawPtr(Intrinsics.castObjectToRawPtr(a))

  @alwaysinline def fromPtr[A](ptr: Ptr[Byte]): A =
    Intrinsics.castRawPtrToObject(runtime.toRawPtr(ptr)).asInstanceOf[A]

  @alwaysinline def toCStringArray(str: String): Array[Byte] =
    (str + 0.toChar).getBytes()

  def mkGcRoot[F[_]](implicit F: Sync[F]) = Resource.make(
    F.delay(Collections.newSetFromMap[Any](new IdentityHashMap))
  )(gcr => F.delay(gcr.clear()))

  def s2nVerifyHostFn(hostName: Ptr[CChar], hostNameLen: CSize, data: Ptr[Byte]): Byte = {
    val cb = fromPtr[String => SyncIO[Boolean]](data)
    val hn = ByteVector.fromPtr(hostName, hostNameLen.toLong).decodeAsciiLenient
    val trust = cb(hn).unsafeRunSync()
    if (trust) 1 else 0
  }
}
