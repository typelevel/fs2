package fs2
package io

import java.nio.file._
import java.util.stream.{Stream => JStream}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.implicits._

/** Provides support for working with files. */
package object file {
  import java.nio.file.attribute.FileAttribute
  import java.nio.file.CopyOption
  import java.nio.file.LinkOption
  import java.nio.file.Files

  /**
    * Reads all data synchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAll[F[_]: Sync: ContextShift](
      path: Path,
      blocker: Blocker,
      chunkSize: Int
  ): Stream[F, Byte] =
    pulls
      .fromPath(path, blocker, List(StandardOpenOption.READ))
      .flatMap(c => pulls.readAllFromFileHandle(chunkSize)(c.resource))
      .stream

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
    pulls
      .fromPath(path, blocker, List(StandardOpenOption.READ))
      .flatMap(c => pulls.readRangeFromFileHandle(chunkSize, start, end)(c.resource))
      .stream

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
    pulls
      .fromPath(path, blocker, List(StandardOpenOption.READ))
      .flatMap(c => pulls.tailFromFileHandle(chunkSize, offset, pollDelay)(c.resource))
      .stream

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
      (for {
        out <- pulls.fromPath(path, blocker, StandardOpenOption.WRITE :: flags.toList)
        fileHandle = out.resource
        offset <- if (flags.contains(StandardOpenOption.APPEND)) Pull.eval(fileHandle.size)
        else Pull.pure(0L)
        _ <- pulls.writeAllToFileHandleAtOffset(in, fileHandle, offset)
      } yield ()).stream

  private def _writeAll0[F[_]](
      in: Stream[F, Byte],
      out: FileHandle[F],
      offset: Long
  ): Pull[F, Nothing, Unit] =
    in.pull.uncons.flatMap {
      case None => Pull.done
      case Some((hd, tl)) =>
        _writeAll1(hd, out, offset) >> _writeAll0(tl, out, offset + hd.size)
    }

  private def _writeAll1[F[_]](
      buf: Chunk[Byte],
      out: FileHandle[F],
      offset: Long
  ): Pull[F, Nothing, Unit] =
    Pull.eval(out.write(buf, offset)).flatMap { (written: Int) =>
      if (written >= buf.size)
        Pull.pure(())
      else
        _writeAll1(buf.drop(written), out, offset + written)
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
