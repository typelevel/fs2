package fs2
package io
package file

import scala.concurrent.ExecutionContext

import java.nio.channels._
import java.nio.file._

import cats.effect.{ContextShift, Sync}

/** Provides various `Pull`s for working with files. */
object pulls {

  /**
    * Given a `FileHandle[F]`, creates a `Pull` which reads all data from the associated file.
    */
  def readAllFromFileHandle[F[_]](chunkSize: Int)(h: FileHandle[F]): Pull[F, Byte, Unit] =
    _readAllFromFileHandle0(chunkSize, 0)(h)

  def readRangeFromFileHandle[F[_]](chunkSize: Int, start: Long, end: Long)(
      h: FileHandle[F]): Pull[F, Byte, Unit] =
    _readRangeFromFileHandle0(chunkSize, start, end)(h)

  private def _readRangeFromFileHandle0[F[_]](chunkSize: Int, offset: Long, end: Long)(
      h: FileHandle[F]): Pull[F, Byte, Unit] = {

    val bytesLeft = end - offset
    if (bytesLeft <= 0L) {
      Pull.done
    } else {
      val actualChunkSize =
        if (bytesLeft > Int.MaxValue) chunkSize else math.min(chunkSize, bytesLeft.toInt)

      Pull.eval(h.read(actualChunkSize, offset)).flatMap {
        case Some(o) =>
          Pull.output(o) >> _readRangeFromFileHandle0(chunkSize, offset + o.size, end)(h)
        case None => Pull.done
      }
    }
  }

  private def _readAllFromFileHandle0[F[_]](chunkSize: Int, offset: Long)(
      h: FileHandle[F]): Pull[F, Byte, Unit] =
    Pull.eval(h.read(chunkSize, offset)).flatMap {
      case Some(o) =>
        Pull.output(o) >> _readAllFromFileHandle0(chunkSize, offset + o.size)(h)
      case None => Pull.done
    }

  /**
    * Given a `Stream[F, Byte]` and `FileHandle[F]`, writes all data from the stream to the file.
    */
  def writeAllToFileHandle[F[_]](in: Stream[F, Byte], out: FileHandle[F]): Pull[F, Nothing, Unit] =
    writeAllToFileHandleAtOffset(in, out, 0)

  /** Like `writeAllToFileHandle` but takes an offset in to the file indicating where write should start. */
  def writeAllToFileHandleAtOffset[F[_]](in: Stream[F, Byte],
                                         out: FileHandle[F],
                                         offset: Long): Pull[F, Nothing, Unit] =
    in.pull.uncons.flatMap {
      case None => Pull.done
      case Some((hd, tl)) =>
        writeChunkToFileHandle(hd, out, offset) >> writeAllToFileHandleAtOffset(tl,
                                                                                out,
                                                                                offset + hd.size)
    }

  private def writeChunkToFileHandle[F[_]](buf: Chunk[Byte],
                                           out: FileHandle[F],
                                           offset: Long): Pull[F, Nothing, Unit] =
    Pull.eval(out.write(buf, offset)).flatMap { (written: Int) =>
      if (written >= buf.size)
        Pull.pure(())
      else
        writeChunkToFileHandle(buf.drop(written), out, offset + written)
    }

  /**
    * Creates a `Pull` which allows synchronous file operations against the file at the specified `java.nio.file.Path`.
    *
    * The `Pull` closes the acquired `java.nio.channels.FileChannel` when it is done.
    */
  def fromPath[F[_]](path: Path,
                     blockingExecutionContext: ExecutionContext,
                     flags: Seq[OpenOption])(
      implicit F: Sync[F],
      cs: ContextShift[F]): Pull[F, Nothing, Pull.Cancellable[F, FileHandle[F]]] =
    fromFileChannel(F.delay(FileChannel.open(path, flags: _*)), blockingExecutionContext)

  /**
    * Given a `java.nio.channels.FileChannel`, will create a `Pull` which allows synchronous operations against the underlying file.
    *
    * The `Pull` closes the provided `java.nio.channels.FileChannel` when it is done.
    */
  def fromFileChannel[F[_]](channel: F[FileChannel], blockingExecutionContext: ExecutionContext)(
      implicit F: Sync[F],
      cs: ContextShift[F]): Pull[F, Nothing, Pull.Cancellable[F, FileHandle[F]]] =
    Pull
      .acquireCancellable(channel)(ch => F.delay(ch.close()))
      .map(_.map(FileHandle.fromFileChannel[F](_, blockingExecutionContext)))
}
