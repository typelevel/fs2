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

import scala.concurrent.duration.FiniteDuration

import cats.{Functor, ~>}
import cats.arrow.FunctionK
import cats.syntax.all._
import cats.effect.{Resource, Sync, Temporal}

import java.nio.file._

/** Associates a `FileHandle` with an offset in to the file.
  *
  * This encapsulates the pattern of incrementally reading bytes in from a file,
  * a chunk at a time. Convenience methods are provided for working with pulls.
  */
final case class ReadCursor[F[_]](file: FileHandle[F], offset: Long) {

  /** Reads a single chunk from the underlying file handle, returning the
    * read chunk and a new cursor with an offset incremented by the chunk size.
    */
  def read(chunkSize: Int)(implicit F: Functor[F]): F[Option[(ReadCursor[F], Chunk[Byte])]] =
    read_[F](chunkSize, FunctionK.id[F])

  /** Like `read` but returns a pull instead of an `F[(ReadCursor[F], Option[Chunk[Byte]])]`.
    */
  def readPull(chunkSize: Int): Pull[F, Nothing, Option[(ReadCursor[F], Chunk[Byte])]] =
    read_(chunkSize, Pull.functionKInstance)

  private def read_[G[_]: Functor](
      chunkSize: Int,
      u: F ~> G
  ): G[Option[(ReadCursor[F], Chunk[Byte])]] =
    u(file.read(chunkSize, offset)).map {
      _.map { chunk =>
        val next = ReadCursor(file, offset + chunk.size)
        (next, chunk)
      }
    }

  /** Reads all chunks from the underlying file handle, returning a cursor
    * with offset incremented by the total number of bytes read.
    */
  def readAll(chunkSize: Int): Pull[F, Byte, ReadCursor[F]] =
    readPull(chunkSize).flatMap {
      case Some((next, chunk)) => Pull.output(chunk) >> next.readAll(chunkSize)
      case None                => Pull.pure(this)
    }

  /** Reads chunks until the specified end position in the file. Returns a pull that outputs
    * the read chunks and completes with a cursor with offset incremented by the total number
    * of bytes read.
    */
  def readUntil(chunkSize: Int, end: Long): Pull[F, Byte, ReadCursor[F]] =
    if (offset < end) {
      val toRead = ((end - offset).min(Int.MaxValue).toInt).min(chunkSize)
      readPull(toRead).flatMap {
        case Some((next, chunk)) => Pull.output(chunk) >> next.readUntil(chunkSize, end)
        case None                => Pull.pure(this)
      }
    } else Pull.pure(this)

  /** Returns a new cursor with the offset adjusted to the specified position. */
  def seek(position: Long): ReadCursor[F] = ReadCursor(file, position)

  /** Returns an infinite stream that reads until the end of the file and then starts
    * polling the file for additional writes. Similar to the `tail` command line utility.
    *
    * @param pollDelay amount of time to wait upon reaching the end of the file before
    * polling for updates
    */
  def tail(chunkSize: Int, pollDelay: FiniteDuration)(implicit
      t: Temporal[F]
  ): Pull[F, Byte, ReadCursor[F]] =
    readPull(chunkSize).flatMap {
      case Some((next, chunk)) => Pull.output(chunk) >> next.tail(chunkSize, pollDelay)
      case None                => Pull.eval(t.sleep(pollDelay)) >> tail(chunkSize, pollDelay)
    }
}

object ReadCursor {

  @deprecated("Use Files[F].readCursor", "3.0.0")
  def fromPath[F[_]: Sync](
      path: Path,
      flags: Seq[OpenOption] = Nil
  ): Resource[F, ReadCursor[F]] =
    SyncFiles[F].readCursor(path, flags)
}
