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

package fs2.io.internal.facade

import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

package object fs {

  @js.native
  @JSImport("fs", "constants")
  private[io] def constants: FsConstants = js.native

  @js.native
  @JSImport("fs", "promises")
  private[io] def promises: FsPromises = js.native

  @js.native
  @JSImport("fs", "createReadStream")
  @nowarn
  private[io] def createReadStream(path: String, options: ReadStreamOptions): fs2.io.Readable =
    js.native

  @js.native
  @JSImport("fs", "createWriteStream")
  @nowarn
  private[io] def createWriteStream(path: String, options: WriteStreamOptions): fs2.io.Writable =
    js.native

}

package fs {

  private[io] trait ReadStreamOptions extends js.Object {

    var flags: js.UndefOr[Double] = js.undefined

    var highWaterMark: js.UndefOr[Int] = js.undefined

    var start: js.UndefOr[Double] = js.undefined

    var end: js.UndefOr[Double] = js.undefined

  }

  private[io] trait WriteStreamOptions extends js.Object {

    var flags: js.UndefOr[Double] = js.undefined

  }

  @js.native
  @nowarn
  private[io] trait FsConstants extends js.Object {

    val COPYFILE_EXCL: Double = js.native

    val COPYFILE_FICLONE: Double = js.native

    val COPYFILE_FICLONE_FORCE: Double = js.native

    val R_OK: Double = js.native

    val W_OK: Double = js.native

    val X_OK: Double = js.native

    val O_RDONLY: Double = js.native

    val O_WRONLY: Double = js.native

    val O_APPEND: Double = js.native

    val O_TRUNC: Double = js.native

    val O_CREAT: Double = js.native

    val O_EXCL: Double = js.native

    val O_SYNC: Double = js.native

    val O_DSYNC: Double = js.native

  }

  @js.native
  @nowarn
  private[io] trait FsPromises extends js.Object {

    def access(path: String, mode: Double = js.native): js.Promise[Unit] = js.native

    def chmod(path: String, mode: Double): js.Promise[Unit] = js.native

    def copyFile(src: String, dest: String, mode: Double): js.Promise[Unit] = js.native

    def lchmod(path: String, mode: Double): js.Promise[Unit] = js.native

    def lstat(path: String): js.Promise[Stats] = js.native

    def mkdir(path: String, options: MkdirOptions): js.Promise[js.UndefOr[String]] = js.native

    def open(path: String, flags: Double, mode: Double = js.native): js.Promise[FileHandle] =
      js.native

    def opendir(path: String): js.Promise[Dir] = js.native

    def mkdtemp(prefix: String): js.Promise[String] = js.native

    def realpath(path: String): js.Promise[String] = js.native

    def rename(oldPath: String, newPath: String): js.Promise[Unit] = js.native

    def rm(path: String, options: RmOptions = js.native): js.Promise[Unit] = js.native

    def rmdir(path: String): js.Promise[Unit] = js.native

    def stat(path: String): js.Promise[Stats] = js.native

    def symlink(target: String, path: String): js.Promise[Unit] = js.native

    def utimes(path: String, atime: Double, mtime: Double): js.Promise[Unit] = js.native

  }

  private[io] trait MkdirOptions extends js.Object {

    var recursive: js.UndefOr[Boolean] = js.undefined

    var mode: js.UndefOr[Double] = js.undefined

  }

  private[io] trait RmOptions extends js.Object {

    var force: js.UndefOr[Boolean] = js.undefined

    var recursive: js.UndefOr[Boolean] = js.undefined

  }

  @js.native
  private[io] trait Dir extends js.Object {

    def close(): js.Promise[Unit] = js.native

    def read(): js.Promise[DirEnt] = js.native

  }

  @js.native
  private[io] trait DirEnt extends js.Object {

    def name: String = js.native

  }

  @js.native
  private[io] trait Stats extends js.Object {

    def dev: Double = js.native

    def ino: Double = js.native

    def mode: Double = js.native

    def size: Double = js.native

    def atimeMs: Double = js.native

    def ctimeMs: Double = js.native

    def mtimeMs: Double = js.native

    def isFile(): Boolean = js.native

    def isDirectory(): Boolean = js.native

    def isSymbolicLink(): Boolean = js.native

  }

  @js.native
  @nowarn
  private[io] trait FileHandle extends js.Object {

    def datasync(): js.Promise[Unit] = js.native

    def read(
        buffer: Uint8Array,
        offset: Int,
        length: Int,
        position: Double
    ): js.Promise[FileHandleReadResult] = js.native

    def write(
        buffer: Uint8Array,
        offset: Int,
        length: Int,
        position: Double
    ): js.Promise[FileHandleWriteResult] = js.native

    def stat(): js.Promise[Stats] = js.native

    def truncate(len: Double): js.Promise[Unit] = js.native

    def close(): js.Promise[Unit] = js.native

  }

  @js.native
  private[io] trait FileHandleReadResult extends js.Object {

    def bytesRead: Int = js.native

    def buffer: Uint8Array = js.native

  }

  @js.native
  private[io] trait FileHandleWriteResult extends js.Object {

    def bytesWritten: Int = js.native

    def buffer: Uint8Array = js.native

  }
}
