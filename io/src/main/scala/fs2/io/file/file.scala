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

import java.io.IOException
import java.nio.file.{Files => _, _}
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute, PosixFilePermission}
import java.util.stream.{Stream => JStream}

import cats.effect.{Async, Resource, Sync, Temporal}
import cats.syntax.all._

import fs2.io.CollectionCompat._

import scala.concurrent.duration._

/** Provides support for working with files. */
package object file {

  @deprecated("Use BaseFiles[F].readAll")
  def readAll[F[_]: Sync](
      path: Path,
      chunkSize: Int
  ): Stream[F, Byte] = BaseFiles[F].readAll(path, chunkSize)

  /**
    * Reads a range of data synchronously from the file at the specified `java.nio.file.Path`.
    * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
    * two bytes are read.
    */
  @deprecated("Use BaseFiles[F].readRange")
  def readRange[F[_]: Sync](
      path: Path,
      chunkSize: Int,
      start: Long,
      end: Long
  ): Stream[F, Byte] = BaseFiles[F].readRange(path, chunkSize, start, end)

  /**
    * Returns an infinite stream of data from the file at the specified path.
    * Starts reading from the specified offset and upon reaching the end of the file,
    * polls every `pollDuration` for additional updates to the file.
    *
    * Read operations are limited to emitting chunks of the specified chunk size
    * but smaller chunks may occur.
    *
    * If an error occurs while reading from the file, the overall stream fails.
    */
  @deprecated("Use Files[F].tail")
  def tail[F[_]: Async](
      path: Path,
      chunkSize: Int,
      offset: Long = 0L,
      pollDelay: FiniteDuration = 1.second
  ): Stream[F, Byte] = Files[F].tail(path, chunkSize, offset, pollDelay)

  /**
    * Writes all data to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  @deprecated("Use BaseFiles[F].writeAll")
  def writeAll[F[_]: Sync](
      path: Path,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  ): Pipe[F, Byte, INothing] = BaseFiles[F].writeAll(path, flags)

  /**
    * Writes all data to a sequence of files, each limited in size to `limit`.
    *
    * The `computePath` operation is used to compute the path of the first file
    * and every subsequent file. Typically, the next file should be determined
    * by analyzing the current state of the filesystem -- e.g., by looking at all
    * files in a directory and generating a unique name.
    */
  @deprecated("Use Files[F].writeRotate")
  def writeRotate[F[_]](
      computePath: F[Path],
      limit: Long,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  )(implicit F: Async[F]): Pipe[F, Byte, INothing] =
    Files[F].writeRotate(computePath, limit, flags)

  /**
    * Creates a [[Watcher]] for the default file system.
    *
    * The watcher is returned as a resource. To use the watcher, lift the resource to a stream,
    * watch or register 1 or more paths, and then return `watcher.events()`.
    */
  @deprecated("Use Files[F].watcher")
  def watcher[F[_]](implicit F: Async[F]): Resource[F, Watcher[F]] =
    Files[F].watcher

  /**
    * Watches a single path.
    *
    * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
    */
  @deprecated("Use Files[F].watch")
  def watch[F[_]](
      path: Path,
      types: Seq[Watcher.EventType] = Nil,
      modifiers: Seq[WatchEvent.Modifier] = Nil,
      pollTimeout: FiniteDuration = 1.second
  )(implicit F: Async[F]): Stream[F, Watcher.Event] =
    Files[F].watch(path, types, modifiers, pollTimeout)

  /**
    * Checks if a file exists
    *
    * Note that the result of this method is immediately outdated. If this
    * method indicates the file exists then there is no guarantee that a
    * subsequence access will succeed. Care should be taken when using this
    * method in security sensitive applications.
    */
  @deprecated("Use BaseFiles[F].exists")
  def exists[F[_]: Sync](
      path: Path,
      flags: Seq[LinkOption] = Seq.empty
  ): F[Boolean] =
    BaseFiles[F].exists(path, flags)

  /**
    * Get file permissions as set of [[PosixFilePermission]]
    *
    * This will only work for POSIX supporting file systems
    */
  @deprecated("Use BaseFiles[F].permissions")
  def permissions[F[_]: Sync](
      path: Path,
      flags: Seq[LinkOption] = Seq.empty
  ): F[Set[PosixFilePermission]] =
    BaseFiles[F].permissions(path, flags)

  /**
    * Set file permissions from set of [[PosixFilePermission]]
    *
    * This will only work for POSIX supporting file systems
    */
  @deprecated("Use BaseFiles[F].setPermissions")
  def setPermissions[F[_]: Sync](
      path: Path,
      permissions: Set[PosixFilePermission]
  ): F[Path] =
    BaseFiles[F].setPermissions(path, permissions)

  /**
    * Copies a file from the source to the target path,
    *
    * By default, the copy fails if the target file already exists or is a symbolic link.
    */
  @deprecated("Use BaseFiles[F].copy")
  def copy[F[_]: Sync](
      source: Path,
      target: Path,
      flags: Seq[CopyOption] = Seq.empty
  ): F[Path] =
    BaseFiles[F].copy(source, target, flags)

  /**
    * Deletes a file.
    *
    * If the file is a directory then the directory must be empty for this action to succeed.
    * This action will fail if the path doesn't exist.
    */
  @deprecated("Use BaseFiles[F].delete")
  def delete[F[_]: Sync](path: Path): F[Unit] =
    BaseFiles[F].delete(path)

  /**
    * Like `delete`, but will not fail when the path doesn't exist.
    */
  @deprecated("Use BaseFiles[F].deleteIfExists")
  def deleteIfExists[F[_]: Sync](path: Path): F[Boolean] =
    BaseFiles[F].deleteIfExists(path)

  /**
    * Recursively delete a directory
    */
  @deprecated("Use BaseFiles[F].deleteDirectoryRecursively")
  def deleteDirectoryRecursively[F[_]: Sync](
      path: Path,
      options: Set[FileVisitOption] = Set.empty
  ): F[Unit] =
    BaseFiles[F].deleteDirectoryRecursively(path, options)

  /**
    * Returns the size of a file (in bytes).
    */
  @deprecated("Use BaseFiles[F].size")
  def size[F[_]: Sync](path: Path): F[Long] =
    BaseFiles[F].size(path)

  /**
    * Moves (or renames) a file from the source to the target path.
    *
    * By default, the move fails if the target file already exists or is a symbolic link.
    */
  @deprecated("Use BaseFiles[F].move")
  def move[F[_]: Sync](
      source: Path,
      target: Path,
      flags: Seq[CopyOption] = Seq.empty
  ): F[Path] =
    BaseFiles[F].move(source, target, flags)

  /**
    * Creates a stream containing the path of a temporary file.
    *
    * The temporary file is removed when the stream completes.
    */
  @deprecated("Use Stream.resource(BaseFiles[F].tempFile(..))")
  def tempFileStream[F[_]: Sync](
      dir: Path,
      prefix: String = "",
      suffix: String = ".tmp",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Stream[F, Path] =
    Stream.resource(BaseFiles[F].tempFile(dir, prefix, suffix, attributes))

  /**
    * Creates a resource containing the path of a temporary file.
    *
    * The temporary file is removed during the resource release.
    */
  @deprecated("Use BaseFiles[F].tempFile")
  def tempFileResource[F[_]: Sync](
      dir: Path,
      prefix: String = "",
      suffix: String = ".tmp",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, Path] =
    BaseFiles[F].tempFile(dir, prefix, suffix, attributes)

  /**
    * Creates a stream containing the path of a temporary directory.
    *
    * The temporary directory is removed when the stream completes.
    */
  @deprecated("Use Stream.resource(BaseFiles[F].tempDirectory(..))")
  def tempDirectoryStream[F[_]: Sync](
      dir: Path,
      prefix: String = "",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Stream[F, Path] =
    Stream.resource(BaseFiles[F].tempDirectory(dir, prefix, attributes))

  /**
    * Creates a resource containing the path of a temporary directory.
    *
    * The temporary directory is removed during the resource release.
    */
  @deprecated("Use BaseFiles[F].tempDirectory")
  def tempDirectoryResource[F[_]: Sync](
      dir: Path,
      prefix: String = "",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, Path] =
    BaseFiles[F].tempDirectory(dir, prefix, attributes)

  /**
    * Creates a new directory at the given path
    */
  @deprecated("Use BaseFiles[F].createDirectory")
  def createDirectory[F[_]: Sync](
      path: Path,
      flags: Seq[FileAttribute[_]] = Seq.empty
  ): F[Path] =
    BaseFiles[F].createDirectory(path, flags)

  /**
    * Creates a new directory at the given path and creates all nonexistent parent directories beforehand.
    */
  @deprecated("Use BaseFiles[F].createDirectories")
  def createDirectories[F[_]: Sync](
      path: Path,
      flags: Seq[FileAttribute[_]] = Seq.empty
  ): F[Path] =
    BaseFiles[F].createDirectories(path, flags)

  /**
    * Creates a stream of [[Path]]s inside a directory.
    */
  @deprecated("Use BaseFiles[F].directoryStream")
  def directoryStream[F[_]: Sync](path: Path): Stream[F, Path] =
    BaseFiles[F].directoryStream(path)

  /**
    * Creates a stream of [[Path]]s inside a directory, filtering the results by the given predicate.
    */
  @deprecated("Use BaseFiles[F].directoryStream")
  def directoryStream[F[_]: Sync](
      path: Path,
      filter: Path => Boolean
  ): Stream[F, Path] =
    BaseFiles[F].directoryStream(path, filter)

  /**
    * Creates a stream of [[Path]]s inside a directory which match the given glob.
    */
  @deprecated("Use BaseFiles[F].directoryStream")
  def directoryStream[F[_]: Sync](
      path: Path,
      glob: String
  ): Stream[F, Path] =
    BaseFiles[F].directoryStream(path, glob)

  /**
    * Creates a stream of [[Path]]s contained in a given file tree. Depth is unlimited.
    */
  @deprecated("Use BaseFiles[F].walk")
  def walk[F[_]: Sync](start: Path): Stream[F, Path] =
    BaseFiles[F].walk(start)

  /**
    * Creates a stream of [[Path]]s contained in a given file tree, respecting the supplied options. Depth is unlimited.
    */
  @deprecated("Use BaseFiles[F].walk")
  def walk[F[_]: Sync](
      start: Path,
      options: Seq[FileVisitOption]
  ): Stream[F, Path] =
    BaseFiles[F].walk(start, options)

  /**
    * Creates a stream of [[Path]]s contained in a given file tree down to a given depth.
    */
  @deprecated("Use BaseFiles[F].walk")
  def walk[F[_]: Sync](
      start: Path,
      maxDepth: Int,
      options: Seq[FileVisitOption] = Seq.empty
  ): Stream[F, Path] =
    BaseFiles[F].walk(start, maxDepth, options)
}
