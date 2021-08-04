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

import cats.effect.kernel.{Async, Resource, Sync}
import cats.syntax.all._

import java.nio.channels.FileChannel
import java.nio.file.{Files => JFiles, Path => JPath, _}
import java.nio.file.attribute.{BasicFileAttributes, PosixFilePermissions}
import java.util.stream.{Stream => JStream}

import fs2.io.CollectionCompat._

private[file] trait FilesPlatform[F[_]] extends DeprecatedFilesApi[F] { self: Files[F] =>

  /** Creates a `FileHandle` for the supplied NIO `FileChannel`. */
  def openFileChannel(channel: F[FileChannel]): Resource[F, FileHandle[F]]

}

private[file] trait FilesCompanionPlatform {

  implicit def forAsync[F[_]: Async]: Files[F] = new AsyncFiles[F]

  private final class AsyncFiles[F[_]](protected implicit val F: Async[F])
      extends Files.UnsealedFiles[F] {

    def copy(source: Path, target: Path, flags: CopyFlags): F[Unit] =
      Sync[F].blocking {
        JFiles.copy(source.toNioPath, target.toNioPath, flags.value.map(_.option): _*)
        ()
      }

    def createDirectory(path: Path, permissions: Option[Permissions]): F[Unit] =
      Sync[F].blocking {
        JFiles.createDirectory(path.toNioPath, permissions.map(_.toNioFileAttribute).toSeq: _*)
        ()
      }

    def createDirectories(path: Path, permissions: Option[Permissions]): F[Unit] =
      Sync[F].blocking {
        JFiles.createDirectories(path.toNioPath, permissions.map(_.toNioFileAttribute).toSeq: _*)
        ()
      }

    def createTempFile(
        dir: Option[Path],
        prefix: String,
        suffix: String,
        permissions: Option[Permissions]
    ): F[Path] =
      (dir match {
        case Some(dir) =>
          Sync[F].blocking(
            JFiles.createTempFile(
              dir.toNioPath,
              prefix,
              suffix,
              permissions.map(_.toNioFileAttribute).toSeq: _*
            )
          )
        case None =>
          Sync[F].blocking(
            JFiles.createTempFile(prefix, suffix, permissions.map(_.toNioFileAttribute).toSeq: _*)
          )
      }).map(Path.fromNioPath)

    def createTempDirectory(
        dir: Option[Path],
        prefix: String,
        permissions: Option[Permissions]
    ): F[Path] =
      (dir match {
        case Some(dir) =>
          Sync[F].blocking(
            JFiles.createTempDirectory(
              dir.toNioPath,
              prefix,
              permissions.map(_.toNioFileAttribute).toSeq: _*
            )
          )
        case None =>
          Sync[F].blocking(
            JFiles.createTempDirectory(prefix, permissions.map(_.toNioFileAttribute).toSeq: _*)
          )
      }).map(Path.fromNioPath)

    def delete(path: Path): F[Unit] =
      Sync[F].blocking(JFiles.delete(path.toNioPath))

    def deleteIfExists(path: Path): F[Boolean] =
      Sync[F].blocking(JFiles.deleteIfExists(path.toNioPath))

    def deleteRecursively(
        path: Path,
        followLinks: Boolean = false
    ): F[Unit] =
      Sync[F].blocking {
        JFiles.walkFileTree(
          path.toNioPath,
          (if (followLinks) Set(FileVisitOption.FOLLOW_LINKS)
           else Set.empty[FileVisitOption]).asJava,
          Int.MaxValue,
          new SimpleFileVisitor[JPath] {
            override def visitFile(path: JPath, attrs: BasicFileAttributes): FileVisitResult = {
              JFiles.deleteIfExists(path)
              FileVisitResult.CONTINUE
            }
            override def postVisitDirectory(path: JPath, e: IOException): FileVisitResult = {
              JFiles.deleteIfExists(path)
              FileVisitResult.CONTINUE
            }
          }
        )
        ()
      }

    def exists(path: Path, followLinks: Boolean): F[Boolean] =
      Sync[F].blocking(
        JFiles.exists(
          path.toNioPath,
          (if (followLinks) Nil else Seq(LinkOption.NOFOLLOW_LINKS)): _*
        )
      )

    def getPosixPermissions(path: Path, followLinks: Boolean): F[PosixPermissions] =
      Sync[F].blocking(
        PosixPermissions
          .fromString(
            PosixFilePermissions.toString(
              JFiles.getPosixFilePermissions(
                path.toNioPath,
                (if (followLinks) Nil else Seq(LinkOption.NOFOLLOW_LINKS)): _*
              )
            )
          )
          .get
      )

    def isDirectory(path: Path, followLinks: Boolean): F[Boolean] =
      Sync[F].delay(
        JFiles.isDirectory(
          path.toNioPath,
          (if (followLinks) Nil else Seq(LinkOption.NOFOLLOW_LINKS)): _*
        )
      )

    def isExecutable(path: Path): F[Boolean] =
      Sync[F].delay(JFiles.isExecutable(path.toNioPath))

    def isHidden(path: Path): F[Boolean] =
      Sync[F].delay(JFiles.isHidden(path.toNioPath))

    def isReadable(path: Path): F[Boolean] =
      Sync[F].delay(JFiles.isReadable(path.toNioPath))

    def isRegularFile(path: Path, followLinks: Boolean): F[Boolean] =
      Sync[F].delay(
        JFiles.isRegularFile(
          path.toNioPath,
          (if (followLinks) Nil else Seq(LinkOption.NOFOLLOW_LINKS)): _*
        )
      )

    def isSymbolicLink(path: Path): F[Boolean] =
      Sync[F].delay(JFiles.isSymbolicLink(path.toNioPath))

    def isWritable(path: Path): F[Boolean] =
      Sync[F].delay(JFiles.isWritable(path.toNioPath))

    def list(path: Path): Stream[F, Path] =
      _runJavaCollectionResource[JStream[JPath]](
        Sync[F].blocking(JFiles.list(path.toNioPath)),
        _.iterator.asScala
      ).map(Path.fromNioPath)

    def list(path: Path, glob: String): Stream[F, Path] =
      _runJavaCollectionResource[DirectoryStream[JPath]](
        Sync[F].blocking(JFiles.newDirectoryStream(path.toNioPath, glob)),
        _.iterator.asScala
      ).map(Path.fromNioPath)

    def move(source: Path, target: Path, flags: CopyFlags): F[Unit] =
      Sync[F].blocking {
        JFiles.move(source.toNioPath, target.toNioPath, flags.value.map(_.option): _*)
        ()
      }

    def open(path: Path, flags: Flags): Resource[F, FileHandle[F]] =
      openFileChannel(
        Sync[F].blocking(FileChannel.open(path.toNioPath, flags.value.map(_.option): _*))
      )

    def openFileChannel(channel: F[FileChannel]): Resource[F, FileHandle[F]] =
      Resource.make(channel)(ch => Sync[F].blocking(ch.close())).map(ch => FileHandle.make(ch))

    def setPosixPermissions(path: Path, permissions: PosixPermissions): F[Unit] =
      Sync[F]
        .blocking(
          JFiles.setPosixFilePermissions(
            path.toNioPath,
            PosixFilePermissions.fromString(permissions.toString)
          )
        )
        .void

    def size(path: Path): F[Long] =
      Sync[F].blocking(JFiles.size(path.toNioPath))

    def tempFile(
        dir: Option[Path],
        prefix: String,
        suffix: String,
        permissions: Option[Permissions]
    ): Resource[F, Path] =
      Resource.make(createTempFile(dir, prefix, suffix, permissions))(deleteIfExists(_).void)

    def tempDirectory(
        dir: Option[Path],
        prefix: String,
        permissions: Option[Permissions]
    ): Resource[F, Path] =
      Resource.make(createTempDirectory(dir, prefix, permissions))(deleteRecursively(_).recover {
        case _: NoSuchFileException => ()
      })

    def walk(start: Path, maxDepth: Int, followLinks: Boolean): Stream[F, Path] =
      _runJavaCollectionResource[JStream[JPath]](
        Sync[F].blocking(
          JFiles.walk(
            start.toNioPath,
            maxDepth,
            (if (followLinks) Seq(FileVisitOption.FOLLOW_LINKS) else Nil): _*
          )
        ),
        _.iterator.asScala
      ).map(Path.fromNioPath)

    private final val pathStreamChunkSize = 16
    protected def _runJavaCollectionResource[C <: AutoCloseable](
        javaCollection: F[C],
        collectionIterator: C => Iterator[JPath]
    ): Stream[F, JPath] =
      Stream
        .resource(Resource.fromAutoCloseable(javaCollection))
        .flatMap(ds => Stream.fromBlockingIterator[F](collectionIterator(ds), pathStreamChunkSize))
  }
}
