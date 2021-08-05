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

import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.Hotswap
import cats.syntax.all._

import scala.concurrent.duration._

/** Provides operations related to working with files in the effect `F`.
  *
  * An instance is available for any effect `F` which has an `Async[F]` instance.
  */
sealed trait Files[F[_]] extends FilesPlatform[F] {

  def copy(source: Path, target: Path): F[Unit] =
    copy(source, target, CopyFlags.empty)

  def copy(source: Path, target: Path, flags: CopyFlags): F[Unit]

  def createDirectory(path: Path): F[Unit] = createDirectory(path, None)

  def createDirectory(path: Path, permissions: Option[Permissions]): F[Unit]

  def createDirectories(path: Path): F[Unit] = createDirectories(path, None)

  def createDirectories(path: Path, permissions: Option[Permissions]): F[Unit]

  /** Creates a temporary file.
    * The created file is not automatically deleted - it is up to the operating system to decide when the file is deleted.
    * Alternatively, use `tempFile` to get a resource, which is deleted upon resource finalization.
    */
  def createTempFile: F[Path] = createTempFile(None, "", ".tmp", None)

  /** Creates a temporary file.
    * The created file is not automatically deleted - it is up to the operating system to decide when the file is deleted.
    * Alternatively, use `tempFile` to get a resource which deletes upon resource finalization.
    *
    * @param dir the directory which the temporary file will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the file's name
    * @param suffix the suffix string to be used in generating the file's name
    * @param attributes an optional list of file attributes to set atomically when creating the file
    */
  def createTempFile(
      dir: Option[Path],
      prefix: String,
      suffix: String,
      permissions: Option[Permissions]
  ): F[Path]

  /** Creates a temporary directory.
    * The created directory is not automatically deleted - it is up to the operating system to decide when the file is deleted.
    * Alternatively, use `tempDirectory` to get a resource which deletes upon resource finalization.
    */
  def createTempDirectory: F[Path] = createTempDirectory(None, "", None)

  /** Creates a temporary directory.
    * The created directory is not automatically deleted - it is up to the operating system to decide when the file is deleted.
    * Alternatively, use `tempDirectory` to get a resource which deletes upon resource finalization.
    *
    * @param dir the directory which the temporary directory will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the directory's name
    * @param attributes an optional list of file attributes to set atomically when creating the directory
    */
  def createTempDirectory(
      dir: Option[Path],
      prefix: String,
      permissions: Option[Permissions]
  ): F[Path]

  def delete(path: Path): F[Unit]

  def deleteIfExists(path: Path): F[Boolean]

  def deleteRecursively(
      path: Path
  ): F[Unit] = deleteRecursively(path, false)

  def deleteRecursively(
      path: Path,
      followLinks: Boolean
  ): F[Unit]

  def exists(path: Path): F[Boolean] = exists(path, true)

  def exists(path: Path, followLinks: Boolean): F[Boolean]

  def getBasicFileAttributes(path: Path): F[BasicFileAttributes] =
    getBasicFileAttributes(path, false)
  def getBasicFileAttributes(path: Path, followLinks: Boolean): F[BasicFileAttributes]

  def getLastModifiedTime(path: Path): F[FiniteDuration] = getLastModifiedTime(path, true)

  def getLastModifiedTime(path: Path, followLinks: Boolean): F[FiniteDuration]

  /** Gets the POSIX permissions of the specified file. */
  def getPosixPermissions(path: Path): F[PosixPermissions] = getPosixPermissions(path, true)

  /** Gets the POSIX permissions of the specified file. */
  def getPosixPermissions(path: Path, followLinks: Boolean): F[PosixPermissions]

  def isDirectory(path: Path): F[Boolean] = isDirectory(path, true)
  def isDirectory(path: Path, followLinks: Boolean): F[Boolean]
  def isExecutable(path: Path): F[Boolean]
  def isHidden(path: Path): F[Boolean]
  def isReadable(path: Path): F[Boolean]
  def isRegularFile(path: Path): F[Boolean] = isRegularFile(path, true)
  def isRegularFile(path: Path, followLinks: Boolean): F[Boolean]
  def isSymbolicLink(path: Path): F[Boolean]
  def isWritable(path: Path): F[Boolean]

