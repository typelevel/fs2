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

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.effect.std.NonEmptyHotswap
import cats.syntax.all._

import scala.concurrent.duration._

/** Provides operations related to working with files in the effect `F`.
  *
  * An instance is available for any effect `F` which has an `Async[F]` instance.
  *
  * The operations on this trait are implemented for both the JVM and Node.js.
  * Some operations only work on POSIX file systems, though such methods generally
  * have "Posix" in their names (e.g. `getPosixPermissions`). A small number of methods
  * are only available on the JVM (e.g. variant of `list` which takes a glob pattern) and
  * are indicated as such in their ScalaDoc.
  */
sealed trait Files[F[_]] extends FilesPlatform[F] {

  /** Copies the source to the target, failing if source does not exist or the target already exists.
    * To replace the existing instead, use `copy(source, target, CopyFlags(CopyFlag.ReplaceExisting))`.
    */
  def copy(source: Path, target: Path): F[Unit] =
    copy(source, target, CopyFlags.empty)

  /** Copies the source to the target, following any directives supplied in the flags.
    * By default, an error occurs if the target already exists, though this can be overriden via `CopyFlag.ReplaceExisting`.
    */
  def copy(source: Path, target: Path, flags: CopyFlags): F[Unit]

  /** Creates the specified directory. Fails if the parent path does not already exist.
    */
  def createDirectory(path: Path): F[Unit] = createDirectory(path, None)

  /** Creates the specified directory with the specified permissions. Fails if the parent path does not already exist.
    */
  def createDirectory(path: Path, permissions: Option[Permissions]): F[Unit]

  /** Creates the specified directory and any non-existant parent directories. */
  def createDirectories(path: Path): F[Unit] = createDirectories(path, None)

  /** Creates the specified directory and any parent directories, using the supplied permissions for any directories
    * that get created as a result of this operation. For example if `/a` exists and
    * `createDirectories(Path("/a/b/c"), Some(p))` is called, `/a/b` and `/a/b/c` are created with permissions set
    * to `p` on each (and the permissions of `/a` remain unmodified).
    */
  def createDirectories(path: Path, permissions: Option[Permissions]): F[Unit]

  /** Creates the specified file. Fails if the parent path does not already exist.
    */
  def createFile(path: Path): F[Unit] = createFile(path, None)

  /** Creates the specified file with the specified permissions. Fails if the parent path does not already exist.
    */
  def createFile(path: Path, permissions: Option[Permissions]): F[Unit]

  /** Creates a hard link with an existing file. */
  def createLink(link: Path, existing: Path): F[Unit]

  /** Creates a symbolic link which points to the supplied target. */
  def createSymbolicLink(link: Path, target: Path): F[Unit] = createSymbolicLink(link, target, None)

  /** Creates a symbolic link which points to the supplied target. If defined, the supplied permissions are set on the created link. */
  def createSymbolicLink(link: Path, target: Path, permissions: Option[Permissions]): F[Unit]

  /** Creates a temporary file.
    * The created file is not automatically deleted - it is up to the operating system to decide when the file is deleted.
    * Alternatively, use `tempFile` to get a resource, which is deleted upon resource finalization.
    */
  def createTempFile: F[Path] = createTempFile(None, "", ".tmp", None)

  /** Creates a temporary file.
    * The created file is not automatically deleted - it is up to the operating system to decide when the file is deleted.
    * Alternatively, use `tempFile` to get a resource which deletes upon resource finalization.
    *
    * @param dir the directory which the temporary file will be created in. Pass none to use the default system temp directory
    * @param prefix the prefix string to be used in generating the file's name
    * @param suffix the suffix string to be used in generating the file's name
    * @param permissions permissions to set on the created file
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
    * @param dir the directory which the temporary directory will be created in. Pass none to use the default system temp directory
    * @param prefix the prefix string to be used in generating the directory's name
    * @param permissions permissions to set on the created directory
    */
  def createTempDirectory(
      dir: Option[Path],
      prefix: String,
      permissions: Option[Permissions]
  ): F[Path]

