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
import java.nio.channels.{FileChannel, FileLock, SeekableByteChannel}
import java.nio.file.{OpenOption, Path => JPath}

import cats.effect.kernel.{Async, Resource, Sync}

private[file] trait FileHandlePlatform[F[_]] {

  /** Opaque type representing an exclusive lock on a file. */
  type Lock

  /** Acquire an exclusive lock on the underlying file.
    * @return a lock object which can be used to unlock the file.
    */
  def lock: F[Lock]

  /** Acquire a lock on the specified region of the underlying file.
    * @param position the start of the region to lock.
    * @param size the size of the region to lock.
    * @param shared to request a shared lock across process boundaries (may be converted to an exclusive lock on some operating systems).
    * @return a lock object which can be used to unlock the region.
    */
  def lock(position: Long, size: Long, shared: Boolean): F[Lock]

  /** Attempt to acquire an exclusive lock on the underlying file.
    * @return if the lock could be acquired, a lock object which can be used to unlock the file.
    */
  def tryLock: F[Option[Lock]]

  /** Attempt to acquire a lock on the specified region of the underlying file.
    * @param position the start of the region to lock.
    * @param size the size of the region to lock.
    * @param shared to request a shared lock across process boundaries (may be converted to an exclusive lock on some operating systems).
    * @return if the lock could be acquired, a lock object which can be used to unlock the region.
    */
  def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]]

  /** Unlock the (exclusive or regional) lock represented by the supplied `Lock`.
    * @param lock the lock object which represents the locked file or region.
    */
  def unlock(lock: Lock): F[Unit]

}

private[file] trait FileHandleCompanionPlatform {
  @deprecated("Use Files[F].open", "3.0.0")
  def fromPath[F[_]: Async](path: JPath, flags: Seq[OpenOption]): Resource[F, FileHandle[F]] =
    Files.forAsync[F].open(path, flags)

  @deprecated("Use Files[F].openFileChannel", "3.0.0")
  def fromFileChannel[F[_]: Async](channel: F[FileChannel]): Resource[F, FileHandle[F]] =
    Files.forAsync[F].openFileChannel(channel)

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

  /** Creates a `FileHandle[F]` from a `java.nio.channels.SeekableByteChannel`. Because a `SeekableByteChannel` doesn't provide all the functionalities required by `FileHandle` some features like locking will be unavailable. */
  private[file] def makeFromSeekableByteChannel[F[_]](
      chan: SeekableByteChannel,
      unsupportedOperationException: => Throwable
  )(implicit F: Sync[F]): FileHandle[F] =
    new FileHandle[F] {
      type Lock = Unit

      override def force(metaData: Boolean): F[Unit] =
        F.raiseError(unsupportedOperationException)

      override def lock: F[Lock] =
        F.raiseError(unsupportedOperationException)

      override def lock(position: Long, size: Long, shared: Boolean): F[Lock] =
        F.raiseError(unsupportedOperationException)

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] =
        F.blocking {
          val buf = ByteBuffer.allocate(numBytes)
          val len = chan.synchronized {
            // don't set position on sequential operations because not all file systems support setting the position
            if (chan.position() != offset) { chan.position(offset); () }
            chan.read(buf)
          }
          if (len < 0) None
          else if (len == 0) Some(Chunk.empty)
          else Some(Chunk.array(buf.array, 0, len))
        }

      override def size: F[Long] =
        F.blocking(chan.size)

      override def truncate(size: Long): F[Unit] =
        F.blocking { chan.truncate(size); () }

      override def tryLock: F[Option[Lock]] =
        F.raiseError(unsupportedOperationException)

      override def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]] =
        F.raiseError(unsupportedOperationException)

      override def unlock(f: Lock): F[Unit] =
        F.unit

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.blocking {
          chan.synchronized {
            // don't set position on sequential operations because not all file systems support setting the position
            if (chan.position() != offset) { chan.position(offset); () }
            chan.write(bytes.toByteBuffer)
          }
        }
    }
}
