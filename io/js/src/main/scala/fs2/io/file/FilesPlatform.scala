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
import fs2.io.file.Files.UnsealedFiles
import fs2.io.internal.facade

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
          facade.fs.promises.copyFile(
            source.toString,
            target.toString,
            CopyFlag.monoid.combineAll(flags.value).jsBits.toDouble
          )
        )
      ).adaptError { case IOException(ex) => ex }

    private def mkdir(path: Path, permissions: Option[Permissions], _recursive: Boolean): F[Unit] =
      F.fromPromise(
        F.delay(
          facade.fs.promises.mkdir(
            path.toString,
            new facade.fs.MkdirOptions {
              recursive = _recursive
              permissions.collect { case PosixPermissions(value) =>
                mode = value.toDouble
              }
            }
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
        F.delay(facade.fs.promises.symlink(target.toString, link.toString))
      ) >> (permissions match {
        case Some(PosixPermissions(value)) =>
          F.fromPromise(F.delay(facade.fs.promises.lchmod(link.toString, value.toDouble)))
        case _ => F.unit
      })).adaptError { case IOException(ex) => ex }

    override def createTempDirectory(
        dir: Option[Path],
        prefix: String,
        permissions: Option[Permissions]
    ): F[Path] =
      F.fromPromise(
        F.delay(
          facade.fs.promises.mkdtemp(dir.fold(facade.os.tmpdir())(_.toString) + Path.sep + prefix)
        )
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
        F.fromPromise(F.delay(facade.fs.promises.rmdir(path.toString))),
        F.fromPromise(F.delay(facade.fs.promises.rm(path.toString)))
      ).adaptError { case IOException(ex) => ex }

    def currentWorkingDirectory: F[Path] =
      F.delay(Path(facade.process.cwd()))

    def userHome: F[Path] =
      F.delay(Path(facade.os.homedir()))

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
            facade.fs.promises.rm(
              path.toString,
              new facade.fs.RmOptions {
                force = true
                recursive = true
              }
            )
          )
        ).adaptError { case IOException(ex) => ex }
      else
        walk(path, Int.MaxValue, true).evalTap(deleteIfExists).compile.drain

    override def exists(path: Path, followLinks: Boolean): F[Boolean] =
      (if (followLinks)
         F.fromPromise(F.delay(facade.fs.promises.access(path.toString)))
       else
         F.fromPromise(
           F.delay(facade.fs.promises.lstat(path.toString, new facade.fs.StatOptions {}))
         ))
        .as(true)
        .recover { case _ => false }

    private def stat(path: Path, followLinks: Boolean = false): F[facade.fs.BigIntStats] =
      F.fromPromise {
        F.delay {
          val options = new facade.fs.StatOptions { bigint = true }
          if (followLinks)
            facade.fs.promises.stat(path.toString, options)
          else
            facade.fs.promises.lstat(path.toString, options)
        }
      }.adaptError { case IOException(ex) => ex }
        .widen

    private def access(path: Path, mode: Double): F[Boolean] =
      F.fromPromise(F.delay(facade.fs.promises.access(path.toString, mode)))
        .as(true)
        .recover { case _ =>
          false
        }

    override def getBasicFileAttributes(path: Path, followLinks: Boolean): F[BasicFileAttributes] =
      getPosixFileAttributes(path, followLinks).widen

    override def getLastModifiedTime(path: Path, followLinks: Boolean): F[FiniteDuration] =
      stat(path, followLinks).map { stats =>
        stats.mtimeNs.toString.toLong.nanos
      }

    def getPosixFileAttributes(path: Path, followLinks: Boolean): F[PosixFileAttributes] =
      stat(path, followLinks).map { stats =>
        new PosixFileAttributes.UnsealedPosixFileAttributes {
          def creationTime: FiniteDuration = stats.ctimeNs.toString.toLong.nanos
          def fileKey: Option[FileKey] = if (stats.dev != js.BigInt(0) || stats.ino != js.BigInt(0))
            Some(PosixFileKey(stats.dev.toString.toLong, stats.ino.toString.toLong))
          else None
          def isDirectory: Boolean = stats.isDirectory()
          def isOther: Boolean = !isDirectory && !isRegularFile && !isSymbolicLink
          def isRegularFile: Boolean = stats.isFile()
          def isSymbolicLink: Boolean = stats.isSymbolicLink()
          def lastAccessTime: FiniteDuration = stats.atimeNs.toString.toLong.nanos
          def lastModifiedTime: FiniteDuration = stats.mtimeNs.toString.toLong.nanos
          def size: Long = stats.size.toString.toLong
          def permissions: PosixPermissions = {
            val value = stats.mode.toString.toInt & 511
            PosixPermissions.fromInt(value).get
          }
        }
      }

    override def getPosixPermissions(path: Path, followLinks: Boolean): F[PosixPermissions] =
      getPosixFileAttributes(path, followLinks).map(_.permissions)

    override def isDirectory(path: Path, followLinks: Boolean): F[Boolean] =
      stat(path, followLinks).map(_.isDirectory()).recover { case _: NoSuchFileException => false }

    override def isExecutable(path: Path): F[Boolean] =
      access(path, facade.fs.constants.X_OK)

    override def isHidden(path: Path): F[Boolean] = F.pure {
      val fileName = path.fileName.toString
      fileName.length >= 2 && fileName(0) == '.' && fileName(1) != '.'
    }

    override def isReadable(path: Path): F[Boolean] =
      access(path, facade.fs.constants.R_OK)

    override def isRegularFile(path: Path, followLinks: Boolean): F[Boolean] =
      stat(path, followLinks).map(_.isFile()).recover { case _: NoSuchFileException => false }

    override def isSymbolicLink(path: Path): F[Boolean] =
      stat(path).map(_.isSymbolicLink()).recover { case _: NoSuchFileException => false }

    override def isWritable(path: Path): F[Boolean] =
      access(path, facade.fs.constants.W_OK)

    override def isSameFile(path1: Path, path2: Path): F[Boolean] =
      F.pure(path1.absolute == path2.absolute)

    override def list(path: Path): Stream[F, Path] =
      Stream
        .bracket(F.fromPromise(F.delay(facade.fs.promises.opendir(path.toString))))(dir =>
          F.fromPromise(F.delay(dir.close()))
        )
        .flatMap { dir =>
          Stream
            .repeatEval(F.fromPromise(F.delay(dir.read())))
            .map(Option(_))
            .unNoneTerminate
            .map(entry => path / Path(entry.name))
        }
        .adaptError { case IOException(ex) => ex }

    override def move(source: Path, target: Path, flags: CopyFlags): F[Unit] =
      F.ifM(
        if (flags.contains(CopyFlag.ReplaceExisting))
          F.pure(true)
        else
          exists(target).map(!_)
      )(
        F.fromPromise(F.delay(facade.fs.promises.rename(source.toString, target.toString))),
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
                facade.fs.promises
                  .open(path.toString, combineFlags(flags))
              )(
                facade.fs.promises
                  .open(path.toString, combineFlags(flags), _)
              )
          )
        )
      )(fd => F.fromPromise(F.delay(fd.close())))
      .map(FileHandle.make[F])
      .adaptError { case IOException(ex) => ex }

    private def readStream(path: Path, chunkSize: Int, _flags: Flags)(
        f: facade.fs.ReadStreamOptions => facade.fs.ReadStreamOptions
    ): Stream[F, Byte] =
      Stream
        .resource(suspendReadableAndRead() {
          facade.fs.createReadStream(
            path.toString,
            f(new facade.fs.ReadStreamOptions {
              flags = combineFlags(_flags)
              highWaterMark = chunkSize
            })
          )
        })
        .flatMap(_._2)

    override def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte] =
      readStream(path, chunkSize, flags)(identity)

    def realPath(path: Path): F[Path] =
      F.fromPromise(F.delay(facade.fs.promises.realpath(path.toString))).map(Path(_)).adaptError {
        case NoSuchFileException(e) => e
      }

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
            facade.fs.promises.utimes(
              path.toString,
              lastAccess.fold(stats.atimeMs.toString.toDouble)(_.toMillis.toDouble),
              lastModified.fold(stats.mtimeMs.toString.toDouble)(_.toMillis.toDouble)
            )
          )
        )
      }
      .adaptError { case IOException(ex) => ex }

    override def setPosixPermissions(path: Path, permissions: PosixPermissions): F[Unit] =
      F.fromPromise(F.delay(facade.fs.promises.chmod(path.toString, permissions.value.toDouble)))
        .adaptError { case IOException(ex) => ex }

    override def size(path: Path): F[Long] =
      stat(path).map(_.size.toString.toLong)

    override def writeAll(path: Path, _flags: Flags): Pipe[F, Byte, Nothing] =
      in =>
        in.through {
          writeWritable(
            F.async_[Writable] { cb =>
              val ws = facade.fs
                .createWriteStream(
                  path.toString,
                  new facade.fs.WriteStreamOptions {
                    flags = combineFlags(_flags)
                  }
                )
              ws.once[Unit](
                "ready",
                _ => {
                  ws.removeAllListeners()
                  cb(Right(ws))
                }
              )
              ws.once[js.Error](
                "error",
                error => {
                  ws.removeAllListeners()
                  cb(Left(js.JavaScriptException(error)))
                }
              )
              ()
            }
          )
        }
  }
}
