package fs2
package io
package file

import cats.effect.Timer

import scala.concurrent.duration.FiniteDuration

/** Provides various `Pull`s for working with files. */
object pulls {

  /**
    * Given a `FileHandle[F]`, creates a `Pull` which reads all data from the associated file.
    */
  def readAllFromFileHandle[F[_]](chunkSize: Int)(h: FileHandle[F]): Pull[F, Byte, Unit] =
    _readAllFromFileHandle0(chunkSize, 0)(h)

  def readRangeFromFileHandle[F[_]](chunkSize: Int, start: Long, end: Long)(
      h: FileHandle[F]
  ): Pull[F, Byte, Unit] =
    _readRangeFromFileHandle0(chunkSize, start, end)(h)

  def tailFromFileHandle[F[_]: Timer](chunkSize: Int, offset: Long, delay: FiniteDuration)(
      h: FileHandle[F]
  ): Pull[F, Byte, Unit] =
    _tailFromFileHandle(chunkSize, offset, delay)(h)

  private def _tailFromFileHandle[F[_]](chunkSize: Int, offset: Long, delay: FiniteDuration)(
      h: FileHandle[F]
  )(implicit timer: Timer[F]): Pull[F, Byte, Unit] =
    ReadCursor(h, offset).tail(chunkSize, delay).void

  private def _readRangeFromFileHandle0[F[_]](chunkSize: Int, offset: Long, end: Long)(
      h: FileHandle[F]
  ): Pull[F, Byte, Unit] =
    ReadCursor(h, offset).readUntil(chunkSize, end).void

  private def _readAllFromFileHandle0[F[_]](chunkSize: Int, offset: Long)(
      h: FileHandle[F]
  ): Pull[F, Byte, Unit] =
    ReadCursor(h, offset).readAll(chunkSize).void

  /**
    * Given a `Stream[F, Byte]` and `FileHandle[F]`, writes all data from the stream to the file.
    */
  def writeAllToFileHandle[F[_]](in: Stream[F, Byte], out: FileHandle[F]): Pull[F, Nothing, Unit] =
    writeAllToFileHandleAtOffset(in, out, 0)

  /** Like `writeAllToFileHandle` but takes an offset in to the file indicating where write should start. */
  def writeAllToFileHandleAtOffset[F[_]](
      in: Stream[F, Byte],
      out: FileHandle[F],
      offset: Long
  ): Pull[F, Nothing, Unit] =
    WriteCursor(out, offset).writeAll(in).void
}
