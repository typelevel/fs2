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
import fs2.io.internal.facade

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

private[file] trait FileHandlePlatform[F[_]]

private[file] trait FileHandleCompanionPlatform {
  private[file] def make[F[_]](
      fd: facade.fs.FileHandle
  )(implicit F: Async[F]): FileHandle[F] =
    new FileHandle[F] {

      override def force(metaData: Boolean): F[Unit] =
        F.fromPromise(F.delay(fd.datasync()))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] =
        F.async_[(Int, Uint8Array)] { cb =>
          facade.fs.read(
            fd.fd,
            new Uint8Array(numBytes),
            0,
            numBytes,
            js.BigInt(offset.toString),
            (err, bytesRead, buffer) =>
              cb(Option(err).map(js.JavaScriptException(_)).toLeft((bytesRead, buffer)))
          )
        }.map { case (bytesRead, buffer) =>
          if (bytesRead == 0)
            if (numBytes > 0) None else Some(Chunk.empty)
          else
            Some(Chunk.uint8Array(buffer).take(bytesRead))
        }

      override def size: F[Long] =
        F.fromPromise(F.delay(fd.stat(new facade.fs.StatOptions { bigint = true })))
          .map(_.size.toString.toLong)

      override def truncate(size: Long): F[Unit] =
        F.fromPromise(F.delay(fd.truncate(size.toDouble)))

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.async_[Int] { cb =>
          facade.fs.write(
            fd.fd,
            bytes.toUint8Array,
            0,
            bytes.size,
            js.BigInt(offset.toString),
            (err, bytesWritten, _) =>
              cb(Option(err).map(js.JavaScriptException(_)).toLeft(bytesWritten))
          )
        }
    }
}
