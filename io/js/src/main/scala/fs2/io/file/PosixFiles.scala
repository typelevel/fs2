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
import fs2.internal.jsdeps.node.fsPromisesMod
import fs2.internal.jsdeps.node.fsMod
import fs2.io.file.ReadFiles.UnsealedReadFiles
import fs2.io.file.ReadFiles.TemporalReadFiles

import java.time.Instant
import scala.concurrent.duration._
import fs2.internal.jsdeps.node.streamMod

sealed trait PosixFiles[F[_]] extends UnsealedReadFiles[F] {

  import PosixFiles._

  def access(path: Path, mode: AccessMode = AccessMode.F_OK): F[Boolean]

  def chmod(path: Path, mode: FileAccessMode): F[Unit]

  def chown(path: Path, uid: Long, guid: Long): F[Unit]

  def copyFile(src: Path, dest: Path, mode: CopyMode = CopyMode.Default): F[Unit]

  def lchown(path: Path, uid: Long, guid: Long): F[Unit]

  def link(existingPath: Path, newPath: Path): F[Unit]

  def lstat(path: Path): F[Stats]

  def mkdir(
      path: Path,
      recursive: Boolean = false,
      mode: FileAccessMode = FileAccessMode.Default
  ): F[Path]

  def mkdtemp(prefix: Path): F[Path]

  def open(
      path: Path,
      flags: OpenMode = OpenMode.O_RDONLY,
      mode: FileAccessMode = FileAccessMode.OpenDefault
  ): Resource[F, FileHandle[F]]

  def opendir(path: Path): Stream[F, Path]

  def readCursor(path: Path, flags: OpenMode = OpenMode.O_RDONLY): Resource[F, ReadCursor[F]]

  def readAll(path: Path, flags: OpenMode = OpenMode.O_RDONLY): Stream[F, Byte]

  def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte]

  def readlink(path: Path): F[Path]

  def realpath(path: Path): F[Path]

  def rename(oldPath: Path, newPath: Path): F[Unit]

  def rm(
      path: Path,
      force: Boolean = false,
      maxRetries: Int = 0,
      recursive: Boolean = false,
      retryDelay: FiniteDuration = 100.milliseconds
  ): F[Unit]

  def rmdir(path: Path, maxRetries: Int = 0, retryDelay: FiniteDuration = 100.milliseconds): F[Unit]

  def stat(path: Path): F[Stats]

  def unlink(path: Path): F[Unit]

  def writeCursor(
      path: Path,
      flags: OpenMode = OpenMode.O_WRONLY | OpenMode.O_CREAT
  ): Resource[F, WriteCursor[F]]

  def writeAll(
      path: Path,
      flags: OpenMode = OpenMode.O_WRONLY | OpenMode.O_CREAT
  ): Pipe[F, Byte, INothing]

}

object PosixFiles {

  final class AccessMode private (private[file] val mode: Long) extends AnyVal {
    def |(that: AccessMode) = AccessMode(this.mode | that.mode)
  }
  object AccessMode {
    private def apply(mode: Long) = new AccessMode(mode)
    val F_OK = AccessMode(0)
    val R_OK = AccessMode(1 << 2)
    val W_OK = AccessMode(1 << 1)
    val X_OK = AccessMode(1 << 0)
  }

  final class CopyMode private (private[file] val mode: Long) extends AnyVal {
    def |(that: CopyMode) = CopyMode(this.mode | that.mode)
  }
  object CopyMode {
    def apply(mode: Long) = new CopyMode(mode)
    val COPYFILE_EXCL = CopyMode(1)
    val COPYFILE_FICLONE = CopyMode(2)
    val COPYFILE_FICLONE_FORCE = CopyMode(4)

    private[file] val Default = CopyMode(0)
  }

  final class OpenMode private (private[file] val mode: Long) extends AnyVal {
    def |(that: OpenMode) = OpenMode(this.mode | that.mode)
  }
  object OpenMode {
    private def apply(mode: Long) = new OpenMode(mode)
    val O_RDONLY = OpenMode(0)
    val O_WRONLY = OpenMode(1 << 0)
    val O_RDWR = OpenMode(1 << 1)
    val O_CREAT = OpenMode(1 << 9)
    val O_EXCL = OpenMode(1 << 11)
    val O_NOCTTY = OpenMode(1 << 17)
    val O_TRUNC = OpenMode(1 << 10)
    val O_APPEND = OpenMode(1 << 3)
    val O_DIRECTORY = OpenMode(1 << 20)
    val O_NOATIME = OpenMode(1 << 18)
    val O_NOFOLLOW = OpenMode(1 << 17)
    val O_DSYNC = OpenMode(1 << 12)
    val O_SYMLINK = OpenMode(1 << 21)
    val O_DIRECT = OpenMode(1 << 14)
    val O_NONBLOCK = OpenMode(1 << 11)
  }