  /** Gets the contents of the specified directory.
    */
  def list(path: Path): Stream[F, Path]

  def move(source: Path, target: Path): F[Unit] =
    move(source, target, CopyFlags.empty)

  def move(source: Path, target: Path, flags: CopyFlags): F[Unit]

  /** Creates a `FileHandle` for the file at the supplied `Path`. */
  def open(path: Path, flags: Flags): Resource[F, FileHandle[F]]

  /** Reads all bytes from the file specified. */
  def readAll(path: Path): Stream[F, Byte] = readAll(path, 64 * 1024, Flags.Read)

  /** Reads all bytes from the file specified, reading in chunks up to the specified limit,
    * and using the supplied flags to open the file. The flags must contain `Read`.
    */
  def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte]

  /** Returns a `ReadCursor` for the specified path. */
  def readCursor(path: Path, flags: Flags): Resource[F, ReadCursor[F]]

  /** Reads a range of data synchronously from the file at the specified path.
    * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
    * two bytes are read.
    */
  def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte]

  def setLastModifiedTime(path: Path, timestamp: FiniteDuration): F[Unit]

  def setPosixPermissions(path: Path, permissions: PosixPermissions): F[Unit]

  def size(path: Path): F[Long]

  /** Returns an infinite stream of data from the file at the specified path.
    * Starts reading from the specified offset and upon reaching the end of the file,
    * polls every `pollDuration` for additional updates to the file.
    *
    * Read operations are limited to emitting chunks of the specified chunk size
    * but smaller chunks may occur.
    *
    * If an error occurs while reading from the file, the overall stream fails.
    */
  def tail(
      path: Path,
      chunkSize: Int = 64 * 1024,
      offset: Long = 0L,
      pollDelay: FiniteDuration = 1.second
  ): Stream[F, Byte]

  /** Creates a temporary file and deletes it upon finalization of the returned resource.
    *
    * @param dir the directory which the temporary file will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the file's name
    * @param suffix the suffix string to be used in generating the file's name
    * @param attributes an optional list of file attributes to set atomically when creating the file
    * @return a resource containing the path of the temporary file
    */
  def tempFile: Resource[F, Path] = tempFile(None, "", ".tmp", None)

  /** Creates a temporary file and deletes it upon finalization of the returned resource.
    *
    * @param dir the directory which the temporary file will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the file's name
    * @param suffix the suffix string to be used in generating the file's name
    * @param attributes an optional list of file attributes to set atomically when creating the file
    * @return a resource containing the path of the temporary file
    */
  def tempFile(
      dir: Option[Path],
      prefix: String,
      suffix: String,
      permissions: Option[Permissions]
  ): Resource[F, Path]

  /** Creates a temporary directory and deletes it upon finalization of the returned resource.
    * @return a resource containing the path of the temporary directory
    */
  def tempDirectory: Resource[F, Path] = tempDirectory(None, "", None)

  /** Creates a temporary directory and deletes it upon finalization of the returned resource.
    *
    * @param dir the directory which the temporary directory will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the directory's name
    * @param attributes an optional list of file attributes to set atomically when creating the directory
    * @return a resource containing the path of the temporary directory
    */
  def tempDirectory(
      dir: Option[Path],
      prefix: String,
      permissions: Option[Permissions]
  ): Resource[F, Path]

  /** Creates a stream of paths contained in a given file tree. Depth is unlimited.
    */
  def walk(start: Path): Stream[F, Path] =
    walk(start, Int.MaxValue, false)

  /** Creates a stream of paths contained in a given file tree down to a given depth.
    */
  def walk(start: Path, maxDepth: Int, followLinks: Boolean): Stream[F, Path]

  /** Writes all data to the file at the specified path.
    *
    * The file is created if it does not exist and is truncated.
    * Use `writeAll(path, Flags.Append)` to append to the end of
    * the file, or pass other flags to further customize behavior.
    */
  def writeAll(
      path: Path
  ): Pipe[F, Byte, INothing] = writeAll(path, Flags.Write)

  /** Writes all data to the file at the specified path, using the
    * specified flags to open the file. The flags must include either
    * `Write` or `Append` or an error will occur.
    */
  def writeAll(
      path: Path,
      flags: Flags
  ): Pipe[F, Byte, INothing]

  /** Returns a `WriteCursor` for the specified path.
    *
    * The flags must include either `Write` or `Append` or an error will occur.
    */
  def writeCursor(
      path: Path,
      flags: Flags
  ): Resource[F, WriteCursor[F]]

  /** Returns a `WriteCursor` for the specified file handle.
    *
    * If `append` is true, the offset is initialized to the current size of the file.
    */
  def writeCursorFromFileHandle(
      file: FileHandle[F],
      append: Boolean
  ): F[WriteCursor[F]]

  /** Writes all data to a sequence of files, each limited in size to `limit`.
    *
    * The `computePath` operation is used to compute the path of the first file
    * and every subsequent file. Typically, the next file should be determined
    * by analyzing the current state of the filesystem -- e.g., by looking at all
    * files in a directory and generating a unique name.
    */
  def writeRotate(
      computePath: F[Path],
      limit: Long,
      flags: Flags
  ): Pipe[F, Byte, INothing]
}

