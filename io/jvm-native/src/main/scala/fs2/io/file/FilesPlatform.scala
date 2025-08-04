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

import java.nio.channels.{FileChannel, SeekableByteChannel}
import java.nio.file.{Files => JFiles, Path => JPath, FileSystemLoopException => _, _}
import java.nio.file.attribute.{
  BasicFileAttributeView,
  BasicFileAttributes => JBasicFileAttributes,
  PosixFileAttributes => JPosixFileAttributes,
  PosixFilePermissions,
  FileTime
}
import java.security.Principal
import java.util.stream.{Stream => JStream}

import scala.concurrent.duration._
import scala.util.control.NonFatal

import fs2.io.CollectionCompat._

private[file] trait FilesPlatform[F[_]] extends DeprecatedFilesApi[F] { self: Files[F] =>

  /** Creates a `FileHandle` for the supplied NIO `FileChannel`. JVM only. */
  def openFileChannel(channel: F[FileChannel]): Resource[F, FileHandle[F]]

  /** Creates a `FileHandle` for the supplied NIO `SeekableByteChannel`. Because a `SeekableByteChannel` doesn't provide all the functionalities required by `FileHandle` some features like locking will be unavailable. JVM only. */
  def openSeekableByteChannel(
      channel: F[SeekableByteChannel],
      unsupportedOperationException: => Throwable
  ): Resource[F, FileHandle[F]]

  /** Gets the contents of the specified directory whose paths match the supplied glob pattern.
    *
    * Example glob patterns: `*.scala`, `*.{scala,java}`
    *
    * JVM only.
    */
  def list(path: Path, glob: String): Stream[F, Path]

  /** Watches a single path.
    *
    * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
    *
    * JVM only.
    */
  def watch(path: Path): Stream[F, Watcher.Event] =
    watch(path, Nil, Nil, 1.second)

  /** Watches a single path.
    *
    * Alias for creating a watcher and watching the supplied path, releasing the watcher when the resulting stream is finalized.
    *
    * JVM only.
    */
  def watch(
      path: Path,
      types: Seq[Watcher.EventType],
      modifiers: Seq[WatchEvent.Modifier],
      pollTimeout: FiniteDuration
  ): Stream[F, Watcher.Event]
}