  /** User's current working directory */
  def currentWorkingDirectory: F[Path]

  /** Deletes the specified file or empty directory, failing if it does not exist. */
  def delete(path: Path): F[Unit]

  /** Deletes the specified file or empty directory, passing if it does not exist. */
  def deleteIfExists(path: Path): F[Boolean]

  /** Deletes the specified file or directory.
    * If the path is a directory and is non-empty, its contents are recursively deleted.
    * Symbolic links are not followed (but are deleted).
    */
  def deleteRecursively(
      path: Path
  ): F[Unit] = deleteRecursively(path, false)

  /** Deletes the specified file or directory.
    * If the path is a directory and is non-empty, its contents are recursively deleted.
    * Symbolic links are followed when `followLinks` is true.
    */
  def deleteRecursively(
      path: Path,
      followLinks: Boolean
  ): F[Unit]

  /** Returns true if the specified path exists.
    * Symbolic links are followed -- see the overload for more details on links.
    */
  def exists(path: Path): F[Boolean] = exists(path, true)

  /** Returns true if the specified path exists.
    * Symbolic links are followed when `followLinks` is true.
    * For example, if the symbolic link `foo` points to `bar` and `bar` does not exist,
    * `exists(Path("foo"), true)` returns `false` but `exists(Path("foo"), false)` returns `true`.
    */
  def exists(path: Path, followLinks: Boolean): F[Boolean]

  /** Gets `BasicFileAttributes` for the supplied path. Symbolic links are not followed. */
  def getBasicFileAttributes(path: Path): F[BasicFileAttributes] =
    getBasicFileAttributes(path, false)

  /** Gets `BasicFileAttributes` for the supplied path. Symbolic links are followed when `followLinks` is true. */
  def getBasicFileAttributes(path: Path, followLinks: Boolean): F[BasicFileAttributes]

  /** Gets the last modified time of the supplied path.
    * The last modified time is represented as a duration since the Unix epoch.
    * Symbolic links are followed.
    */
  def getLastModifiedTime(path: Path): F[FiniteDuration] = getLastModifiedTime(path, true)

  /** Gets the last modified time of the supplied path.
    * The last modified time is represented as a duration since the Unix epoch.
    * Symbolic links are followed when `followLinks` is true.
    */
  def getLastModifiedTime(path: Path, followLinks: Boolean): F[FiniteDuration]

  /** Gets the POSIX attributes for the supplied path.
    * Symbolic links are not followed.
    */
  def getPosixFileAttributes(path: Path): F[PosixFileAttributes] =
    getPosixFileAttributes(path, false)

  /** Gets the POSIX attributes for the supplied path.
    * Symbolic links are followed when `followLinks` is true.
    */
  def getPosixFileAttributes(path: Path, followLinks: Boolean): F[PosixFileAttributes]

  /** Gets the POSIX permissions of the supplied path.
    * Symbolic links are followed.
    */
  def getPosixPermissions(path: Path): F[PosixPermissions] = getPosixPermissions(path, true)

  /** Gets the POSIX permissions of the supplied path.
    * Symbolic links are followed when `followLinks` is true.
    */
  def getPosixPermissions(path: Path, followLinks: Boolean): F[PosixPermissions]

  /** Returns true if the supplied path exists and is a directory. Symbolic links are followed. */
  def isDirectory(path: Path): F[Boolean] = isDirectory(path, true)

  /** Returns true if the supplied path exists and is a directory. Symbolic links are followed when `followLinks` is true. */
  def isDirectory(path: Path, followLinks: Boolean): F[Boolean]

  /** Returns true if the supplied path exists and is executable. */
  def isExecutable(path: Path): F[Boolean]

  /** Returns true if the supplied path is a hidden file (note: may not check for existence). */
  def isHidden(path: Path): F[Boolean]