  final class FileMode private (private val mode: Long) extends AnyVal {
    def |(that: FileMode) = FileMode(this.mode | that.mode)
    def `type`: FileTypeMode = FileTypeMode(FileTypeMode.S_IFMT.mode & mode)
    def access: FileAccessMode = FileAccessMode(~FileTypeMode.S_IFMT.mode & mode)
  }
  object FileMode {
    def apply(`type`: FileTypeMode, access: FileAccessMode): FileMode =
      apply(`type`.mode | access.mode)
    private[file] def apply(mode: Long) = new FileMode(mode)
  }

  final class FileTypeMode private (private[file] val mode: Long) extends AnyVal {
    def |(that: FileTypeMode) = FileTypeMode(this.mode | that.mode)
  }

  object FileTypeMode {
    private[file] def apply(mode: Long) = new FileTypeMode(mode)
    val S_IFREG = FileTypeMode(1 << 15)
    val S_IFDIR = FileTypeMode(1 << 14)
    val S_IFCHR = FileTypeMode(1 << 13)
    val S_IFBLK = S_IFDIR | S_IFCHR
    val S_IFIFO = FileTypeMode(1 << 12)
    val S_IFLNK = S_IFREG | S_IFCHR
    val S_IFSOCK = S_IFREG | S_IFDIR
    val S_IFMT = S_IFREG | S_IFDIR | S_IFCHR | S_IFIFO
  }

  final class FileAccessMode private (private[file] val mode: Long) extends AnyVal {
    def |(that: FileAccessMode) = FileAccessMode(this.mode | that.mode)
  }
  object FileAccessMode {
    private[file] def apply(mode: Long) = new FileAccessMode(mode)
    val S_IRUSR = FileAccessMode(1 << 8)
    val S_IWUSR = FileAccessMode(1 << 7)
    val S_IXUSR = FileAccessMode(1 << 6)
    val S_IRWXU = S_IRUSR | S_IWUSR | S_IXUSR
    val S_IRGRP = FileAccessMode(1 << 5)
    val S_IWGRP = FileAccessMode(1 << 4)
    val S_IXGRP = FileAccessMode(1 << 3)
    val S_IRWXG = S_IRGRP | S_IWGRP | S_IXGRP
    val S_IROTH = FileAccessMode(1 << 2)
    val S_IWOTH = FileAccessMode(1 << 1)
    val S_IXOTH = FileAccessMode(1 << 0)
    val S_IRWXO = S_IROTH | S_IWOTH | S_IXOTH

    private[file] val Default = S_IRWXU | S_IRWXG | S_IRWXO
    private[file] val OpenDefault = S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP | S_IROTH | S_IWOTH
  }

  sealed trait Stats {
    def isBlockDevice: Boolean
    def isCharacterDevice: Boolean
    def isDirectory: Boolean
    def isFIFO: Boolean
    def isFile: Boolean
    def isSocket: Boolean
    def isSymbolicLink: Boolean
    def dev: Long
    def ino: Long
    def nlink: Long
    def uid: Long
    def gid: Long
    def rdev: Long
    def mode: FileMode
    def size: Long
    def blksize: Long
    def blocks: Long
    def atime: Instant
    def mtime: Instant
    def ctime: Instant
    def birthtime: Instant
  }

  implicit def forAsync[F[_]: Async]: PosixFiles[F] = new AsyncPosixFiles[F]

