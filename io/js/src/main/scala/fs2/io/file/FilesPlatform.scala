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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.internal.jsdeps.node.eventsMod
import fs2.internal.jsdeps.node.fsMod
import fs2.internal.jsdeps.node.fsPromisesMod
import fs2.internal.jsdeps.node.nodeStrings
import fs2.internal.jsdeps.node.osMod
import fs2.io.file.Files.UnsealedFiles

import scala.concurrent.duration._
import scala.scalajs.js

private[file] trait FilesPlatform[F[_]]

private[fs2] trait FilesCompanionPlatform {

  implicit def forAsync[F[_]: Async]: Files[F] = new AsyncFiles[F]

  private final class AsyncFiles[F[_]](implicit F: Async[F]) extends UnsealedFiles[F] {
    private def combineFlags(flags: Flags): Double =
      Flag.monoid.combineAll(flags.value).bits.toDouble

    override def copy(source: Path, target: Path, flags: CopyFlags): F[Unit] =
      F.fromPromise(
        F.delay(
          fsPromisesMod.copyFile(
            source.toString,
            target.toString,
            CopyFlag.monoid.combineAll(flags.value).jsBits.toDouble
          )
        )
      ).adaptError { case IOException(ex) => ex }

    private def mkdir(path: Path, permissions: Option[Permissions], recursive: Boolean): F[Unit] =
      F.fromPromise(
        F.delay(
          fsPromisesMod.mkdir(
            path.toString,
            permissions
              .collect { case PosixPermissions(value) =>
                value.toDouble
              }
              .fold(fsMod.MakeDirectoryOptions())(fsMod.MakeDirectoryOptions().setMode(_))
              .setRecursive(recursive)
          )
        )
      ).void
        .adaptError { case IOException(ex) => ex }

    override def createDirectory(path: Path, permissions: Option[Permissions]): F[Unit] =
      mkdir(path, permissions, false)

    override def createDirectories(path: Path, permissions: Option[Permissions]): F[Unit] =
      mkdir(path, permissions, true)

    override def createFile(path: Path, permissions: Option[Permissions]): F[Unit] =
      open(path, Flags(Flag.CreateNew), permissions).use_

    override def createSymbolicLink(
        link: Path,
        target: Path,
        permissions: Option[Permissions]
    ): F[Unit] =
      (F.fromPromise(
        F.delay(fsPromisesMod.link(target.toString, link.toString))
      ) >> (permissions match {
        case Some(PosixPermissions(value)) =>
          F.fromPromise(F.delay(fsPromisesMod.lchmod(link.toString, value)))
        case _ => F.unit
      })).adaptError { case IOException(ex) => ex }

    override def createTempDirectory(
        dir: Option[Path],
        prefix: String,
        permissions: Option[Permissions]
    ): F[Path] =
      F.fromPromise(
        F.delay(fsPromisesMod.mkdtemp((dir.fold(osMod.tmpdir())(_.toString) + Path.sep + prefix)))
      ).map(Path(_))
        .flatTap { path =>
          permissions
            .collect { case posix @ PosixPermissions(_) => posix }
            .fold(F.unit)(setPosixPermissions(path, _))
        }
        .adaptError { case IOException(ex) => ex }

    override def createTempFile(
        dir: Option[Path],
        prefix: String,
        suffix: String,
        permissions: Option[Permissions]
    ): F[Path] =
      for {
        dir <- createTempDirectory(dir, prefix, permissions)
        path = dir / Option(suffix).filter(_.nonEmpty).getOrElse(".tmp")
        _ <- open(path, Flags.Write).use_
        _ <- permissions
          .collect { case posix @ PosixPermissions(_) =>
            posix
          }
          .fold(F.unit)(setPosixPermissions(path, _))
      } yield path

    private def rmMaybeDir(path: Path): F[Unit] =
      F.ifM(isDirectory(path))(
        F.fromPromise(F.delay(fsPromisesMod.rmdir(path.toString))),
        F.fromPromise(F.delay(fsPromisesMod.rm(path.toString)))
      ).adaptError { case IOException(ex) => ex }

    override def delete(path: Path): F[Unit] =
      rmMaybeDir(path)

    override def deleteIfExists(path: Path): F[Boolean] =
      exists(path).flatMap { exists =>
        if (exists)
          rmMaybeDir(path).as(exists).recover { case _: NoSuchFileException =>
            false
          }
        else
          F.pure(exists)
      }

    override def deleteRecursively(
        path: Path,
        followLinks: Boolean
    ): F[Unit] =
      if (!followLinks)
        F.fromPromise(
          F.delay(
            fsPromisesMod.rm(path.toString, fsMod.RmOptions().setRecursive(true).setForce(true))
          )
        ).adaptError { case IOException(ex) => ex }
      else
        walk(path, Int.MaxValue, true).evalTap(deleteIfExists).compile.drain

    override def exists(path: Path, followLinks: Boolean): F[Boolean] =
      F.ifM(F.pure(followLinks))(
        F.fromPromise(F.delay(fsPromisesMod.access(path.toString))).void,
        F.fromPromise(F.delay(fsPromisesMod.lstat(path.toString))).void
      ).as(true)
        .recover { case _ => false }

    private def stat(path: Path, followLinks: Boolean = false): F[fsMod.Stats] =
      F.fromPromise {
        F.delay {
          if (followLinks)
            fsPromisesMod.stat(path.toString)
          else
            fsPromisesMod.lstat(path.toString)
        }
      }.adaptError { case IOException(ex) => ex }

    private def access(path: Path, mode: Double): F[Boolean] =
      F.fromPromise(F.delay(fsPromisesMod.access(path.toString, mode)))
        .as(true)
        .recover { case _ =>
          false
        }

    override def getBasicFileAttributes(path: Path, followLinks: Boolean): F[BasicFileAttributes] =
      getPosixFileAttributes(path, followLinks).widen

    override def getLastModifiedTime(path: Path, followLinks: Boolean): F[FiniteDuration] =
      stat(path, followLinks).map { stats =>
        stats.mtimeMs.milliseconds
      }

    def getPosixFileAttributes(path: Path, followLinks: Boolean): F[PosixFileAttributes] =
      stat(path, followLinks).map { stats =>
        new PosixFileAttributes.UnsealedPosixFileAttributes {
          def creationTime: FiniteDuration = stats.ctimeMs.milliseconds
          def fileKey: Option[FileKey] = if (stats.dev != 0 || stats.ino != 0)
            Some(PosixFileKey(stats.dev.toLong, stats.ino.toLong))
          else None
          def isDirectory: Boolean = stats.isDirectory()
          def isOther: Boolean = !isDirectory && !isRegularFile && !isSymbolicLink
          def isRegularFile: Boolean = stats.isFile()
          def isSymbolicLink: Boolean = stats.isSymbolicLink()
          def lastAccessTime: FiniteDuration = stats.atimeMs.milliseconds
          def lastModifiedTime: FiniteDuration = stats.mtimeMs.milliseconds
          def size: Long = stats.size.toLong
          def permissions: PosixPermissions = {
            val value = stats.mode.toInt & 511
            PosixPermissions.fromInt(value).get
          }
        }
      }

    override def getPosixPermissions(path: Path, followLinks: Boolean): F[PosixPermissions] =
      getPosixFileAttributes(path, followLinks).map(_.permissions)

    override def isDirectory(path: Path, followLinks: Boolean): F[Boolean] =
      stat(path, followLinks).map(_.isDirectory())

    override def isExecutable(path: Path): F[Boolean] =
      access(path, fsMod.constants.X_OK)

    private val HiddenPattern = raw"/(^|\/)\.[^\/\.]/g".r
    override def isHidden(path: Path): F[Boolean] = F.pure {
      path.toString match {
        case HiddenPattern() => true
        case _               => false
      }
    }

    override def isReadable(path: Path): F[Boolean] =
      access(path, fsMod.constants.R_OK)

    override def isRegularFile(path: Path, followLinks: Boolean): F[Boolean] =
      stat(path, followLinks).map(_.isFile())

    override def isSymbolicLink(path: Path): F[Boolean] =
      stat(path).map(_.isSymbolicLink())

    override def isWritable(path: Path): F[Boolean] =
      access(path, fsMod.constants.W_OK)

    override def isSameFile(path1: Path, path2: Path): F[Boolean] =
      F.pure(path1.absolute == path2.absolute)

    override def list(path: Path): Stream[F, Path] =
      Stream
        .bracket(F.fromPromise(F.delay(fsPromisesMod.opendir(path.toString))))(dir =>
          F.fromPromise(F.delay(dir.close()))
        )
        .flatMap { dir =>
          Stream
            .repeatEval(F.fromPromise(F.delay(dir.read())))
            .map(Option(_))
            .unNoneTerminate
            .map(entry => path / Path(entry.asInstanceOf[fsMod.Dirent].name))
        }
        .adaptError { case IOException(ex) => ex }

    override def move(source: Path, target: Path, flags: CopyFlags): F[Unit] =
      F.ifM(
        F.ifM(F.pure(flags.contains(CopyFlag.ReplaceExisting)))(
          F.pure(true),
          exists(target).map(!_)
        )
      )(
        F.fromPromise(F.delay(fsPromisesMod.rename(source.toString, target.toString))),
        F.raiseError(new FileAlreadyExistsException)
      ).adaptError { case IOException(ex) => ex }

    override def open(path: Path, flags: Flags): Resource[F, FileHandle[F]] =
      open(path, flags, None)

    private def open(
        path: Path,
        flags: Flags,
        mode: Option[Permissions]
    ): Resource[F, FileHandle[F]] = Resource
      .make(
        F.fromPromise(
          F.delay(
            mode
              .collect { case PosixPermissions(value) =>
                value.toDouble
              }
              .fold(
                fsPromisesMod
                  .open(path.toString, combineFlags(flags))
              )(
                fsPromisesMod
                  .open(path.toString, combineFlags(flags), _)
              )
          )
        )
      )(fd => F.fromPromise(F.delay(fd.close())))
      .map(FileHandle.make[F])
      .adaptError { case IOException(ex) => ex }

    private def readStream(path: Path, chunkSize: Int, flags: Flags)(
        f: fsMod.ReadStreamOptions => fsMod.ReadStreamOptions
    ): Stream[F, Byte] =
      readReadable(
        F.async_[Readable] { cb =>
          val rs = fsMod
            .createReadStream(
              path.toString,
              f(
                js.Dynamic
                  .literal(flags = combineFlags(flags))
                  .asInstanceOf[fsMod.ReadStreamOptions]
                  .setHighWaterMark(chunkSize.toDouble)
              )
            )
          rs.once_ready(
            nodeStrings.ready,
            () => {
              rs.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
              cb(Right(rs.asInstanceOf[Readable]))
            }
          )
          rs.once_error(
            nodeStrings.error,
            error => {
              rs.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
              cb(Left(js.JavaScriptException(error)))
            }
          )
        }
      )

    override def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte] =
      readStream(path, chunkSize, flags)(identity)

    override def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte] =
      readStream(path, chunkSize, Flags.Read)(_.setStart(start.toDouble).setEnd((end - 1).toDouble))

    override def setFileTimes(
        path: Path,
        lastModified: Option[FiniteDuration],
        lastAccess: Option[FiniteDuration],
        create: Option[FiniteDuration],
        followLinks: Boolean
    ): F[Unit] = stat(path, followLinks)
      .flatMap { stats =>
        F.fromPromise(
          F.delay(
            fsPromisesMod.utimes(
              path.toString,
              lastAccess.fold(stats.atimeMs)(_.toMillis.toDouble),
              lastModified.fold(stats.mtimeMs)(_.toMillis.toDouble)
            )
          )
        )
      }
      .adaptError { case IOException(ex) => ex }

    override def setPosixPermissions(path: Path, permissions: PosixPermissions): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.chmod(path.toString, permissions.value.toDouble)))
        .adaptError { case IOException(ex) => ex }

    override def size(path: Path): F[Long] =
      stat(path).map(_.size.toLong)

    override def writeAll(path: Path, flags: Flags): Pipe[F, Byte, INothing] =
      in =>
        in.through {
          writeWritable(
            F.async_[Writable] { cb =>
              val ws = fsMod
                .createWriteStream(
                  path.toString,
                  js.Dynamic
                    .literal(flags = combineFlags(flags))
                    .asInstanceOf[fsMod.StreamOptions]
                )
              ws.once_ready(
                nodeStrings.ready,
                () => {
                  ws.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
                  cb(Right(ws.asInstanceOf[Writable]))
                }
              )
              ws.once_error(
                nodeStrings.error,
                error => {
                  ws.asInstanceOf[eventsMod.EventEmitter].removeAllListeners()
                  cb(Left(js.JavaScriptException(error)))
                }
              )
            }
          )
        }
  }
}
