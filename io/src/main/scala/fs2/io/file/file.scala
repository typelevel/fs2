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
import java.nio.file._
import java.nio.file.attribute.{BasicFileAttributes, FileAttribute, PosixFilePermission}
import java.util.stream.{Stream => JStream}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.syntax.all._
import fs2.io.CollectionCompat._

import scala.concurrent.duration._

/** Provides support for working with files. */
package object file {

  /**
    * Reads all data synchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAll[F[_]: Sync: ContextShift](
      path: Path,
      blocker: Blocker,
      chunkSize: Int
  ): Stream[F, Byte] =
    Stream.resource(ReadCursor.fromPath(path, blocker)).flatMap { cursor =>
      cursor.readAll(chunkSize).void.stream
    }

  /**
    * Reads a range of data synchronously from the file at the specified `java.nio.file.Path`.
    * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
    * two bytes are read.
    */
  def readRange[F[_]: Sync: ContextShift](
      path: Path,
      blocker: Blocker,
      chunkSize: Int,
      start: Long,
      end: Long
  ): Stream[F, Byte] =
    Stream.resource(ReadCursor.fromPath(path, blocker)).flatMap { cursor =>
      cursor.seek(start).readUntil(chunkSize, end).void.stream
    }

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
  def tail[F[_]: Sync: ContextShift: Timer](
      path: Path,
      blocker: Blocker,
      chunkSize: Int,
      offset: Long = 0L,
      pollDelay: FiniteDuration = 1.second
  ): Stream[F, Byte] =
    Stream.resource(ReadCursor.fromPath(path, blocker)).flatMap { cursor =>
      cursor.seek(offset).tail(chunkSize, pollDelay).void.stream
    }

  /**
    * Writes all data to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAll[F[_]: Sync: ContextShift](
      path: Path,
      blocker: Blocker,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  ): Pipe[F, Byte, Unit] =
    in =>
      Stream
        .resource(WriteCursor.fromPath(path, blocker, flags))
        .flatMap(_.writeAll(in).void.stream)

  /**
    * Writes all data to a sequence of files, each limited in size to `limit`.
    *
    * The `computePath` operation is used to compute the path of the first file
    * and every subsequent file. Typically, the next file should be determined
    * by analyzing the current state of the filesystem -- e.g., by looking at all
    * files in a directory and generating a unique name.
    */
  def writeRotate[F[_]: Concurrent: ContextShift](
      computePath: F[Path],
      limit: Long,
      blocker: Blocker,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  ): Pipe[F, Byte, Unit] = {
    def openNewFile: Resource[F, FileHandle[F]] =
      Resource
        .liftF(computePath)
        .flatMap(p => FileHandle.fromPath(p, blocker, StandardOpenOption.WRITE :: flags.toList))

    def newCursor(file: FileHandle[F]): F[WriteCursor[F]] =
      WriteCursor.fromFileHandle[F](file, flags.contains(StandardOpenOption.APPEND))

    def go(
        fileHotswap: Hotswap[F, FileHandle[F]],
        cursor: WriteCursor[F],
        acc: Long,
        s: Stream[F, Byte]
    ): Pull[F, Unit, Unit] = {
      val toWrite = (limit - acc).min(Int.MaxValue.toLong).toInt
      s.pull.unconsLimit(toWrite).flatMap {
        case Some((hd, tl)) =>
          val newAcc = acc + hd.size
          cursor.writePull(hd).flatMap { nc =>
            if (newAcc >= limit)
              Pull
                .eval {
                  fileHotswap
                    .swap(openNewFile)
                    .flatMap(newCursor)
                }
                .flatMap(nc => go(fileHotswap, nc, 0L, tl))
            else
              go(fileHotswap, nc, newAcc, tl)
          }
        case None => Pull.done
      }
    }

    in =>
      Stream
        .resource(Hotswap(openNewFile))
        .flatMap {
          case (fileHotswap, fileHandle) =>
            Stream.eval(newCursor(fileHandle)).flatMap { cursor =>
              go(fileHotswap, cursor, 0L, in).stream
            }
        }
  }

  /**
    * Creates a [[Watcher]] for the default file system.
    *
    * The watcher is returned as a resource. To use the watcher, lift the resource to a stream,
    * watch or register 1 or more paths, and then return `watcher.events()`.
    */
  def watcher[F[_]: Concurrent: ContextShift](blocker: Blocker): Resource[F, Watcher[F]] =
    Watcher.default(blocker)

  /**
    * Watches a single path.
    *
    * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
    */
  def watch[F[_]: Concurrent: ContextShift](
      blocker: Blocker,
      path: Path,
      types: Seq[Watcher.EventType] = Nil,
      modifiers: Seq[WatchEvent.Modifier] = Nil,
      pollTimeout: FiniteDuration = 1.second
  ): Stream[F, Watcher.Event] =
    Stream
      .resource(Watcher.default(blocker))
      .flatMap(w => Stream.eval_(w.watch(path, types, modifiers)) ++ w.events(pollTimeout))

  /**
    * Checks if a file exists
    *
    * Note that the result of this method is immediately outdated. If this
    * method indicates the file exists then there is no guarantee that a
    * subsequence access will succeed. Care should be taken when using this
    * method in security sensitive applications.
    */
  def exists[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      flags: Seq[LinkOption] = Seq.empty
  ): F[Boolean] =
    blocker.delay(Files.exists(path, flags: _*))

  /**
    * Get file permissions as set of [[PosixFilePermission]]
    *
    * This will only work for POSIX supporting file systems
    */
  def permissions[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      flags: Seq[LinkOption] = Seq.empty
  ): F[Set[PosixFilePermission]] =
    blocker.delay(Files.getPosixFilePermissions(path, flags: _*).asScala)

  /**
    * Set file permissions from set of [[PosixFilePermission]]
    *
    * This will only work for POSIX supporting file systems
    */
  def setPermissions[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      permissions: Set[PosixFilePermission]
  ): F[Path] =
    blocker.delay(Files.setPosixFilePermissions(path, permissions.asJava))

  /**
    * Copies a file from the source to the target path,
    *
    * By default, the copy fails if the target file already exists or is a symbolic link.
    */
  def copy[F[_]: Sync: ContextShift](
      blocker: Blocker,
      source: Path,
      target: Path,
      flags: Seq[CopyOption] = Seq.empty
  ): F[Path] =
    blocker.delay(Files.copy(source, target, flags: _*))

  /**
    * Deletes a file.
    *
    * If the file is a directory then the directory must be empty for this action to succeed.
    * This action will fail if the path doesn't exist.
    */
  def delete[F[_]: Sync: ContextShift](blocker: Blocker, path: Path): F[Unit] =
    blocker.delay(Files.delete(path))

  /**
    * Like `delete`, but will not fail when the path doesn't exist.
    */
  def deleteIfExists[F[_]: Sync: ContextShift](blocker: Blocker, path: Path): F[Boolean] =
    blocker.delay(Files.deleteIfExists(path))

  /**
    * Recursively delete a directory
    */
  def deleteDirectoryRecursively[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      options: Set[FileVisitOption] = Set.empty
  ): F[Unit] =
    blocker.delay {
      Files.walkFileTree(
        path,
        options.asJava,
        Int.MaxValue,
        new SimpleFileVisitor[Path] {
          override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
            Files.deleteIfExists(path)
            FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(path: Path, e: IOException): FileVisitResult = {
            Files.deleteIfExists(path)
            FileVisitResult.CONTINUE
          }
        }
      )
    }

  /**
    * Returns the size of a file (in bytes).
    */
  def size[F[_]: Sync: ContextShift](blocker: Blocker, path: Path): F[Long] =
    blocker.delay(Files.size(path))

  /**
    * Moves (or renames) a file from the source to the target path.
    *
    * By default, the move fails if the target file already exists or is a symbolic link.
    */
  def move[F[_]: Sync: ContextShift](
      blocker: Blocker,
      source: Path,
      target: Path,
      flags: Seq[CopyOption] = Seq.empty
  ): F[Path] =
    blocker.delay(Files.move(source, target, flags: _*))

  /**
    * Creates a stream containing the path of a temporary file.
    *
    * The temporary file is removed when the stream completes.
    */
  def tempFileStream[F[_]: Sync: ContextShift](
      blocker: Blocker,
      dir: Path,
      prefix: String = "",
      suffix: String = ".tmp",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Stream[F, Path] =
    Stream.resource(tempFileResource[F](blocker, dir, prefix, suffix, attributes))

  /**
    * Creates a resource containing the path of a temporary file.
    *
    * The temporary file is removed during the resource release.
    */
  def tempFileResource[F[_]: Sync: ContextShift](
      blocker: Blocker,
      dir: Path,
      prefix: String = "",
      suffix: String = ".tmp",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, Path] =
    Resource.make {
      blocker.delay(Files.createTempFile(dir, prefix, suffix, attributes: _*))
    }(deleteIfExists[F](blocker, _).void)

  /**
    * Creates a stream containing the path of a temporary directory.
    *
    * The temporary directory is removed when the stream completes.
    */
  def tempDirectoryStream[F[_]: Sync: ContextShift](
      blocker: Blocker,
      dir: Path,
      prefix: String = "",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Stream[F, Path] =
    Stream.resource(tempDirectoryResource[F](blocker, dir, prefix, attributes))

  /**
    * Creates a resource containing the path of a temporary directory.
    *
    * The temporary directory is removed during the resource release.
    */
  def tempDirectoryResource[F[_]: Sync: ContextShift](
      blocker: Blocker,
      dir: Path,
      prefix: String = "",
      attributes: Seq[FileAttribute[_]] = Seq.empty
  ): Resource[F, Path] =
    Resource.make {
      blocker.delay(Files.createTempDirectory(dir, prefix, attributes: _*))
    } { p =>
      deleteDirectoryRecursively[F](blocker, p)
        .recover { case _: NoSuchFileException => () }
    }

  /**
    * Creates a new directory at the given path
    */
  def createDirectory[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      flags: Seq[FileAttribute[_]] = Seq.empty
  ): F[Path] =
    blocker.delay(Files.createDirectory(path, flags: _*))

  /**
    * Creates a new directory at the given path and creates all nonexistent parent directories beforehand.
    */
  def createDirectories[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      flags: Seq[FileAttribute[_]] = Seq.empty
  ): F[Path] =
    blocker.delay(Files.createDirectories(path, flags: _*))

  /**
    * Creates a stream of [[Path]]s inside a directory.
    */
  def directoryStream[F[_]: Sync: ContextShift](blocker: Blocker, path: Path): Stream[F, Path] =
    _runJavaCollectionResource[F, DirectoryStream[Path]](
      blocker,
      blocker.delay(Files.newDirectoryStream(path)),
      _.asScala.iterator
    )

  /**
    * Creates a stream of [[Path]]s inside a directory, filtering the results by the given predicate.
    */
  def directoryStream[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      filter: Path => Boolean
  ): Stream[F, Path] =
    _runJavaCollectionResource[F, DirectoryStream[Path]](
      blocker,
      blocker.delay(Files.newDirectoryStream(path, (entry: Path) => filter(entry))),
      _.asScala.iterator
    )

  /**
    * Creates a stream of [[Path]]s inside a directory which match the given glob.
    */
  def directoryStream[F[_]: Sync: ContextShift](
      blocker: Blocker,
      path: Path,
      glob: String
  ): Stream[F, Path] =
    _runJavaCollectionResource[F, DirectoryStream[Path]](
      blocker,
      blocker.delay(Files.newDirectoryStream(path, glob)),
      _.asScala.iterator
    )

  /**
    * Creates a stream of [[Path]]s contained in a given file tree. Depth is unlimited.
    */
  def walk[F[_]: Sync: ContextShift](blocker: Blocker, start: Path): Stream[F, Path] =
    walk[F](blocker, start, Seq.empty)

  /**
    * Creates a stream of [[Path]]s contained in a given file tree, respecting the supplied options. Depth is unlimited.
    */
  def walk[F[_]: Sync: ContextShift](
      blocker: Blocker,
      start: Path,
      options: Seq[FileVisitOption]
  ): Stream[F, Path] =
    walk[F](blocker, start, Int.MaxValue, options)

  /**
    * Creates a stream of [[Path]]s contained in a given file tree down to a given depth.
    */
  def walk[F[_]: Sync: ContextShift](
      blocker: Blocker,
      start: Path,
      maxDepth: Int,
      options: Seq[FileVisitOption] = Seq.empty
  ): Stream[F, Path] =
    _runJavaCollectionResource[F, JStream[Path]](
      blocker,
      blocker.delay(Files.walk(start, maxDepth, options: _*)),
      _.iterator.asScala
    )

  private def _runJavaCollectionResource[F[_]: Sync: ContextShift, C <: AutoCloseable](
      blocker: Blocker,
      javaCollection: F[C],
      collectionIterator: C => Iterator[Path]
  ): Stream[F, Path] =
    Stream
      .resource(Resource.fromAutoCloseable(javaCollection))
      .flatMap(ds => Stream.fromBlockingIterator[F](blocker, collectionIterator(ds)))
}
