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
package io.file

import scala.concurrent.duration._
import java.time.Instant
import cats.effect.kernel.Resource

sealed trait PosixFiles[F[_]] {
  
  import PosixFiles._

  def access(path: Path, mode: AccessMode = AccessMode.F_OK): F[Boolean]

  def chmod(path: Path, mode: FileAccessMode): F[Unit]

  def chown(path: Path, uid: Long, guid: Long): F[Unit]

  def copyFile(src: Path, dest: Path, mode: CopyMode = CopyMode.Default): F[Unit]

  def lchown(path: Path, uid: Long, guid: Long): F[Unit]

  def link(existingPath: Path, newPath: Path): F[Unit]
  
  def lstat(path: Path): F[Stats]

  def mkdir(path: Path, recursive: Boolean = false, mode: FileAccessMode = FileAccessMode.Default): F[Path]

  def mkdtemp(prefix: Path): F[Path]

  def open(path: Path, flags: OpenMode = OpenMode.O_RDONLY, mode: FileAccessMode = FileAccessMode.OpenDefault): F[FileHandle[F]]

  def opendir(path: Path): Stream[F, Path]

  def readCursor(path: Path, flags: OpenMode = OpenMode.O_RDONLY): Resource[F, ReadCursor[F]]

  def readFile(path: Path, flags: OpenMode = OpenMode.O_RDONLY): Stream[F, Byte]

  def readLink(path: Path): F[Path]

  def realpath(path: Path): F[Path]

  def rename(oldPath: Path, newPath: Path): F[Unit]

  def rm(path: Path, force: Boolean = false, maxRetries: Int = 0, recursive: Boolean = false, retryDelay: FiniteDuration = 100.milliseconds): F[Unit]

  def rmdir(path: Path, maxRetries: Int = 0, retryDelay: FiniteDuration = 100.milliseconds): F[Unit]

  def stat(path: Path): F[Stats]

  def unlink(path: Path): F[Unit]

  def writeCursor(path: Path, flags: OpenMode = OpenMode.O_WRONLY | OpenMode.O_CREAT): Resource[F, WriteCursor[F]]

  def writeFile(path: Path, flags: OpenMode = OpenMode.O_WRONLY | OpenMode.O_CREAT): Pipe[F, Byte, INothing]

}

object PosixFiles {

  final class AccessMode private (private val mode: Int) extends AnyVal {
    def | (that: AccessMode) = AccessMode(this.mode | that.mode)
  }
  object AccessMode {
    private def apply(mode: Int) = new AccessMode(mode)
    val F_OK = AccessMode(0)
    val R_OK = AccessMode(1 << 2)
    val W_OK = AccessMode(1 << 1)
    val X_OK = AccessMode(1 << 0)
  }

  final class CopyMode private (private val mode: Int) extends AnyVal {
    def | (that: CopyMode) = CopyMode(this.mode | that.mode)
  }
  object CopyMode {
    def apply(mode: Int) = new CopyMode(mode)
    val COPYFILE_EXCL = CopyMode(1)
    val COPYFILE_FICLONE = CopyMode(2)
    val COPYFILE_FICLONE_FORCE = CopyMode(4)

    private[file] val Default = CopyMode(0)
  }

  final class OpenMode private (private val mode: Int) extends AnyVal {
    def | (that: OpenMode) = OpenMode(this.mode | that.mode)
  }
  object OpenMode {
    private def apply(mode: Int) = new OpenMode(mode)
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

  final class FileMode private (private val mode: Int) extends AnyVal {
    def | (that: FileMode) = FileMode(this.mode | that.mode)
    def `type`: FileTypeMode = FileTypeMode(FileTypeMode.S_IFMT.mode & mode)
    def access: FileAccessMode = FileAccessMode(~FileTypeMode.S_IFMT.mode & mode)
  }
  object FileMode {
    def apply(`type`: FileTypeMode, access: FileAccessMode): FileMode =
      apply(`type`.mode | access.mode)
    private def apply(mode: Int) = new FileMode(mode)
  }

  final class FileTypeMode private (private[file] val mode: Int) extends AnyVal {
    def | (that: FileTypeMode) = FileTypeMode(this.mode | that.mode)
  }

  object FileTypeMode {
    private[file] def apply(mode: Int) = new FileTypeMode(mode)
    val S_IFREG = FileTypeMode(1 << 15)
    val S_IFDIR = FileTypeMode(1 << 14)
    val S_IFCHR = FileTypeMode(1 << 13)
    val S_IFBLK = S_IFDIR | S_IFCHR
    val S_IFIFO = FileTypeMode(1 << 12)
    val S_IFLNK = S_IFREG | S_IFCHR
    val S_IFSOCK = S_IFREG | S_IFDIR
    val S_IFMT = S_IFREG | S_IFDIR | S_IFCHR | S_IFIFO
  }

  final class FileAccessMode private (private[file] val mode: Int) extends AnyVal {
    def | (that: FileAccessMode) = FileAccessMode(this.mode | that.mode)
  }
  object FileAccessMode {
    private[file] def apply(mode: Int) = new FileAccessMode(mode)
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
    def mode: Set[FileMode]
    def size: Long
    def blksize: Long
    def blocks: Long
    def atime: Instant
    def mtime: Instant
    def ctime: Instant
    def birthtime: Instant
  }

}
