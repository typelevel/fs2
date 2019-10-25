package fs2
package io

import java.nio.file._
import java.nio.file.attribute.FileAttribute
import java.util.stream.{Stream => JStream}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.implicits._

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
    * Writes all data synchronously to the file at the specified `java.nio.file.Path`.
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

  def writeRotate[F[_]: Sync: ContextShift](
      path: F[Path],
      limit: Long,
      blocker: Blocker,
      flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
  ): Pipe[F, Byte, Unit] = {
    def openNewFile: Resource[F, FileHandle[F]] =
      Resource
        .liftF(path)
        .flatMap(p => FileHandle.fromPath(p, blocker, StandardOpenOption.WRITE :: flags.toList))

    def newCursor(file: FileHandle[F]): F[WriteCursor[F]] =
      WriteCursor.fromFileHandle[F](file, flags.contains(StandardOpenOption.APPEND))

    def go(
        fileProxy: ResourceProxy[F, FileHandle[F]],
        cursor: WriteCursor[F],
        acc: Long,
        s: Stream[F, Byte]
    ): Pull[F, Unit, Unit] = {
      val toWrite = (limit - acc).min(Int.MaxValue.toLong).toInt
      s.pull.unconsLimit(toWrite).flatMap {
        case Some((hd, tl)) =>
          val newAcc = acc + hd.size
          cursor.writePull(hd).flatMap { nc =>
            if (newAcc >= limit) {
              Pull
                .eval {
                  fileProxy
                    .swap(openNewFile)
                    .flatMap(newCursor)
                }
                .flatMap(nc => go(fileProxy, nc, 0L, tl))
            } else {
              go(fileProxy, nc, newAcc, tl)
            }
          }
        case None => Pull.done
      }
    }

    in =>
      Stream
        .resource(ResourceProxy(openNewFile))
        .flatMap { fileProxy =>
          Stream
            .eval {
              fileProxy
                .swap(openNewFile)
                .flatMap(newCursor)
            }
            .flatMap { cursor =>
              go(fileProxy, cursor, 0L, in).stream
            }
        }
  }

  /**
    * Creates a [[Watcher]] for the default file system.
    *
    * A singleton bracketed stream is returned consisting of the single watcher. To use the watcher,
    * `flatMap` the returned stream, watch or register 1 or more paths, and then return `watcher.events()`.
    *
    * @return singleton bracketed stream returning a watcher
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
    * If the file is a directory then the directory must be empty for this action to succed.
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
