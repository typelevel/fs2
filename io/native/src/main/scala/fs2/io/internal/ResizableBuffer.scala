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

package fs2.io.internal

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._

import scala.scalanative.libc.errno._
import scala.scalanative.libc.stdlib._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[io] final class ResizableBuffer[F[_]] private (
    private var ptr: Ptr[Byte],
    private[this] var size: Int
)(implicit F: Sync[F]) {

  def get(size: Int): F[Ptr[Byte]] = F.delay {
    if (size <= this.size)
      F.pure(ptr)
    else {
      ptr = realloc(ptr, size.toUInt)
      this.size = size
      if (ptr == null)
        F.raiseError[Ptr[Byte]](new RuntimeException(s"realloc: ${errno}"))
      else F.pure(ptr)
    }
  }.flatten

}

private[io] object ResizableBuffer {

  def apply[F[_]](size: Int)(implicit F: Sync[F]): Resource[F, ResizableBuffer[F]] =
    Resource.make {
      F.delay {
        val ptr = malloc(size.toUInt)
        if (ptr == null)
          throw new RuntimeException(s"malloc: ${errno}")
        else new ResizableBuffer(ptr, size)
      }
    }(buf => F.delay(free(buf.ptr)))

}
