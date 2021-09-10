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
package io
package file

import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.internal.jsdeps.node.fsPromisesMod

import scala.scalajs.js.typedarray.Uint8Array

private[file] trait FileHandlePlatform[F[_]]

private[file] trait FileHandleCompanionPlatform {
  private[file] def make[F[_]](
      fd: fsPromisesMod.FileHandle
  )(implicit F: Async[F]): FileHandle[F] =
    new FileHandle[F] {

      override def force(metaData: Boolean): F[Unit] =
        F.fromPromise(F.delay(fd.datasync()))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] =
        F.fromPromise(
          F.delay(fd.read(new Uint8Array(numBytes), 0, numBytes.toDouble, offset.toDouble))
        ).map { res =>
          if (res.bytesRead < 0) None
          else if (res.bytesRead == 0) Some(Chunk.empty)
          else
            Some(Chunk.uint8Array(res.buffer).take(res.bytesRead.toInt))
        }

      override def size: F[Long] =
        F.fromPromise(F.delay(fd.stat())).map(_.size.toLong)

      override def truncate(size: Long): F[Unit] =
        F.fromPromise(F.delay(fd.truncate(size.toDouble)))

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.fromPromise(
          F.delay(fd.write(bytes.toUint8Array, 0, bytes.size.toDouble, offset.toDouble))
        ).map(_.bytesWritten.toInt)
    }
}