  private final class AsyncPosixFiles[F[_]](implicit F: Async[F])
      extends TemporalReadFiles[F]
      with PosixFiles[F] {

    override def access(path: Path, mode: AccessMode): F[Boolean] =
      F.fromPromise(F.delay(fsPromisesMod.access(path.toJS, mode.mode.toDouble)))
        .redeem(_ => false, _ => true)

    override def chmod(path: Path, mode: FileAccessMode): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.chmod(path.toJS, mode.mode.toDouble)))

    override def chown(path: Path, uid: Long, guid: Long): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.chown(path.toJS, uid.toDouble, guid.toDouble)))

    override def copyFile(src: Path, dest: Path, mode: CopyMode): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.copyFile(src.toJS, dest.toJS, mode.mode.toDouble)))

    override def lchown(path: Path, uid: Long, guid: Long): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.lchown(path.toJS, uid.toDouble, guid.toDouble)))

    override def link(existingPath: Path, newPath: Path): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.link(existingPath.toJS, newPath.toJS)))

    override def lstat(path: Path): F[Stats] =
      F.fromPromise(F.delay(fsPromisesMod.lstat(path.toJS))).map(new WrappedJSStats(_))

    override def mkdir(path: Path, recursive: Boolean, mode: FileAccessMode): F[Path] =
      F.fromPromise(
        F.delay(
          fsPromisesMod.mkdir(
            path.toJS,
            fsMod.MakeDirectoryOptions().setRecursive(recursive).setMode(mode.mode.toDouble)
          )
        )
      ).map(_.fold(path)(Path(_)))

    override def mkdtemp(prefix: Path): F[Path] =
      F.fromPromise(F.delay(fsPromisesMod.mkdtemp(prefix.toString()))).map(Path(_))

    override def open(
        path: Path,
        flags: OpenMode,
        mode: FileAccessMode
    ): Resource[F, FileHandle[F]] =
      Resource
        .make(
          F.fromPromise(
            F.delay(fsPromisesMod.open(path.toJS, flags.mode.toDouble, mode.mode.toDouble))
          )
        )(fd => F.fromPromise(F.delay(fd.close())))
        .map(FileHandle.make[F])

    override def opendir(path: Path): Stream[F, Path] =
      Stream
        .bracket(F.fromPromise(F.delay(fsPromisesMod.opendir(path.toString()))))(dir =>
          F.fromPromise(F.delay(dir.close()))
        )
        .flatMap { dir =>
          Stream
            .repeatEval(F.fromPromise(F.delay(dir.read())))
            .map(dirent => Option(dirent.asInstanceOf[fsMod.Dirent]))
            .unNoneTerminate
            .map(dirent => Path(dirent.name))
        }

    override def readCursor(path: Path): Resource[F, ReadCursor[F]] =
      readCursor(path, OpenMode.O_RDONLY)

    override def readCursor(path: Path, flags: OpenMode): Resource[F, ReadCursor[F]] =
      open(path, flags | OpenMode.O_RDONLY).map(ReadCursor(_, 0L))

    override def readAll(path: Path, chunkSize: Int): Stream[F, Byte] =
      readAll(path, OpenMode.O_RDONLY)

    override def readAll(path: Path, flags: OpenMode): Stream[F, Byte] =
      fromReadable(
        F.delay(
          fsMod
            .createReadStream(
              path.toJS,
              fsMod.ReadStreamOptions().setMode((flags | OpenMode.O_RDONLY).mode.toDouble)
            )
            .asInstanceOf[streamMod.Readable]
        )
      )

    override def readlink(path: Path): F[Path] =
      F.fromPromise(F.delay(fsPromisesMod.readlink(path.toJS))).map(Path(_))

    override def realpath(path: Path): F[Path] =
      F.fromPromise(F.delay(fsPromisesMod.realpath(path.toJS))).map(Path(_))

    override def rename(oldPath: Path, newPath: Path): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.rename(oldPath.toJS, newPath.toJS)))

    override def rm(
        path: Path,
        force: Boolean,
        maxRetries: Int,
        recursive: Boolean,
        retryDelay: FiniteDuration
    ): F[Unit] =
      F.fromPromise(
        F.delay(
          fsPromisesMod.rm(
            path.toJS,
            fsMod
              .RmOptions()
              .setForce(force)
              .setMaxRetries(maxRetries.toDouble)
              .setRecursive(recursive)
              .setRetryDelay(retryDelay.toMillis.toDouble)
          )
        )
      )

    override def rmdir(path: Path, maxRetries: Int, retryDelay: FiniteDuration): F[Unit] =
      F.fromPromise(
        F.delay(
          fsPromisesMod.rmdir(
            path.toJS,
            fsMod
              .RmDirOptions()
              .setMaxRetries(maxRetries.toDouble)
              .setRetryDelay(retryDelay.toMillis.toDouble)
          )
        )
      )

    override def stat(path: Path): F[Stats] =
      F.fromPromise(F.delay(fsPromisesMod.stat(path.toJS))).map(new WrappedJSStats(_))

    override def unlink(path: Path): F[Unit] =
      F.fromPromise(F.delay(fsPromisesMod.unlink(path.toJS)))

    override def writeCursor(path: Path, flags: OpenMode): Resource[F, WriteCursor[F]] =
      open(path, flags | OpenMode.O_WRONLY).map(WriteCursor(_, 0L))

    override def writeAll(path: Path, flags: OpenMode): Pipe[F, Byte, INothing] =
      fromWritable(
        F.delay(
          fsMod
            .createWriteStream(path.toJS, fsMod.StreamOptions().setMode(flags.mode.toDouble))
            .asInstanceOf[streamMod.Writable]
        )
      )

  }

  private final class WrappedJSStats(private val stats: fsMod.Stats) extends Stats {
    override def isBlockDevice: Boolean = stats.isBlockDevice()
    override def isCharacterDevice: Boolean = stats.isCharacterDevice()
    override def isDirectory: Boolean = stats.isDirectory()
    override def isFIFO: Boolean = stats.isFIFO()
    override def isFile: Boolean = stats.isFile()
    override def isSocket: Boolean = stats.isSocket()
    override def isSymbolicLink: Boolean = stats.isSymbolicLink()
    override def dev: Long = stats.dev.toLong
    override def ino: Long = stats.ino.toLong
    override def nlink: Long = stats.nlink.toLong
    override def uid: Long = stats.uid.toLong
    override def gid: Long = stats.gid.toLong
    override def rdev: Long = stats.rdev.toLong
    override def mode: FileMode = FileMode(stats.mode.toLong)
    override def size: Long = stats.size.toLong
    override def blksize: Long = stats.blksize.toLong
    override def blocks: Long = stats.blocks.toLong
    override def atime: Instant = Instant.ofEpochMilli(stats.atimeMs.toLong)
    override def mtime: Instant = Instant.ofEpochMilli(stats.mtimeMs.toLong)
    override def ctime: Instant = Instant.ofEpochMilli(stats.ctimeMs.toLong)
    override def birthtime: Instant = Instant.ofEpochMilli(stats.birthtimeMs.toLong)
  }

}
