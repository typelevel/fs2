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

import cats.{Monad, ~>}
import cats.implicits._
import cats.effect.{Blocker, ContextShift, Resource, Sync}

import java.nio.file._
import cats.arrow.FunctionK

/**
  * Associates a `FileHandle` with an offset in to the file.
  *
  * This encapsulates the pattern of incrementally writing bytes in to a file,
  * a chunk at a time. Convenience methods are provided for working with pulls.
  */
final case class WriteCursor[F[_]](file: FileHandle[F], offset: Long) {

  /** Returns a new cursor with the offset adjusted to the specified position. */
  def seek(position: Long): WriteCursor[F] = WriteCursor(file, position)

  /**
    * Writes a single chunk to the underlying file handle, returning a new cursor
    * with an offset incremented by the chunk size.
    */
  def write(bytes: Chunk[Byte])(implicit F: Monad[F]): F[WriteCursor[F]] =
    write_[F](bytes, FunctionK.id[F])

  /**
    * Like `write` but returns a pull instead of an `F[WriteCursor[F]]`.
    */
  def writePull(bytes: Chunk[Byte]): Pull[F, Nothing, WriteCursor[F]] =
    write_(bytes, Pull.functionKInstance)

  private def write_[G[_]: Monad](bytes: Chunk[Byte], u: F ~> G): G[WriteCursor[F]] =
    u(file.write(bytes, offset)).flatMap { written =>
      val next = WriteCursor(file, offset + written)
      if (written == bytes.size) next.pure[G]
      else next.write_(bytes.drop(written), u)
    }

  /**
    * Writes all chunks from the supplied stream to the underlying file handle, returning a cursor
    * with offset incremented by the total number of bytes written.
    */
  def writeAll(s: Stream[F, Byte]): Pull[F, Nothing, WriteCursor[F]] =
    s.pull.uncons.flatMap {
      case Some((hd, tl)) => writePull(hd).flatMap(_.writeAll(tl))
      case None           => Pull.pure(this)
    }
}

object WriteCursor {

  /**
    * Returns a `WriteCursor` for the specified path.
    *
    * The `WRITE` option is added to the supplied flags. If the `APPEND` option is present in `flags`,
    * the offset is initialized to the current size of the file.
    */
  def fromPath[F[_]: Sync: ContextShift](
      path: Path,
      blocker: Blocker,
      flags: Seq[OpenOption] = List(StandardOpenOption.CREATE)
  ): Resource[F, WriteCursor[F]] =
    FileHandle.fromPath(path, blocker, StandardOpenOption.WRITE :: flags.toList).flatMap {
      fileHandle =>
        val size = if (flags.contains(StandardOpenOption.APPEND)) fileHandle.size else 0L.pure[F]
        val cursor = size.map(s => WriteCursor(fileHandle, s))
        Resource.liftF(cursor)
    }

  /**
    * Returns a `WriteCursor` for the specified file handle.
    *
    * If `append` is true, the offset is initialized to the current size of the file.
    */
  def fromFileHandle[F[_]: Sync: ContextShift](
      file: FileHandle[F],
      append: Boolean
  ): F[WriteCursor[F]] =
    if (append) file.size.map(s => WriteCursor(file, s)) else WriteCursor(file, 0L).pure[F]
}
