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

import scala.concurrent.duration._

import cats.effect.kernel.Resource
import cats.syntax.all._

/** Provides operations related to working with files in the effect `F`.
  *
  * An instance is available for any effect `F` which has an `Async[F]` instance.
  */
trait Files[F[_]] extends FilesPlatform[F] {

  import Files._

  /** Copies a file from the source to the target path,
    *
    * By default, the copy fails if the target file already exists or is a symbolic link.
    */
  def copy(source: Path, target: Path, flags: Seq[CopyOption] = Seq.empty): F[Path]

  /** Creates a new directory at the given path.
    */
  def createDirectory(path: Path, flags: Seq[FileAttribute[_]] = Seq.empty): F[Path]

  /** Creates a new directory at the given path and creates all nonexistent parent directories beforehand.
    */
  def createDirectories(path: Path, flags: Seq[FileAttribute[_]] = Seq.empty): F[Path]

  /** Deletes a file.
    *
    * If the file is a directory then the directory must be empty for this action to succeed.
    * This action will fail if the path doesn't exist.
    */
  def delete(path: Path): F[Unit]

  /** Like `delete`, but will not fail when the path doesn't exist.
    */
  def deleteIfExists(path: Path): F[Boolean]

  /** Recursively delete a directory
    */
  def deleteDirectoryRecursively(path: Path, options: Set[FileVisitOption] = Set.empty): F[Unit]

  /** Creates a stream of [[Path]]s inside a directory.
    */
  def directoryStream(path: Path): Stream[F, Path]

  /** Creates a stream of [[Path]]s inside a directory, filtering the results by the given predicate.
    */
  def directoryStream(path: Path, filter: Path => Boolean): Stream[F, Path]

  /** Creates a stream of [[Path]]s inside a directory which match the given glob.
    */
  def directoryStream(path: Path, glob: String): Stream[F, Path]

  /** Checks if a file exists.
    *
    * Note that the result of this method is immediately outdated. If this
    * method indicates the file exists then there is no guarantee that a
    * subsequence access will succeed. Care should be taken when using this
    * method in security sensitive applications.
    */
  def exists(path: Path, flags: Seq[LinkOption] = Seq.empty): F[Boolean]

  /** Tests whether a file is a directory.
    *
    * The options sequence may be used to indicate how symbolic links are handled for the case that the file is a symbolic link.
    * By default, symbolic links are followed and the file attribute of the final target of the link is read.
    * If the option NOFOLLOW_LINKS is present then symbolic links are not followed.
    *
    * Where is it required to distinguish an I/O exception from the case that the
    * file is not a directory then the file attributes can be read with the
    * readAttributes method and the file type tested with the BasicFileAttributes.isDirectory() method.
    *
    * @param path the path to the file to test
    * @param options - options indicating how symbolic links are handled
    * @return true if the file is a directory; false if the file does not exist, is not a directory, or it cannot be determined if the file is a directory or not.
    */
  def isDirectory(
      path: Path,
      linkOption: Seq[LinkOption] = Nil
  ): F[Boolean]

  /** Tests whether a file is a regular file with opaque content.
    *
    * The options sequence may be used to indicate how symbolic links are handled for the case that
    * the file is a symbolic link. By default, symbolic links are followed and the file
    * attribute of the final target of the link is read. If the option NOFOLLOW_LINKS is present
    * then symbolic links are not followed.
    *
    * Where is it required to distinguish an I/O exception from the case that the file is
    * not a regular file then the file attributes can be read with the readAttributes
    * method and the file type tested with the BasicFileAttributes.isRegularFile() method.
    *
    * @param path the path to the file
    * @param options options indicating how symbolic links are handled
    * @return true if the file is a regular file; false if the file does not exist, is not a regular file, or it cannot be determined if the file is a regular file or not.
    */
  def isFile(
      path: Path,
      linkOption: Seq[LinkOption] = Nil
  ): F[Boolean]

  /** Moves (or renames) a file from the source to the target path.
    *
    * By default, the move fails if the target file already exists or is a symbolic link.
    */
  def move(source: Path, target: Path, flags: Seq[CopyOption] = Seq.empty): F[Path]

  /** Creates a `FileHandle` for the file at the supplied `Path`. */
  def open(path: Path, flags: Seq[OpenOption]): Resource[F, FileHandle[F]]

  /** Get file permissions as set of [[PosixFilePermission]].
    *
    * Note: this will only work for POSIX supporting file systems.
    */
  def permissions(path: Path, flags: Seq[LinkOption] = Seq.empty): F[Set[PosixFilePermission]]

  /** Reads all data from the file at the specified `java.nio.file.Path`.
    */
  def readAll(path: Path, chunkSize: Int): Stream[F, Byte]