  /** Returns true if the supplied path exists and is readable. */
  def isReadable(path: Path): F[Boolean]

  /** Returns true if the supplied path is a regular file. Symbolic links are followed. */
  def isRegularFile(path: Path): F[Boolean] = isRegularFile(path, true)

  /** Returns true if the supplied path is a regular file. Symbolic links are followed when `followLinks` is true. */
  def isRegularFile(path: Path, followLinks: Boolean): F[Boolean]

  /** Returns true if the supplied path is a symbolic link. */
  def isSymbolicLink(path: Path): F[Boolean]

  /** Returns true if the supplied path exists and is writable. */
  def isWritable(path: Path): F[Boolean]

  /** Returns true if the supplied paths reference the same file. */
  def isSameFile(path1: Path, path2: Path): F[Boolean]

  /** Returns the line separator for the specific OS */
  def lineSeparator: String

  /** Gets the contents of the specified directory. */
  def list(path: Path): Stream[F, Path]

  /** Moves the source to the target, failing if source does not exist or the target already exists.
    * To replace the existing instead, use `move(source, target, CopyFlags(CopyFlag.ReplaceExisting))`.
    */
  def move(source: Path, target: Path): F[Unit] =
    move(source, target, CopyFlags.empty)

  /** Moves the source to the target, following any directives supplied in the flags.
    * By default, an error occurs if the target already exists, though this can be overriden via `CopyFlag.ReplaceExisting`.
    */
  def move(source: Path, target: Path, flags: CopyFlags): F[Unit]

  /** Creates a `FileHandle` for the file at the supplied `Path`.
    * The supplied flags indicate the mode used when opening the file (e.g. read, write, append)
    * as well as the ability to specify additional options (e.g. automatic deletion at process exit).
    */
  def open(path: Path, flags: Flags): Resource[F, FileHandle[F]]

  /** Reads all bytes from the file specified. */
  def readAll(path: Path): Stream[F, Byte] = readAll(path, 64 * 1024, Flags.Read)

