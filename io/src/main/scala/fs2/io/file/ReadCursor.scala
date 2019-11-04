package fs2
package io
package file

import scala.concurrent.duration.FiniteDuration

import cats.{Functor, ~>}
import cats.arrow.FunctionK
import cats.implicits._
import cats.effect.{Blocker, ContextShift, Resource, Sync, Timer}

import java.nio.file._

/**
  * Associates a `FileHandle` with an offset in to the file.
  *
  * This encapsulates the pattern of incrementally reading bytes in from a file,
  * a chunk at a time. Convenience methods are provided for working with pulls.
  */
final case class ReadCursor[F[_]](file: FileHandle[F], offset: Long) {

  /**
    * Reads a single chunk from the underlying file handle, returning the
    * read chunk and a new cursor with an offset incremented by the chunk size.
    */
  def read(chunkSize: Int)(implicit F: Functor[F]): F[Option[(ReadCursor[F], Chunk[Byte])]] =
    read_[F](chunkSize, FunctionK.id[F])

  /**
    * Like `read` but returns a pull instead of an `F[(ReadCursor[F], Option[Chunk[Byte]])]`.
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

  /**
    * Reads all chunks from the underlying file handle, returning a cursor
    * with offset incremented by the total number of bytes read.
    */
  def readAll(chunkSize: Int): Pull[F, Byte, ReadCursor[F]] =
    readPull(chunkSize).flatMap {
      case Some((next, chunk)) => Pull.output(chunk) >> next.readAll(chunkSize)
      case None                => Pull.pure(this)
    }

  /**
    * Reads chunks until the specified end position in the file. Returns a pull that outputs
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

  /**
    * Returns an infinite stream that reads until the end of the file and then starts
    * polling the file for additional writes. Similar to the `tail` command line utility.
    *
    * @param pollDelay amount of time to wait upon reaching the end of the file before
    * polling for updates
    */
  def tail(chunkSize: Int, pollDelay: FiniteDuration)(
      implicit timer: Timer[F]
  ): Pull[F, Byte, ReadCursor[F]] =
    readPull(chunkSize).flatMap {
      case Some((next, chunk)) => Pull.output(chunk) >> next.tail(chunkSize, pollDelay)
      case None                => Pull.eval(timer.sleep(pollDelay)) >> tail(chunkSize, pollDelay)
    }
}

object ReadCursor {

  /**
    * Returns a `ReadCursor` for the specified path. The `READ` option is added to the supplied flags.
    */
  def fromPath[F[_]: Sync: ContextShift](
      path: Path,
      blocker: Blocker,
      flags: Seq[OpenOption] = Nil
  ): Resource[F, ReadCursor[F]] =
    FileHandle.fromPath(path, blocker, StandardOpenOption.READ :: flags.toList).map { fileHandle =>
      ReadCursor(fileHandle, 0L)
    }

}
