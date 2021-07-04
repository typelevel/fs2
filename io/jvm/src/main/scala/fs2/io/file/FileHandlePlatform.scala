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

import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{OpenOption, Path}

import cats.effect.kernel.{Async, Resource, Sync}

private[file] trait FileHandleCompanionPlatform {
  @deprecated("Use Files[F].open", "3.0.0")
  def fromPath[F[_]: Async](path: Path, flags: Seq[OpenOption]): Resource[F, FileHandle[F]] =
    Files[F].open(path, flags)

  @deprecated("Use Files[F].openFileChannel", "3.0.0")
  def fromFileChannel[F[_]: Async](channel: F[FileChannel]): Resource[F, FileHandle[F]] =
    Files[F].openFileChannel(channel)

  /** Creates a `FileHandle[F]` from a `java.nio.channels.FileChannel`. */
  private[file] def make[F[_]](
      chan: FileChannel
  )(implicit F: Sync[F]): FileHandle[F] =
    new FileHandle[F] {
      type Lock = FileLock

      override def force(metaData: Boolean): F[Unit] =
        F.blocking(chan.force(metaData))

      override def lock: F[Lock] =
        F.blocking(chan.lock)

      override def lock(position: Long, size: Long, shared: Boolean): F[Lock] =
        F.blocking(chan.lock(position, size, shared))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] =
        F.blocking {
          val buf = ByteBuffer.allocate(numBytes)
          val len = chan.read(buf, offset)
          if (len < 0) None
          else if (len == 0) Some(Chunk.empty)
          else Some(Chunk.array(buf.array, 0, len))
        }

      override def size: F[Long] =
        F.blocking(chan.size)

      override def truncate(size: Long): F[Unit] =
        F.blocking { chan.truncate(size); () }

      override def tryLock: F[Option[Lock]] =
        F.blocking(Option(chan.tryLock()))

      override def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]] =
        F.blocking(Option(chan.tryLock(position, size, shared)))

      override def unlock(f: Lock): F[Unit] =
        F.blocking(f.release())

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.blocking(chan.write(bytes.toByteBuffer, offset))
    }
}
