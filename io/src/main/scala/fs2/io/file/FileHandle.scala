package fs2.io.file

import fs2.Chunk

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
