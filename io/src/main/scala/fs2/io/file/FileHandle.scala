package fs2
package io
package file

import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock}

import cats.effect.{Blocker, ContextShift, Sync}

/**
  * Provides the ability to read/write/lock/inspect a file in the effect `F`.
  *
  * To construct a `FileHandle`, use the methods in the [[fs2.io.file.pulls]] object.
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

private[file] object FileHandle {

  /**
    * Creates a `FileHandle[F]` from a `java.nio.channels.FileChannel`.
    */
  private[file] def fromFileChannel[F[_]](
      chan: FileChannel,
      blocker: Blocker
  )(implicit F: Sync[F], cs: ContextShift[F]): FileHandle[F] =
    new FileHandle[F] {
      type Lock = FileLock

      override def force(metaData: Boolean): F[Unit] =
        blocker.delay(chan.force(metaData))

      override def lock: F[Lock] =
        blocker.delay(chan.lock)

      override def lock(position: Long, size: Long, shared: Boolean): F[Lock] =
        blocker.delay(chan.lock(position, size, shared))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] =
        blocker.delay {
          val buf = ByteBuffer.allocate(numBytes)
          val len = chan.read(buf, offset)
          if (len < 0) None
          else if (len == 0) Some(Chunk.empty)
          else Some(Chunk.bytes(buf.array, 0, len))
        }

      override def size: F[Long] =
        blocker.delay(chan.size)

      override def truncate(size: Long): F[Unit] =
        blocker.delay { chan.truncate(size); () }

      override def tryLock: F[Option[Lock]] =
        blocker.delay(Option(chan.tryLock()))

      override def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]] =
        blocker.delay(Option(chan.tryLock(position, size, shared)))

      override def unlock(f: Lock): F[Unit] =
        blocker.delay(f.release())

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        blocker.delay(chan.write(bytes.toBytes.toByteBuffer, offset))
    }
}
