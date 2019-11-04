package fs2
package io
package file

import cats.effect.Timer

import scala.concurrent.duration.FiniteDuration

/**Provides various `Pull`s for working with files. */
@deprecated("Use ReadCursor/WriteCursor instead", "2.1.0")
object pulls {
  /**
    * Given a `FileHandle[F]`, creates a `Pull` which reads all data from the associated file.
    */
  @deprecated("Use ReadCursor(h, 0L).readAll(chunkSize).void", "2.1.0")
  def readAllFromFileHandle[F[_]](chunkSize: Int)(h: FileHandle[F]): Pull[F, Byte, Unit] =
    ReadCursor(h, 0L).readAll(chunkSize).void

  @deprecated("Use ReadCursor(h, start).readUntil(chunkSize, end).void", "2.1.0")
  def readRangeFromFileHandle[F[_]](chunkSize: Int, start: Long, end: Long)(
      h: FileHandle[F]
  ): Pull[F, Byte, Unit] =
    ReadCursor(h, start).readUntil(chunkSize, end).void

  @deprecated("Use ReadCursor(h, offset).tail(chunkSize, delay).void", "2.1.0")
  def tailFromFileHandle[F[_]: Timer](chunkSize: Int, offset: Long, delay: FiniteDuration)(
      h: FileHandle[F]
  ): Pull[F, Byte, Unit] =
    ReadCursor(h, offset).tail(chunkSize, delay).void

  /**
    * Given a `Stream[F, Byte]` and `FileHandle[F]`, writes all data from the stream to the file.
    */
  @deprecated("Use WriteCursor(out, 0).writeAll(in).void", "2.1.0")
  def writeAllToFileHandle[F[_]](in: Stream[F, Byte], out: FileHandle[F]): Pull[F, Nothing, Unit] =
    writeAllToFileHandleAtOffset(in, out, 0)

  /** Like `writeAllToFileHandle` but takes an offset in to the file indicating where write should start. */
  @deprecated("Use WriteCursor(out, offset).writeAll(in).void", "2.1.0")
  def writeAllToFileHandleAtOffset[F[_]](
      in: Stream[F, Byte],
      out: FileHandle[F],
      offset: Long
  ): Pull[F, Nothing, Unit] =
    WriteCursor(out, offset).writeAll(in).void
}