  /** Reads all bytes from the file specified, reading in chunks up to the specified limit,
    * and using the supplied flags to open the file.
    */
  def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte]

  /** Returns a `ReadCursor` for the specified path, using the supplied flags when opening the file. */
  def readCursor(path: Path, flags: Flags): Resource[F, ReadCursor[F]] =
    open(path, flags.addIfAbsent(Flag.Read)).map { fileHandle =>
      ReadCursor(fileHandle, 0L)
    }

  /** Reads a range of data synchronously from the file at the specified path.
    * `start` is inclusive, `end` is exclusive, so when `start` is 0 and `end` is 2,
    * two bytes are read.
    */
  def readRange(path: Path, chunkSize: Int, start: Long, end: Long): Stream[F, Byte]

  /** Reads all bytes from the file specified and decodes them as a utf8 string. */
  def readUtf8(path: Path): Stream[F, String] = readAll(path).through(text.utf8.decode)

  /** Reads all bytes from the file specified and decodes them as utf8 lines. */
  def readUtf8Lines(path: Path): Stream[F, String] = readUtf8(path).through(text.lines)

  /** Returns the real path i.e. the actual location of `path`.
    * The precise definition of this method is implementation dependent but in general
    * it derives from this path, an absolute path that locates the same file as this path,
    * but with name elements that represent the actual name of the directories and the file.
    */
  def realPath(path: Path): F[Path]

  /** Sets the last modified, last access, and creation time fields of the specified path.
    *
    * Times which are supplied as `None` are not modified. E.g., `setTimes(p, Some(t), Some(t), None, false)`
    * sets the last modified and last access time to `t` and does not change the creation time.
    *
    * If the path is a symbolic link and `followLinks` is true, the target of the link as
    * times set. Otherwise, the link itself has times set.
    */
  def setFileTimes(
      path: Path,
      lastModified: Option[FiniteDuration],
      lastAccess: Option[FiniteDuration],
      creationTime: Option[FiniteDuration],
      followLinks: Boolean
  ): F[Unit]

  /** Sets the POSIX permissions for the supplied path. Fails on non-POSIX file systems. */
  def setPosixPermissions(path: Path, permissions: PosixPermissions): F[Unit]

  /** Gets the size of the supplied path, failing if it does not exist. */
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

  /** Creates a temporary file and deletes it upon finalization of the returned resource. */
  def tempFile: Resource[F, Path] = tempFile(None, "", ".tmp", None)

  /** Creates a temporary file and deletes it upon finalization of the returned resource.
    *
    * @param dir the directory which the temporary file will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the file's name
    * @param suffix the suffix string to be used in generating the file's name
    * @param permissions permissions to set on the created file
    * @return a resource containing the path of the temporary file
    */
  def tempFile(
      dir: Option[Path],
      prefix: String,
      suffix: String,
      permissions: Option[Permissions]
  ): Resource[F, Path]

  /** Creates a temporary directory and deletes it upon finalization of the returned resource.
    */
  def tempDirectory: Resource[F, Path] = tempDirectory(None, "", None)

  /** Creates a temporary directory and deletes it upon finalization of the returned resource.
    *
    * @param dir the directory which the temporary directory will be created in. Pass in None to use the default system temp directory
    * @param prefix the prefix string to be used in generating the directory's name
    * @param permissions permissions to set on the created file
    * @return a resource containing the path of the temporary directory
    */
  def tempDirectory(
      dir: Option[Path],
      prefix: String,
      permissions: Option[Permissions]
  ): Resource[F, Path]

  /** User's home directory */
  def userHome: F[Path]

  /** Creates a stream of paths contained in a given file tree. Depth is unlimited. */
  def walk(start: Path): Stream[F, Path] =
    walk(start, WalkOptions.Default)

  /** Creates a stream of paths contained in a given file tree.
    *
    * The `options` parameter allows for customizing the walk behavior. The `WalkOptions`
    * type provides both `WalkOptions.Default` and `WalkOptions.Eager` as starting points,
    * and further customizations can be specified via methods on the returned options value.
    * For example, to eagerly walk a directory while following symbolic links, emitting all
    * paths as a single chunk, use `walk(start, WalkOptions.Eager.withFollowLinks(true))`.
    */
  def walk(start: Path, options: WalkOptions): Stream[F, Path] =
    walkWithAttributes(start, options).map(_.path)

  /** Creates a stream of paths contained in a given file tree down to a given depth.
    */
  @deprecated("Use walk(start, WalkOptions.Default.withMaxDepth(..).withFollowLinks(..))", "3.10")
  def walk(start: Path, maxDepth: Int, followLinks: Boolean): Stream[F, Path] =
    walk(start, WalkOptions.Default.withMaxDepth(maxDepth).withFollowLinks(followLinks))

  /** Like `walk` but returns a `PathInfo`, which provides both the `Path` and `BasicFileAttributes`. */
  def walkWithAttributes(start: Path): Stream[F, PathInfo] =
    walkWithAttributes(start, WalkOptions.Default)

  /** Like `walk` but returns a `PathInfo`, which provides both the `Path` and `BasicFileAttributes`. */
  def walkWithAttributes(start: Path, options: WalkOptions): Stream[F, PathInfo]

  /** Writes all data to the file at the specified path.
    *
    * The file is created if it does not exist and is truncated.
    * Use `writeAll(path, Flags.Append)` to append to the end of
    * the file, or pass other flags to further customize behavior.
    */
  def writeAll(path: Path): Pipe[F, Byte, Nothing] = writeAll(path, Flags.Write)

  /** Writes all data to the file at the specified path, using the
    * specified flags to open the file.
    */
  def writeAll(path: Path, flags: Flags): Pipe[F, Byte, Nothing]

  /** Returns a `WriteCursor` for the specified path.
    */
  def writeCursor(path: Path, flags: Flags): Resource[F, WriteCursor[F]]

  /** Returns a `WriteCursor` for the specified file handle.
    *
    * If `append` is true, the offset is initialized to the current size of the file.
    */
  def writeCursorFromFileHandle(file: FileHandle[F], append: Boolean): F[WriteCursor[F]]

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
  ): Pipe[F, Byte, Nothing]

  /** Writes to the specified file as an utf8 string.
    *
    * The file is created if it does not exist and is truncated.
    * Use `writeUtf8(path, Flags.Append)` to append to the end of
    * the file, or pass other flags to further customize behavior.
    */
  def writeUtf8(path: Path): Pipe[F, String, Nothing] = writeUtf8(path, Flags.Write)

  /** Writes to the specified file as an utf8 string using
    * the specified flags to open the file.
    */
  def writeUtf8(path: Path, flags: Flags): Pipe[F, String, Nothing] = in =>
    in.through(text.utf8.encode).through(writeAll(path, flags))

  /** Writes each string to the specified file as utf8 lines.
    *
    * The file is created if it does not exist and is truncated.
    * Use `writeUtf8Lines(path, Flags.Append)` to append to the end
    * of the file, or pass other flags to further customize behavior.
    */
  def writeUtf8Lines(path: Path): Pipe[F, String, Nothing] = writeUtf8Lines(path, Flags.Write)

  /** Writes each string to the specified file as utf8 lines
    * using the specified flags to open the file.
    */
  def writeUtf8Lines(path: Path, flags: Flags): Pipe[F, String, Nothing] = in =>
    in.pull.uncons
      .flatMap {
        case Some((next, rest)) =>
          Stream
            .chunk(next)
            .append(rest)
            .intersperse(lineSeparator)
            .append(Stream[F, String](lineSeparator))
            .underlying
        case None => Pull.done
      }
      .stream
      .through(writeUtf8(path, flags))
}

