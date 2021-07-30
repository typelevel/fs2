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
package file2

import cats.effect.Resource

/** Platform-agnostic methods for reading files.
  */
sealed trait Files[F[_]] {

  def readAll(path: Path): Stream[F, Byte] = readAll(path, 64 * 1024)

  def readAll(path: Path, chunkSize: Int): Stream[F, Byte]

  /** Returns a `ReadCursor` for the specified path.
    */
  def readCursor(path: Path): Resource[F, ReadCursor[F]]

//   /** Reads a range of data synchronously from the file at the specified path.
//     * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
//     * two bytes are read.
//     */
//   def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte]

//   /** Returns an infinite stream of data from the file at the specified path.
//     * Starts reading from the specified offset and upon reaching the end of the file,
//     * polls every `pollDuration` for additional updates to the file.
//     *
//     * Read operations are limited to emitting chunks of the specified chunk size
//     * but smaller chunks may occur.
//     *
//     * If an error occurs while reading from the file, the overall stream fails.
//     */
//   def tail(
//       path: Path,
//       chunkSize: Int,
//       offset: Long = 0L,
//       pollDelay: FiniteDuration
//   ): Stream[F, Byte]

  /** Writes all data to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAll(
      path: Path
  ): Pipe[F, Byte, INothing] = writeAll(path, None)

  def writeAll(
      path: Path,
      flags: Option[Flags]
  ): Pipe[F, Byte, INothing]

  /** Returns a `WriteCursor` for the specified path.
    *
    * The `WRITE` option is added to the supplied flags. If the `APPEND` option is present in `flags`,
    * the offset is initialized to the current size of the file.
    */
  def writeCursor(
      path: Path,
      flags: Option[Flags]
  ): Resource[F, WriteCursor[F]]

  // /** Returns a `WriteCursor` for the specified file handle.
  //   *
  //   * If `append` is true, the offset is initialized to the current size of the file.
  //   */
  // def writeCursorFromFileHandle(
  //     file: FileHandle[F],
  //     append: Boolean
  // ): F[WriteCursor[F]]

  // /** Writes all data to a sequence of files, each limited in size to `limit`.
  //   *
  //   * The `computePath` operation is used to compute the path of the first file
  //   * and every subsequent file. Typically, the next file should be determined
  //   * by analyzing the current state of the filesystem -- e.g., by looking at all
  //   * files in a directory and generating a unique name.
  //   */
  // def writeRotate(
  //     computePath: F[Path],
  //     limit: Long,
  //     flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  // ): Pipe[F, Byte, INothing]
}

object Files extends FilesPlatform {
  private[file2] trait UnsealedFiles[F[_]] extends Files[F]

  def apply[F[_]](implicit F: Files[F]): Files[F] = F

}
