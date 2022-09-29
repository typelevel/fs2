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

import cats.effect.kernel.{Async, Resource, Sync}
import cats.syntax.all._

import java.nio.file.{Files => JFiles, Path => JPath, _}
import java.nio.file.attribute.{FileAttribute, PosixFilePermission}
import java.util.stream.{Stream => JStream}

import fs2.io.CollectionCompat._
import fs2.io.{Watcher => DeprecatedWatcher}

private[file] trait DeprecatedFilesApi[F[_]] { self: Files[F] =>

  protected implicit val F: Async[F]

  /** Copies a file from the source to the target path,
    *
    * By default, the copy fails if the target file already exists or is a symbolic link.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def copy(source: JPath, target: JPath, flags: Seq[CopyOption] = Seq.empty): F[JPath] =
    Sync[F].blocking(JFiles.copy(source, target, flags: _*))

  /** Creates a new directory at the given path.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def createDirectory(path: JPath, flags: Seq[FileAttribute[_]] = Seq.empty): F[JPath] =
    Sync[F].blocking(JFiles.createDirectory(path, flags: _*))

  /** Creates a new directory at the given path and creates all nonexistent parent directories beforehand.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def createDirectories(path: JPath, flags: Seq[FileAttribute[_]] = Seq.empty): F[JPath] =
    Sync[F].blocking(JFiles.createDirectories(path, flags: _*))

  /** Deletes a file.
    *
    * If the file is a directory then the directory must be empty for this action to succeed.
    * This action will fail if the path doesn't exist.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def delete(path: JPath): F[Unit] =
    delete(Path.fromNioPath(path))

  /** Like `delete`, but will not fail when the path doesn't exist.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def deleteIfExists(path: JPath): F[Boolean] =
    deleteIfExists(Path.fromNioPath(path))

  /** Recursively delete a directory
    */
  @deprecated("Use deleteRecursively", "3.1.0")
  def deleteDirectoryRecursively(path: JPath, options: Set[FileVisitOption] = Set.empty): F[Unit] =
    deleteRecursively(Path.fromNioPath(path), options.contains(FileVisitOption.FOLLOW_LINKS))

  /** Creates a stream of `Path`s inside a directory.
    */
  @deprecated("Use list(path)", "3.1.0")
  def directoryStream(path: JPath): Stream[F, JPath] =
    _runJavaCollectionResource[DirectoryStream[JPath]](
      Sync[F].blocking(JFiles.newDirectoryStream(path)),
      _.asScala.iterator
    )

  /** Creates a stream of `Path`s inside a directory, filtering the results by the given predicate.
    */
  @deprecated("Use list(path).filter(p)", "3.1.0")
  def directoryStream(path: JPath, filter: JPath => Boolean): Stream[F, JPath] =
    _runJavaCollectionResource[DirectoryStream[JPath]](
      Sync[F].blocking(JFiles.newDirectoryStream(path, (entry: JPath) => filter(entry))),
      _.asScala.iterator
    )

  /** Creates a stream of `Path`s inside a directory which match the given glob.
    */
  @deprecated("Use list(path, glob)", "3.1.0")
  def directoryStream(path: JPath, glob: String): Stream[F, JPath] =
    _runJavaCollectionResource[DirectoryStream[JPath]](
      Sync[F].blocking(JFiles.newDirectoryStream(path, glob)),
      _.asScala.iterator
    )

  protected def _runJavaCollectionResource[C <: AutoCloseable](
      javaCollection: F[C],
      collectionIterator: C => Iterator[JPath]
  ): Stream[F, JPath]

  /** Checks if a file exists.
    *
    * Note that the result of this method is immediately outdated. If this
    * method indicates the file exists then there is no guarantee that a
    * subsequence access will succeed. Care should be taken when using this
    * method in security sensitive applications.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def exists(path: JPath, flags: Seq[LinkOption] = Seq.empty): F[Boolean] =
    Sync[F].blocking(JFiles.exists(path, flags: _*))

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
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def isDirectory(
      path: JPath,
      linkOption: Seq[LinkOption] = Nil
  ): F[Boolean] =
    Sync[F].delay(
      JFiles.isDirectory(path, linkOption: _*)
    )

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
  @deprecated("Use isRegularFile which uses fs2.io.file.Path", "3.1.0")
  def isFile(
      path: JPath,
      linkOption: Seq[LinkOption] = Nil
  ): F[Boolean] =
    Sync[F].delay(
      JFiles.isRegularFile(path, linkOption: _*)
    )

  /** Moves (or renames) a file from the source to the target path.
    *
    * By default, the move fails if the target file already exists or is a symbolic link.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def move(source: JPath, target: JPath, flags: Seq[CopyOption] = Seq.empty): F[JPath] =
    Sync[F].blocking(JFiles.move(source, target, flags: _*))

  /** Creates a `FileHandle` for the file at the supplied `Path`. */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def open(path: JPath, flags: Seq[OpenOption]): Resource[F, FileHandle[F]] =
    open(Path.fromNioPath(path), Flags.fromOpenOptions(flags))

  /** Get file permissions as set of `PosixFilePermission`.
    *
    * Note: this will only work for POSIX supporting file systems.
    */
  @deprecated("Use getPosixPermissions", "3.1.0")
  def permissions(path: JPath, flags: Seq[LinkOption] = Seq.empty): F[Set[PosixFilePermission]] =
    Sync[F].blocking(JFiles.getPosixFilePermissions(path, flags: _*).asScala)

  /** Reads all data from the file at the specified `java.nio.file.Path`.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def readAll(path: JPath, chunkSize: Int): Stream[F, Byte] =
    readAll(Path.fromNioPath(path), chunkSize, Flags.Read)

  /** Returns a `ReadCursor` for the specified path.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def readCursor(path: JPath): Resource[F, ReadCursor[F]] = readCursor(path, Nil)

  /** Returns a `ReadCursor` for the specified path. The `READ` option is added to the supplied flags.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def readCursor(path: JPath, flags: Seq[OpenOption] = Nil): Resource[F, ReadCursor[F]] =
    readCursor(Path.fromNioPath(path), Flags.fromOpenOptions(StandardOpenOption.READ +: flags))

  /** Reads a range of data synchronously from the file at the specified `java.nio.file.Path`.
    * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
    * two bytes are read.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def readRange(path: JPath, chunkSize: Int, start: Long, end: Long): Stream[F, Byte] =
    readRange(Path.fromNioPath(path), chunkSize, start, end)

  /** Set file permissions from set of `PosixFilePermission`.
    *
    * Note: this will only work for POSIX supporting file systems.
    */
  @deprecated("Use setPosixPermissions", "3.1.0")
  def setPermissions(path: JPath, permissions: Set[PosixFilePermission]): F[JPath] =
    Sync[F].blocking(JFiles.setPosixFilePermissions(path, permissions.asJava))

  /** Returns the size of a file (in bytes).
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def size(path: JPath): F[Long] =
    size(Path.fromNioPath(path))

  /** Returns an infinite stream of data from the file at the specified path.
    * Starts reading from the specified offset and upon reaching the end of the file,
    * polls every `pollDuration` for additional updates to the file.
    *
    * Read operations are limited to emitting chunks of the specified chunk size
    * but smaller chunks may occur.
    *
    * If an error occurs while reading from the file, the overall stream fails.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def tail(
      path: JPath,
      chunkSize: Int,
      offset: Long,
      pollDelay: FiniteDuration
  ): Stream[F, Byte] =
    tail(Path.fromNioPath(path), chunkSize, offset, pollDelay)

  /** Creates a `Resource` which can be used to create a temporary file.
    *  The file is created during resource allocation, and removed during its release.
    *
    * @param dir the directory which the temporary file will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the file's name
    * @param suffix the suffix string to be used in generating the file's name
    * @param attributes an optional list of file attributes to set atomically when creating the file
    * @return a resource containing the path of the temporary file
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def tempFile(
      dir: Option[JPath] = None,
      prefix: String = "",
      suffix: String = ".tmp",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, JPath] =
    Resource.make {
      dir match {
        case Some(dir) =>
          Sync[F].blocking(JFiles.createTempFile(dir, prefix, suffix, attributes: _*))
        case None =>
          Sync[F].blocking(JFiles.createTempFile(prefix, suffix, attributes: _*))
      }
    }(deleteIfExists(_).void)

  /** Creates a `Resource` which can be used to create a temporary directory.
    *  The directory is created during resource allocation, and removed during its release.
    *
    * @param dir the directory which the temporary directory will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the directory's name
    * @param attributes an optional list of file attributes to set atomically when creating the directory
    * @return a resource containing the path of the temporary directory
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def tempDirectory(
      dir: Option[JPath] = None,
      prefix: String = "",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, JPath] =
    Resource.make {
      dir match {
        case Some(dir) =>
          Sync[F].blocking(JFiles.createTempDirectory(dir, prefix, attributes: _*))
        case None =>
          Sync[F].blocking(JFiles.createTempDirectory(prefix, attributes: _*))
      }
    } { p =>
      deleteDirectoryRecursively(p, Set.empty)
        .recover { case _: NoSuchFileException => () }
    }

  /** Creates a stream of `JPath`s contained in a given file tree. Depth is unlimited.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def walk(start: JPath): Stream[F, JPath] =
    walk(start, Seq.empty)

  /** Creates a stream of `JPath`s contained in a given file tree, respecting the supplied options. Depth is unlimited.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def walk(start: JPath, options: Seq[FileVisitOption]): Stream[F, JPath] =
    walk(start, Int.MaxValue, options)

  /** Creates a stream of `JPath`s contained in a given file tree down to a given depth.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def walk(
      start: JPath,
      maxDepth: Int,
      options: Seq[FileVisitOption] = Seq.empty
  ): Stream[F, JPath] =
    _runJavaCollectionResource[JStream[JPath]](
      Sync[F].blocking(JFiles.walk(start, maxDepth, options: _*)),
      _.iterator.asScala
    )

  /** Creates a [[Watcher]] for the default file system.
    *
    * The watcher is returned as a resource. To use the watcher, lift the resource to a stream,
    * watch or register 1 or more paths, and then return `watcher.events()`.
    */
  @deprecated("Use Watcher.default", "3.1.0")
  def watcher: Resource[F, DeprecatedWatcher[F]] = DeprecatedWatcher.default

  /** Watches a single path.
    *
    * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def watch(
      path: JPath,
      types: Seq[DeprecatedWatcher.EventType] = Nil,
      modifiers: Seq[WatchEvent.Modifier] = Nil,
      pollTimeout: FiniteDuration = 1.second
  ): Stream[F, DeprecatedWatcher.Event] =
    Stream
      .resource(DeprecatedWatcher.default)
      .evalTap(_.watch(path, types, modifiers))
      .flatMap(_.events(pollTimeout))

  /** Writes all data to the file at the specified `java.nio.file.JPath`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def writeAll(
      path: JPath,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  ): Pipe[F, Byte, Nothing] =
    writeAll(Path.fromNioPath(path), Flags.fromOpenOptions(StandardOpenOption.WRITE +: flags))

  /** Returns a `WriteCursor` for the specified path.
    *
    * The `WRITE` option is added to the supplied flags. If the `APPEND` option is present in `flags`,
    * the offset is initialized to the current size of the file.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def writeCursor(
      path: JPath,
      flags: Seq[OpenOption] = List(StandardOpenOption.CREATE)
  ): Resource[F, WriteCursor[F]] =
    writeCursor(Path.fromNioPath(path), Flags.fromOpenOptions(StandardOpenOption.WRITE +: flags))

  /** Writes all data to a sequence of files, each limited in size to `limit`.
    *
    * The `computePath` operation is used to compute the path of the first file
    * and every subsequent file. Typically, the next file should be determined
    * by analyzing the current state of the filesystem -- e.g., by looking at all
    * files in a directory and generating a unique name.
    */
  @deprecated("Use overload which uses fs2.io.file.Path", "3.1.0")
  def writeRotate(
      computePath: F[JPath],
      limit: Long,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  ): Pipe[F, Byte, Nothing] =
    writeRotate(
      computePath.map(Path.fromNioPath),
      limit,
      Flags.fromOpenOptions(StandardOpenOption.WRITE +: flags)
    )
}
