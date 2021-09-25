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

/** Provides the ability to read/write/lock/inspect a file in the effect `F`.
  */
trait FileHandle[F[_]] extends FileHandlePlatform[F] {

  /** Force any updates for the underlying file to storage.
    * @param metaData
    *   If true, also attempts to force file metadata updates to storage.
    */
  def force(metaData: Boolean): F[Unit]

  /** Read the specified number of bytes at a particular offset.
    * @param numBytes
    *   the number of bytes to read.
    * @param offset
    *   the offset from the start of the file.
    * @return
    *   a number of bytes from the file (at most, numBytes in size).
    */
  def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]]

  /** Report the current size of the file.
    * @return
    *   the size of the file.
    */
  def size: F[Long]

  /** Truncate the underlying file to the specified size.
    * @param size
    *   the size of the file after truncation.
    */
  def truncate(size: Long): F[Unit]

  /** Write the specified bytes at a particular offset.
    * @param bytes
    *   the bytes to write to the `FileHandle`.
    * @param offset
    *   the offset at which to write the bytes.
    * @return
    *   the number of bytes written.
    */
  def write(bytes: Chunk[Byte], offset: Long): F[Int]
}

object FileHandle extends FileHandleCompanionPlatform
