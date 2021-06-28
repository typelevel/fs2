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
import cats.effect.std.Hotswap
import cats.syntax.all._

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.{Files => JFiles, _}
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.{Stream => JStream}

import fs2.io.CollectionCompat._

private[file] trait FilesPlatform[F[_]] {

  /** Creates a `FileHandle` for the supplied `FileChannel`. */
  def openFileChannel(channel: F[FileChannel]): Resource[F, FileHandle[F]]

}

private[file] trait FilesSingletonPlatform {
  type Path = java.nio.file.Path
  type CopyOption = java.nio.file.CopyOption
  type FileAttribute[A] = java.nio.file.attribute.FileAttribute[A]
  type FileVisitOption = java.nio.file.FileVisitOption
  type LinkOption = java.nio.file.LinkOption
  type OpenOption = java.nio.file.OpenOption
  type PosixFilePermission = java.nio.file.attribute.PosixFilePermission
  type StandardOpenOption = java.nio.file.StandardOpenOption
  val StandardOpenOptionCreate = java.nio.file.StandardOpenOption.CREATE
  type WatchEvent[A] = java.nio.file.WatchEvent[A]
  type WatchEventModifier = java.nio.file.WatchEvent.Modifier

  implicit def forAsync[F[_]: Async]: Files[F] = new AsyncFiles[F]

  private final class AsyncFiles[F[_]: Async] extends Files[F] {

    def copy(source: Path, target: Path, flags: Seq[CopyOption]): F[Path] =
      Sync[F].blocking(JFiles.copy(source, target, flags: _*))

    def createDirectory(path: Path, flags: Seq[FileAttribute[_]]): F[Path] =
      Sync[F].blocking(JFiles.createDirectory(path, flags: _*))

    def createDirectories(path: Path, flags: Seq[FileAttribute[_]]): F[Path] =
      Sync[F].blocking(JFiles.createDirectories(path, flags: _*))

    def delete(path: Path): F[Unit] =
      Sync[F].blocking(JFiles.delete(path))

    def deleteIfExists(path: Path): F[Boolean] =
      Sync[F].blocking(JFiles.deleteIfExists(path))

    def deleteDirectoryRecursively(
        path: Path,
        options: Set[FileVisitOption]
    ): F[Unit] =
      Sync[F].blocking {
        JFiles.walkFileTree(
          path,
          options.asJava,
          Int.MaxValue,
          new SimpleFileVisitor[Path] {
            override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
              JFiles.deleteIfExists(path)
              FileVisitResult.CONTINUE
            }
            override def postVisitDirectory(path: Path, e: IOException): FileVisitResult = {
              JFiles.deleteIfExists(path)
              FileVisitResult.CONTINUE
            }
          }
        )
        ()
      }

    def directoryStream(path: Path): Stream[F, Path] =
      _runJavaCollectionResource[DirectoryStream[Path]](
        Sync[F].blocking(JFiles.newDirectoryStream(path)),
        _.asScala.iterator
      )

    def directoryStream(path: Path, filter: Path => Boolean): Stream[F, Path] =
      _runJavaCollectionResource[DirectoryStream[Path]](
        Sync[F].blocking(JFiles.newDirectoryStream(path, (entry: Path) => filter(entry))),
        _.asScala.iterator
      )

    def directoryStream(path: Path, glob: String): Stream[F, Path] =
      _runJavaCollectionResource[DirectoryStream[Path]](
        Sync[F].blocking(JFiles.newDirectoryStream(path, glob)),
        _.asScala.iterator
      )

    private final val pathStreamChunkSize = 16
    private def _runJavaCollectionResource[C <: AutoCloseable](
        javaCollection: F[C],
        collectionIterator: C => Iterator[Path]
    ): Stream[F, Path] =
      Stream
        .resource(Resource.fromAutoCloseable(javaCollection))
        .flatMap(ds => Stream.fromBlockingIterator[F](collectionIterator(ds), pathStreamChunkSize))

    def exists(path: Path, flags: Seq[LinkOption]): F[Boolean] =
      Sync[F].blocking(JFiles.exists(path, flags: _*))

    def isDirectory(
        path: Path,
        linkOption: Seq[LinkOption]
    ): F[Boolean] =
      Sync[F].delay(
        JFiles.isDirectory(path, linkOption: _*)
      )

    def isFile(
        path: Path,
        linkOption: Seq[LinkOption]
    ): F[Boolean] =
      Sync[F].delay(
        JFiles.isRegularFile(path, linkOption: _*)
      )

    def move(source: Path, target: Path, flags: Seq[CopyOption]): F[Path] =
      Sync[F].blocking(JFiles.move(source, target, flags: _*))

    def open(path: Path, flags: Seq[OpenOption]): Resource[F, FileHandle[F]] =
      openFileChannel(Sync[F].blocking(FileChannel.open(path, flags: _*)))

    def openFileChannel(channel: F[FileChannel]): Resource[F, FileHandle[F]] =
      Resource.make(channel)(ch => Sync[F].blocking(ch.close())).map(ch => FileHandle.make(ch))

    def permissions(path: Path, flags: Seq[LinkOption]): F[Set[PosixFilePermission]] =
      Sync[F].blocking(JFiles.getPosixFilePermissions(path, flags: _*).asScala)

    def readAll(path: Path, chunkSize: Int): Stream[F, Byte] =
      Stream.resource(readCursor(path)).flatMap { cursor =>
        cursor.readAll(chunkSize).void.stream
      }

    def readCursor(path: Path, flags: Seq[OpenOption] = Nil): Resource[F, ReadCursor[F]] =
      open(path, StandardOpenOption.READ :: flags.toList).map { fileHandle =>
        ReadCursor(fileHandle, 0L)
      }

    def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte] =
      Stream.resource(readCursor(path)).flatMap { cursor =>
        cursor.seek(start).readUntil(chunkSize, end).void.stream
      }

    def setPermissions(path: Path, permissions: Set[PosixFilePermission]): F[Path] =
      Sync[F].blocking(JFiles.setPosixFilePermissions(path, permissions.asJava))

    def size(path: Path): F[Long] =
      Sync[F].blocking(JFiles.size(path))

    def tail(path: Path, chunkSize: Int, offset: Long, pollDelay: FiniteDuration): Stream[F, Byte] =
      Stream.resource(readCursor(path)).flatMap { cursor =>
        cursor.seek(offset).tail(chunkSize, pollDelay).void.stream
      }

    def tempFile(
        dir: Option[Path],
        prefix: String,
        suffix: String,
        attributes: Seq[FileAttribute[_]]
    ): Resource[F, Path] =
      Resource.make {
        dir match {
          case Some(dir) =>
            Sync[F].blocking(JFiles.createTempFile(dir, prefix, suffix, attributes: _*))
          case None =>
            Sync[F].blocking(JFiles.createTempFile(prefix, suffix, attributes: _*))
        }

      }(deleteIfExists(_).void)

    def tempDirectory(
        dir: Option[Path],
        prefix: String,
        attributes: Seq[FileAttribute[_]]
    ): Resource[F, Path] =
      Resource.make {
        dir match {
          case Some(dir) =>
            Sync[F].blocking(JFiles.createTempDirectory(dir, prefix, attributes: _*))
          case None =>
            Sync[F].blocking(JFiles.createTempDirectory(prefix, attributes: _*))
        }
      } { p =>
        deleteDirectoryRecursively(p)
          .recover { case _: NoSuchFileException => () }
      }

    def walk(start: Path): Stream[F, Path] =
      walk(start, Seq.empty)

    def walk(start: Path, options: Seq[FileVisitOption]): Stream[F, Path] =
      walk(start, Int.MaxValue, options)

    def walk(start: Path, maxDepth: Int, options: Seq[FileVisitOption]): Stream[F, Path] =
      _runJavaCollectionResource[JStream[Path]](
        Sync[F].blocking(JFiles.walk(start, maxDepth, options: _*)),
        _.iterator.asScala
      )

    def watcher: Resource[F, Watcher[F]] = Watcher.default

    def watch(
        path: Path,
        types: Seq[Watcher.EventType],
        modifiers: Seq[WatchEvent.Modifier],
        pollTimeout: FiniteDuration
    ): Stream[F, Watcher.Event] =
      Stream
        .resource(Watcher.default)
        .evalTap(_.watch(path, types, modifiers))
        .flatMap(_.events(pollTimeout))

    def writeAll(
        path: Path,
        flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE)
    ): Pipe[F, Byte, INothing] =
      in =>
        Stream
          .resource(writeCursor(path, flags))
          .flatMap(_.writeAll(in).void.stream)

    def writeCursor(
        path: Path,
        flags: Seq[OpenOption] = List(StandardOpenOption.CREATE)
    ): Resource[F, WriteCursor[F]] =
      open(path, StandardOpenOption.WRITE :: flags.toList).flatMap { fileHandle =>
        val size = if (flags.contains(StandardOpenOption.APPEND)) fileHandle.size else 0L.pure[F]
        val cursor = size.map(s => WriteCursor(fileHandle, s))
        Resource.eval(cursor)
      }

    def writeCursorFromFileHandle(
        file: FileHandle[F],
        append: Boolean
    ): F[WriteCursor[F]] =
      if (append) file.size.map(s => WriteCursor(file, s)) else WriteCursor(file, 0L).pure[F]

    def writeRotate(
        computePath: F[Path],
        limit: Long,
        flags: Seq[StandardOpenOption]
    ): Pipe[F, Byte, INothing] = {
      def openNewFile: Resource[F, FileHandle[F]] =
        Resource
          .eval(computePath)
          .flatMap(p => open(p, StandardOpenOption.WRITE :: flags.toList))

      def newCursor(file: FileHandle[F]): F[WriteCursor[F]] =
        writeCursorFromFileHandle(file, flags.contains(StandardOpenOption.APPEND))

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
          .flatMap { case (fileHotswap, fileHandle) =>
            Stream.eval(newCursor(fileHandle)).flatMap { cursor =>
              go(fileHotswap, cursor, 0L, in).stream.drain
            }
          }
    }
  }
}
