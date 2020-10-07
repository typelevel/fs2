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

import cats.effect.{Resource, Sync}

/**
  * Provides the ability to read/write/lock/inspect a file in the effect `F`.
  */
trait FileHandle[F[_]] {

  /** Opaque type representing an exclusive lock on a file. */
  type Lock

  /**
    * Force any updates for the underlying file to storage.
    * @param metaData If true, also attempts to force file metadata updates to storage.
    */
  def force(metaData: Boolean): F[Unit]

  /**
    * Acquire an exclusive lock on the underlying file.
    * @return a lock object which can be used to unlock the file.
    */
  def lock: F[Lock]

  /**
    * Acquire a lock on the specified region of the underlying file.
    * @param position the start of the region to lock.
    * @param size the size of the region to lock.
    * @param shared to request a shared lock across process boundaries (may be converted to an exclusive lock on some operating systems).
    * @return a lock object which can be used to unlock the region.
    */
  def lock(position: Long, size: Long, shared: Boolean): F[Lock]

  /**
    * Read the specified number of bytes at a particular offset.
    * @param numBytes the number of bytes to read.
    * @param offset the offset from the start of the file.
    * @return a number of bytes from the file (at most, numBytes in size).
    */
  def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]]

  /**
    * Report the current size of the file.
    * @return the size of the file.
    */
  def size: F[Long]

  /**
    * Truncate the underlying file to the specified size.
    * @param size the size of the file after truncation.
    */
  def truncate(size: Long): F[Unit]

  /**
    * Attempt to acquire an exclusive lock on the underlying file.
    * @return if the lock could be acquired, a lock object which can be used to unlock the file.
    */
  def tryLock: F[Option[Lock]]

  /**
    * Attempt to acquire a lock on the specified region of the underlying file.
    * @param position the start of the region to lock.
    * @param size the size of the region to lock.
    * @param shared to request a shared lock across process boundaries (may be converted to an exclusive lock on some operating systems).
    * @return if the lock could be acquired, a lock object which can be used to unlock the region.
    */
  def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]]

  /**
    * Unlock the (exclusive or regional) lock represented by the supplied `Lock`.
    * @param lock the lock object which represents the locked file or region.
    */
  def unlock(lock: Lock): F[Unit]

  /**
    * Write the specified bytes at a particular offset.
    * @param bytes the bytes to write to the `FileHandle`.
    * @param offset the offset at which to write the bytes.
    * @return the number of bytes written.
    */
  def write(bytes: Chunk[Byte], offset: Long): F[Int]
}

object FileHandle {

  @deprecated("Use Files[F].open", "3.0.0")
  def fromPath[F[_]: Sync](path: Path, flags: Seq[OpenOption]): Resource[F, FileHandle[F]] =
    SyncFiles[F].open(path, flags)

  @deprecated("Use Files[F].openFileChannel", "3.0.0")
  def fromFileChannel[F[_]: Sync](channel: F[FileChannel]): Resource[F, FileHandle[F]] =
    SyncFiles[F].openFileChannel(channel)

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
          else Some(Chunk.bytes(buf.array, 0, len))
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
        F.blocking(chan.write(bytes.toBytes.toByteBuffer, offset))
    }
}