private[file] trait FilesCompanionPlatform {

  def forAsync[F[_]: Async]: Files[F] = new AsyncFiles[F]

  private case class NioFileKey(value: AnyRef) extends FileKey

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

    def createFile(path: Path, permissions: Option[Permissions]): F[Unit] =
      Sync[F].blocking {
        JFiles.createFile(path.toNioPath, permissions.map(_.toNioFileAttribute).toSeq: _*)
        ()
      }

    def createSymbolicLink(link: Path, target: Path, permissions: Option[Permissions]): F[Unit] =
      Sync[F].blocking {
        JFiles.createSymbolicLink(
          link.toNioPath,
          target.toNioPath,
          permissions.map(_.toNioFileAttribute).toSeq: _*
        )
        ()
      }

    def createLink(link: Path, existing: Path): F[Unit] =
      Sync[F].blocking {
        JFiles.createLink(
          link.toNioPath,
          existing.toNioPath
        )
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

    def currentWorkingDirectory: F[Path] =
      Sync[F].delay(Path(Option(System.getProperty("user.dir")).get))

    def userHome: F[Path] =
      Sync[F].delay(Path(Option(System.getProperty("user.home")).get))

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
            override def visitFile(path: JPath, attrs: JBasicFileAttributes): FileVisitResult = {
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

    def getBasicFileAttributes(path: Path, followLinks: Boolean): F[BasicFileAttributes] =
      Sync[F].blocking(
        new DelegatingBasicFileAttributes(
          JFiles.readAttributes(
            path.toNioPath,
            classOf[JBasicFileAttributes],
            (if (followLinks) Nil else Seq(LinkOption.NOFOLLOW_LINKS)): _*
          )
        )
      )

    def getPosixFileAttributes(path: Path, followLinks: Boolean): F[PosixFileAttributes] =
      Sync[F].blocking(
        new DelegatingPosixFileAttributes(
          JFiles.readAttributes(
            path.toNioPath,
            classOf[JPosixFileAttributes],
            (if (followLinks) Nil else Seq(LinkOption.NOFOLLOW_LINKS)): _*
          )
        )
      )

    def getLastModifiedTime(path: Path, followLinks: Boolean): F[FiniteDuration] =
      Sync[F].blocking(
        JFiles.getLastModifiedTime(path.toNioPath).toMillis.millis
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

    def isSameFile(path1: Path, path2: Path): F[Boolean] =
      Sync[F].blocking(JFiles.isSameFile(path1.toNioPath, path2.toNioPath))

    def lineSeparator: String = System.lineSeparator()

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
      ).recoverWith { case unsupportedOperationException: UnsupportedOperationException =>
        // not all file systems support file channels
        openSeekableByteChannel(
          Sync[F].blocking(JFiles.newByteChannel(path.toNioPath, flags.value.map(_.option): _*)),
          unsupportedOperationException
        )
      }

    def openFileChannel(channel: F[FileChannel]): Resource[F, FileHandle[F]] =
      Resource.make(channel)(ch => Sync[F].blocking(ch.close())).map(ch => FileHandle.make(ch))

    def openSeekableByteChannel(
        channel: F[SeekableByteChannel],
        unsupportedOperationException: => Throwable
    ): Resource[F, FileHandle[F]] =
      Resource
        .make(channel)(ch => Sync[F].blocking(ch.close()))
        .map(ch => FileHandle.makeFromSeekableByteChannel(ch, unsupportedOperationException))

    def realPath(path: Path): F[Path] =
      Sync[F].blocking(Path.fromNioPath(path.toNioPath.toRealPath()))

    def setFileTimes(
        path: Path,
        lastModified: Option[FiniteDuration],
        lastAccess: Option[FiniteDuration],
        create: Option[FiniteDuration],
        followLinks: Boolean
    ): F[Unit] =
      Sync[F].blocking {
        val view = JFiles.getFileAttributeView(
          path.toNioPath,
          classOf[BasicFileAttributeView],
          (if (followLinks) Nil else Seq(LinkOption.NOFOLLOW_LINKS)): _*
        )
        def toFileTime(opt: Option[FiniteDuration]): FileTime =
          opt.map(d => FileTime.fromMillis(d.toMillis)).orNull
        view.setTimes(toFileTime(lastModified), toFileTime(lastAccess), toFileTime(create))
      }

    def setLastModifiedTime(path: Path, timestamp: FiniteDuration): F[Unit] =
      Sync[F]
        .blocking(
          JFiles.setLastModifiedTime(path.toNioPath, FileTime.fromMillis(timestamp.toMillis))
        )
        .void

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

    protected def _runJavaCollectionResource[C <: AutoCloseable](
        javaCollection: F[C],
        collectionIterator: C => Iterator[JPath]
    ): Stream[F, JPath] =
      Stream
        .resource(Resource.fromAutoCloseable(javaCollection))
        .flatMap(ds => Stream.fromBlockingIterator[F](collectionIterator(ds), PathStreamChunkSize))

    private case class WalkEntry(
        path: Path,
        attr: JBasicFileAttributes,
        depth: Int,
        ancestry: List[Either[Path, NioFileKey]]
    )

    override def walkWithAttributes(
        start: Path,
        options: WalkOptions
    ): Stream[F, PathInfo] = {
      import scala.collection.immutable.Queue

      def loop(toWalk0: Queue[WalkEntry]): Stream[F, PathInfo] = {
        val partialWalk = Sync[F].interruptible {
          var acc = Vector.empty[PathInfo]
          var toWalk = toWalk0

          while (acc.size < options.chunkSize && toWalk.nonEmpty && !Thread.interrupted()) {
            val entry = toWalk.head
            toWalk = toWalk.drop(1)
            acc = acc :+ PathInfo(entry.path, new DelegatingBasicFileAttributes(entry.attr))
            if (entry.depth < options.maxDepth) {
              val dir =
                if (entry.attr.isDirectory) entry.path
                else if (options.followLinks && entry.attr.isSymbolicLink) {
                  try {
                    val targetAttr =
                      JFiles.readAttributes(entry.path.toNioPath, classOf[JBasicFileAttributes])
                    val fileKey = Option(targetAttr.fileKey).map(NioFileKey(_))
                    val isCycle = entry.ancestry.exists {
                      case Right(ancestorKey) =>
                        fileKey.contains(ancestorKey)
                      case Left(ancestorPath) =>
                        JFiles.isSameFile(entry.path.toNioPath, ancestorPath.toNioPath)
                    }
                    if (isCycle)
                      if (options.allowCycles) null
                      else throw new FileSystemLoopException(entry.path.toString)
                    else entry.path
                  } catch {
                    case t: FileSystemLoopException => throw t
                    case NonFatal(_)                => null
                  }
                } else null
              if (dir ne null) {
                try {
                  val listing = JFiles.list(dir.toNioPath)
                  try {
                    val descendants = listing.iterator.asScala.flatMap { p =>
                      try
                        Some(
                          WalkEntry(
                            Path.fromNioPath(p),
                            JFiles.readAttributes(
                              p,
                              classOf[JBasicFileAttributes],
                              LinkOption.NOFOLLOW_LINKS
                            ),
                            entry.depth + 1,
                            Option(entry.attr.fileKey)
                              .map(NioFileKey(_))
                              .toRight(entry.path) :: entry.ancestry
                          )
                        )
                      catch {
                        case NonFatal(_) => None
                      }
                    }
                    toWalk = Queue.empty ++ descendants ++ toWalk
                  } finally listing.close()
                } catch {
                  case NonFatal(_) => ()
                }
              }
            }
          }

          Stream.chunk(Chunk.from(acc)) ++ (if (toWalk.isEmpty) Stream.empty else loop(toWalk))
        }
        Stream.eval(partialWalk).flatten
      }

      Stream
        .eval(Sync[F].interruptible {
          WalkEntry(
            start,
            JFiles.readAttributes(start.toNioPath, classOf[JBasicFileAttributes]),
            0,
            Nil
          )
        })
        .mask
        .flatMap(w => loop(Queue(w)))
    }

    def createWatcher: Resource[F, Watcher[F]] = Watcher.default(this, F)

    def watch(
        path: Path,
        types: Seq[Watcher.EventType],
        modifiers: Seq[WatchEvent.Modifier],
        pollTimeout: FiniteDuration
    ): Stream[F, Watcher.Event] =
      Stream
        .resource(createWatcher)
        .evalTap(_.watch(path, types, modifiers))
        .flatMap(_.events(pollTimeout))
  }

  private class DelegatingBasicFileAttributes(attr: JBasicFileAttributes)
      extends BasicFileAttributes.UnsealedBasicFileAttributes {
    def creationTime = attr.creationTime.toMillis.millis
    def fileKey = Option(attr.fileKey).map(NioFileKey(_))
    def isDirectory = attr.isDirectory
    def isOther = attr.isOther
    def isRegularFile = attr.isRegularFile
    def isSymbolicLink = attr.isSymbolicLink
    def lastAccessTime = attr.lastAccessTime.toMillis.millis
    def lastModifiedTime = attr.lastModifiedTime.toMillis.millis
    def size = attr.size
  }

  private class DelegatingPosixFileAttributes(attr: JPosixFileAttributes)
      extends DelegatingBasicFileAttributes(attr)
      with PosixFileAttributes.UnsealedPosixFileAttributes {
    def owner: Principal = attr.owner
    def group: Principal = attr.group
    def permissions: PosixPermissions =
      PosixPermissions.fromString(PosixFilePermissions.toString(attr.permissions)).get
  }
}
