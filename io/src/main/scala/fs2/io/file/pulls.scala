package fs2
package io
package file

import scala.concurrent.ExecutionContext

import java.nio.channels._
import java.nio.file._
import java.util.concurrent.ExecutorService

import cats.effect.{ Effect, Sync }

/** Provides various `Pull`s for working with files. */
object pulls {

  /**
   * Given a `FileHandle[F]`, creates a `Pull` which reads all data from the associated file.
   */
  def readAllFromFileHandle[F[_]](chunkSize: Int)(h: FileHandle[F]): Pull[F, Byte, Unit] =
    _readAllFromFileHandle0(chunkSize, 0)(h)

  private def _readAllFromFileHandle0[F[_]](chunkSize: Int, offset: Long)(h: FileHandle[F]): Pull[F, Byte, Unit] = for {
    res <- Pull.eval(h.read(chunkSize, offset))
    next <- res.fold[Pull[F, Byte, Unit]](Pull.done)(o => Pull.output(o) *> _readAllFromFileHandle0(chunkSize, offset + o.size)(h))
  } yield next


  /**
   * Given a `Stream[F, Byte]` and `FileHandle[F]`, writes all data from the stream to the file.
   */
  def writeAllToFileHandle[F[_]](in: Stream[F, Byte], out: FileHandle[F]): Pull[F, Nothing, Unit] =
    _writeAllToFileHandle1(in, out, 0)

  private def _writeAllToFileHandle1[F[_]](in: Stream[F, Byte], out: FileHandle[F], offset: Long): Pull[F, Nothing, Unit] =
    in.pull.unconsChunk.flatMap {
      case None => Pull.done
      case Some((hd,tl)) =>
        _writeAllToFileHandle2(hd, out, offset) *> _writeAllToFileHandle1(tl, out, offset + hd.size)
    }

  private def _writeAllToFileHandle2[F[_]](buf: Chunk[Byte], out: FileHandle[F], offset: Long): Pull[F, Nothing, Unit] =
    Pull.eval(out.write(buf, offset)) flatMap { (written: Int) =>
      if (written >= buf.size)
        Pull.pure(())
      else
        _writeAllToFileHandle2(buf.drop(written).toOption.get.toChunk, out, offset + written)
    }

  /**
   * Creates a `Pull` which allows synchronous file operations against the file at the specified `java.nio.file.Path`.
   *
   * The `Pull` closes the acquired `java.nio.channels.FileChannel` when it is done.
   */
  def fromPath[F[_]](path: Path, flags: Seq[OpenOption])(implicit F: Sync[F]): Pull[F, Nothing, Pull.Cancellable[F, FileHandle[F]]] =
    fromFileChannel(F.delay(FileChannel.open(path, flags: _*)))

  /**
   * Creates a `Pull` which allows asynchronous file operations against the file at the specified `java.nio.file.Path`.
   *
   * The `Pull` closes the acquired `java.nio.channels.AsynchronousFileChannel` when it is done.
   */
  def fromPathAsync[F[_]](path: Path, flags: Seq[OpenOption], executorService: Option[ExecutorService] = None)(implicit F: Effect[F], ec: ExecutionContext): Pull[F, Nothing, Pull.Cancellable[F, FileHandle[F]]] = {
    import collection.JavaConverters._
    fromAsynchronousFileChannel(F.delay(AsynchronousFileChannel.open(path, flags.toSet.asJava, executorService.orNull)))
  }

  /**
   * Given a `java.nio.channels.FileChannel`, will create a `Pull` which allows synchronous operations against the underlying file.
   *
   * The `Pull` closes the provided `java.nio.channels.FileChannel` when it is done.
   */
  def fromFileChannel[F[_]](channel: F[FileChannel])(implicit F: Sync[F]): Pull[F, Nothing, Pull.Cancellable[F, FileHandle[F]]] =
    Pull.acquireCancellable(channel)(ch => F.delay(ch.close())).map(_.map(FileHandle.fromFileChannel[F]))

  /**
   * Given a `java.nio.channels.AsynchronousFileChannel`, will create a `Pull` which allows asynchronous operations against the underlying file.
   *
   * The `Pull` closes the provided `java.nio.channels.AsynchronousFileChannel` when it is done.
   */
  def fromAsynchronousFileChannel[F[_]](channel: F[AsynchronousFileChannel])(implicit F: Effect[F], ec: ExecutionContext): Pull[F, Nothing, Pull.Cancellable[F, FileHandle[F]]] =
    Pull.acquireCancellable(channel)(ch => F.delay(ch.close())).map(_.map(FileHandle.fromAsynchronousFileChannel[F]))
}