object Files extends FilesCompanionPlatform {
  private[file] abstract class UnsealedFiles[F[_]: Async] extends Files[F] {

    def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte] =
      Stream.resource(readCursor(path, flags)).flatMap { cursor =>
        cursor.readAll(chunkSize).void.stream
      }

    def readCursor(path: Path, flags: Flags): Resource[F, ReadCursor[F]] =
      open(path, flags).map { fileHandle =>
        ReadCursor(fileHandle, 0L)
      }

    def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte] =
      Stream.resource(readCursor(path, Flags.Read)).flatMap { cursor =>
        cursor.seek(start).readUntil(chunkSize, end).void.stream
      }

    def tail(
        path: Path,
        chunkSize: Int,
        offset: Long,
        pollDelay: FiniteDuration
    ): Stream[F, Byte] =
      Stream.resource(readCursor(path, Flags.Read)).flatMap { cursor =>
        cursor.seek(offset).tail(chunkSize, pollDelay).void.stream
      }

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

    def walk(start: Path, maxDepth: Int, followLinks: Boolean): Stream[F, Path] = {

      def go(start: Path, maxDepth: Int, ancestry: List[Option[FileKey]]): Stream[F, Path] =
        if (maxDepth == 0)
          Stream.eval(exists(start, followLinks)).as(start)
        else
          Stream.eval(getBasicFileAttributes(start, followLinks = false)).flatMap { attr =>
            (if (attr.isDirectory)
               list(start)
                 .flatMap { path =>
                   go(path, maxDepth - 1, attr.fileKey :: ancestry)
                 }
                 .recoverWith { case _ =>
                   Stream.empty
                 }
             else if (attr.isSymbolicLink && followLinks)
               Stream.eval(getBasicFileAttributes(start, followLinks = true)).flatMap { attr =>
                 if (!ancestry.contains(attr.fileKey))
                   list(start)
                     .flatMap { path =>
                       go(path, maxDepth - 1, attr.fileKey :: ancestry)
                     }
                     .recoverWith { case _ =>
                       Stream.empty
                     }
                 else
                   Stream.raiseError(new FileSystemLoopException(start.toString))
               }
             else
               Stream.empty) ++ Stream.emit(start)
          }

      Stream.eval(getBasicFileAttributes(start, followLinks)) >> go(start, maxDepth, Nil)
    }

    def writeAll(
        path: Path,
        flags: Flags
    ): Pipe[F, Byte, INothing] =
      in =>
        Stream
          .resource(writeCursor(path, flags))
          .flatMap(_.writeAll(in).void.stream)

    def writeCursor(
        path: Path,
        flags: Flags
    ): Resource[F, WriteCursor[F]] =
      open(path, flags).flatMap { fileHandle =>
        val size = if (flags.contains(Flag.Append)) fileHandle.size else 0L.pure[F]
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
        flags: Flags
    ): Pipe[F, Byte, INothing] = {
      def openNewFile: Resource[F, FileHandle[F]] =
        Resource
          .eval(computePath)
          .flatMap(p => open(p, flags))

      def newCursor(file: FileHandle[F]): F[WriteCursor[F]] =
        writeCursorFromFileHandle(file, flags.contains(Flag.Append))

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

  def apply[F[_]](implicit F: Files[F]): Files[F] = F
}