private[fs2] trait FilesLowPriority { this: Files.type =>
  @deprecated("Add Files constraint or use forAsync", "3.7.0")
  implicit def implicitForAsync[F[_]: Async]: Files[F] = forAsync
}

object Files extends FilesCompanionPlatform with FilesLowPriority {
  def forIO: Files[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Async: LiftIO]: Files[F] = {
    val _ = LiftIO[F]
    forAsync
  }

  private[fs2] abstract class UnsealedFiles[F[_]](implicit F: Async[F]) extends Files[F] {

    def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte] =
      Stream.resource(readCursor(path, flags)).flatMap { cursor =>
        cursor.readAll(chunkSize).void.stream
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

    def writeAll(
        path: Path,
        flags: Flags
    ): Pipe[F, Byte, Nothing] =
      in =>
        Stream
          .resource(writeCursor(path, flags))
          .flatMap(_.writeAll(in).void.stream)

    def writeCursor(
        path: Path,
        flags: Flags
    ): Resource[F, WriteCursor[F]] =
      open(path, flags.addIfAbsent(Flag.Write)).flatMap { fileHandle =>
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
    ): Pipe[F, Byte, Nothing] = {
      def openNewFile: Resource[F, FileHandle[F]] =
        Resource
          .eval(computePath)
          .flatMap(p => open(p, flags.addIfAbsent(Flag.Write)))

      def newCursor(file: FileHandle[F]): F[WriteCursor[F]] =
        writeCursorFromFileHandle(file, flags.contains(Flag.Append))

      def go(
          fileHotswap: NonEmptyHotswap[F, FileHandle[F]],
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
                      .flatMap(_ => fileHotswap.get.use(newCursor))
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
          .resource(NonEmptyHotswap(openNewFile))
          .flatMap { fileHotswap =>
            Stream.eval(fileHotswap.get.use(newCursor)).flatMap { cursor =>
              go(fileHotswap, cursor, 0L, in).stream.drain
            }
          }
    }
  }

  def apply[F[_]](implicit F: Files[F]): Files[F] = F
}
