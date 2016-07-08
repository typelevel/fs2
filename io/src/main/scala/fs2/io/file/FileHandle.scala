package fs2
package io
package file

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, FileChannel, FileLock}

import fs2.util.{Async,Effect}
import fs2.util.syntax._

trait FileHandle[F[_]] {
  type Lock

  /**
    * Close the `FileHandle`.
    */
  def close(): F[Unit]

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
  /**
    * Creates a `FileHandle[F]` from a `java.nio.channels.AsynchronousFileChannel`.
    *
    * Uses a `java.nio.Channels.CompletionHandler` to handle callbacks from IO operations.
    */
  private[fs2] def fromAsynchronousFileChannel[F[_]](chan: AsynchronousFileChannel)(implicit F: Async[F]): FileHandle[F] = {
    new FileHandle[F] {
      type Lock = FileLock

      override def close(): F[Unit] =
        F.delay(chan.close())

      override def force(metaData: Boolean): F[Unit] =
        F.delay(chan.force(metaData))

      override def lock: F[Lock] =
        asyncCompletionHandler[F, Lock](f => F.pure(chan.lock(null, f)))

      override def lock(position: Long, size: Long, shared: Boolean): F[Lock] =
        asyncCompletionHandler[F, Lock](f => F.pure(chan.lock(position, size, shared, null, f)))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] = {
        val buf = ByteBuffer.allocate(numBytes)
        asyncCompletionHandler[F, Integer](f => F.pure(chan.read(buf, offset, null, f))).map { len =>
          if (len < 0) None else if (len == 0) Some(Chunk.empty) else Some(Chunk.bytes(buf.array.take(len)))
        }
      }

      override def size: F[Long] =
        F.delay(chan.size)

      override def truncate(size: Long): F[Unit] =
        F.delay { chan.truncate(size); () }

      override def tryLock: F[Option[Lock]] =
        F.map(F.delay(chan.tryLock()))(Option.apply)

      override def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]] =
        F.map(F.delay(chan.tryLock(position, size, shared)))(Option.apply)

      override def unlock(f: Lock): F[Unit] =
        F.delay(f.release())

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.map(
          asyncCompletionHandler[F, Integer](f => F.pure(chan.write(ByteBuffer.wrap(bytes.toArray), offset, null, f))))(
          i => i.toInt
        )
    }
  }

  /**
    * Creates a `FileHandle[F]` from a `java.nio.channels.FileChannel`.
    */
  private[fs2] def fromFileChannel[F[_]](chan: FileChannel)(implicit F: Effect[F]): FileHandle[F] = {
    new FileHandle[F] {
      type Lock = FileLock

      override def close(): F[Unit] =
        F.delay(chan.close())

      override def force(metaData: Boolean): F[Unit] =
        F.delay(chan.force(metaData))

      override def lock: F[Lock] =
        F.delay(chan.lock)

      override def lock(position: Long, size: Long, shared: Boolean): F[Lock] =
        F.delay(chan.lock(position, size, shared))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] = {
        val buf = ByteBuffer.allocate(numBytes)
        F.delay(chan.read(buf, offset)).map { len =>
          if (len < 0) None else if (len == 0) Some(Chunk.empty) else Some(Chunk.bytes(buf.array.take(len)))
        }
      }

      override def size: F[Long] =
        F.delay(chan.size)

      override def truncate(size: Long): F[Unit] =
        F.delay { chan.truncate(size); () }

      override def tryLock: F[Option[Lock]] =
        F.delay(Option(chan.tryLock()))

      override def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]] =
        F.delay(Option(chan.tryLock(position, size, shared)))

      override def unlock(f: Lock): F[Unit] =
        F.delay(f.release())

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.delay(chan.write(ByteBuffer.wrap(bytes.toArray), offset))
    }
  }
}