  /** Returns a `ReadCursor` for the specified path. The `READ` option is added to the supplied flags.
    */
  def readCursor(path: Path, flags: Seq[OpenOption] = Nil): Resource[F, ReadCursor[F]]

  /** Reads a range of data synchronously from the file at the specified `java.nio.file.Path`.
    * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
    * two bytes are read.
    */
  def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte]

  /** Set file permissions from set of [[PosixFilePermission]].
    *
    * Note: this will only work for POSIX supporting file systems.
    */
  def setPermissions(path: Path, permissions: Set[PosixFilePermission]): F[Path]

  /** Returns the size of a file (in bytes).
    */
  def size(path: Path): F[Long]

  /** Returns an infinite stream of data from the file at the specified path.
    * Starts reading from the specified offset and upon reaching the end of the file,
    * polls every `pollDuration` for additional updates to the file.
    *
    * Read operations are limited to emitting chunks of the specified chunk size
    * but smaller chunks may occur.
    *
    * If an error occurs while reading from the file, the overall stream fails.
    */
  def tail(
      path: Path,
      chunkSize: Int,
      offset: Long = 0L,
      pollDelay: FiniteDuration = 1.second
  ): Stream[F, Byte]

  /** Creates a [[Resource]] which can be used to create a temporary file.
    *  The file is created during resource allocation, and removed during its release.
    *
    * @param dir the directory which the temporary file will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the file's name
    * @param suffix the suffix string to be used in generating the file's name
    * @param attributes an optional list of file attributes to set atomically when creating the file
    * @return a resource containing the path of the temporary file
    */
  def tempFile(
      dir: Option[Path] = None,
      prefix: String = "",
      suffix: String = ".tmp",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, Path]

  /** Creates a [[Resource]] which can be used to create a temporary directory.
    *  The directory is created during resource allocation, and removed during its release.
    *
    * @param dir the directory which the temporary directory will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the directory's name
    * @param attributes an optional list of file attributes to set atomically when creating the directory
    * @return a resource containing the path of the temporary directory
    */
  def tempDirectory(
      dir: Option[Path] = None,
      prefix: String = "",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, Path]

  /** Creates a stream of [[Path]]s contained in a given file tree. Depth is unlimited.
    */
  def walk(start: Path): Stream[F, Path]

  /** Creates a stream of [[Path]]s contained in a given file tree, respecting the supplied options. Depth is unlimited.
    */
  def walk(start: Path, options: Seq[FileVisitOption]): Stream[F, Path]

  /** Creates a stream of [[Path]]s contained in a given file tree down to a given depth.
    */
  def walk(start: Path, maxDepth: Int, options: Seq[FileVisitOption] = Seq.empty): Stream[F, Path]

  /** Creates a [[Watcher]] for the default file system.
    *
    * The watcher is returned as a resource. To use the watcher, lift the resource to a stream,
    * watch or register 1 or more paths, and then return `watcher.events()`.
    */
  def watcher: Resource[F, Watcher[F]]

  /** Watches a single path.
    *
    * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
    */
  def watch(
      path: Path,
      types: Seq[Watcher.EventType] = Nil,
      modifiers: Seq[WatchEventModifier] = Nil,
      pollTimeout: FiniteDuration = 1.second
  ): Stream[F, Watcher.Event]

  /** Writes all data to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAll(
      path: Path,
      flags: Seq[StandardOpenOption] = List(StandardOpenOptionCreate)
  ): Pipe[F, Byte, INothing]

  /** Returns a `WriteCursor` for the specified path.
    *
    * The `WRITE` option is added to the supplied flags. If the `APPEND` option is present in `flags`,
    * the offset is initialized to the current size of the file.
    */
  def writeCursor(
      path: Path,
      flags: Seq[OpenOption] = List(StandardOpenOptionCreate)
  ): Resource[F, WriteCursor[F]]

  /** Returns a `WriteCursor` for the specified file handle.
    *
    * If `append` is true, the offset is initialized to the current size of the file.
    */
  def writeCursorFromFileHandle(
      file: FileHandle[F],
      append: Boolean
  ): F[WriteCursor[F]]

  /** Writes all data to a sequence of files, each limited in size to `limit`.
    *
    * The `computePath` operation is used to compute the path of the first file
    * and every subsequent file. Typically, the next file should be determined
    * by analyzing the current state of the filesystem -- e.g., by looking at all
    * files in a directory and generating a unique name.
    */
  def writeRotate(
      computePath: F[Path],
      limit: Long,
      flags: Seq[StandardOpenOption] = List(StandardOpenOptionCreate)
  ): Pipe[F, Byte, INothing]
}

object Files extends FilesSingletonPlatform {
  def apply[F[_]](implicit F: Files[F]): F.type = F
}
