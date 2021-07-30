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
package file2

import cats.effect.kernel.{Async, Resource, Sync}
import cats.syntax.all._

import java.nio.file.{Path => JPath}
import java.nio.channels.FileChannel

private[fs2] trait FilesPlatform {

  implicit def forAsync[F[_]](implicit F: Async[F]): Files[F] =
    new NioFiles[F]

  private final class NioFiles[F[_]: Async] extends Files.UnsealedFiles[F] {
    private def toJPath(path: Path): JPath = path.toNioPath

    def readAll(path: Path, chunkSize: Int, flags: Flags): Stream[F, Byte] =
      Stream.resource(readCursor(path, flags)).flatMap { cursor =>
        cursor.readAll(chunkSize).void.stream
      }

    def readCursor(path: Path, flags: Flags): Resource[F, ReadCursor[F]] =
      open(path, flags).map { fileHandle =>
        ReadCursor(fileHandle, 0L)
      }

    def open(path: Path, flags: Flags): Resource[F, FileHandle[F]] =
      openFileChannel(
        Sync[F].blocking(FileChannel.open(toJPath(path), flags.value.map(_.option): _*))
      )

    def openFileChannel(channel: F[FileChannel]): Resource[F, FileHandle[F]] =
      Resource.make(channel)(ch => Sync[F].blocking(ch.close())).map(ch => FileHandle.make(ch))

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
  }
}
