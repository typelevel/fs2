package fs2.io

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousFileChannel, CompletionHandler, FileChannel, FileLock}
import java.nio.file.{OpenOption, Path, StandardOpenOption}

import fs2.Stream.Handle
import fs2._
import fs2.util.Monad

package object file {

  /**
    * Provides a handler for NIO methods which require a `java.nio.channels.CompletionHandler` instance.
    */
  private[fs2] def asyncCompletionHandler[F[_], O](f: CompletionHandler[O, Null] => F[Unit])(implicit F: Async[F], S: Strategy): F[O] = {
    F.async[O] { cb =>
      f(new CompletionHandler[O, Null] {
        override def completed(result: O, attachment: Null): Unit = S(cb(Right(result)))
        override def failed(exc: Throwable, attachment: Null): Unit = S(cb(Left(exc)))
      })
    }
  }

  /**
    * Creates a `FileHandle[F]` from a `java.nio.channels.AsynchronousFileChannel`.
    *
    * Uses a `java.nio.Channels.CompletionHandler` to handle callbacks from IO operations.
    */
  private[fs2] def fromAsynchronousFileChannel[F[_]](chan: AsynchronousFileChannel)(implicit F: Async[F], S: Strategy): FileHandle[F] = {
    new FileHandle[F] {
      type Lock = FileLock

      override def close(): F[Unit] =
        F.suspend(chan.close())

      override def force(metaData: Boolean): F[Unit] =
        F.suspend(chan.force(metaData))

      override def lock: F[Lock] =
        asyncCompletionHandler[F, Lock](f => F.pure(chan.lock(null, f)))

      override def lock(position: Long, size: Long, shared: Boolean): F[Lock] =
        asyncCompletionHandler[F, Lock](f => F.pure(chan.lock(position, size, shared, null, f)))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] = {
        val buf = ByteBuffer.allocate(numBytes)
        F.bind(
          asyncCompletionHandler[F, Integer](f => F.pure(chan.read(buf, offset, null, f))))(
          len => F.pure {
            if (len < 0) None else if (len == 0) Some(Chunk.empty) else Some(Chunk.bytes(buf.array.take(len)))
          }
        )
      }

      override def size: F[Long] =
        F.suspend(chan.size)

      override def truncate(size: Long): F[Unit] =
        F.suspend { chan.truncate(size); () }

      override def tryLock: F[Option[Lock]] =
        F.map(F.suspend(chan.tryLock()))(Option.apply)

      override def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]] =
        F.map(F.suspend(chan.tryLock(position, size, shared)))(Option.apply)

      override def unlock(f: Lock): F[Unit] =
        F.suspend(f.release())

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.map(
          asyncCompletionHandler[F, Integer](f => F.pure(chan.write(ByteBuffer.wrap(bytes.toArray), offset, null, f))))(
          i => i.toInt
        )
    }
  }

  /**
    * Creates a `FileHandle[F]` from a `java.nio.channels.FileChannel`.
    */
  private[fs2] def fromFileChannel[F[_]](chan: FileChannel)(implicit F: Monad[F]): FileHandle[F] = {
    new FileHandle[F] {
      type Lock = FileLock

      override def close(): F[Unit] =
        F.suspend(chan.close())

      override def force(metaData: Boolean): F[Unit] =
        F.suspend(chan.force(metaData))

      override def lock: F[Lock] =
        F.suspend(chan.lock)

      override def lock(position: Long, size: Long, shared: Boolean): F[Lock] =
        F.suspend(chan.lock(position, size, shared))

      override def read(numBytes: Int, offset: Long): F[Option[Chunk[Byte]]] = {
        val buf = ByteBuffer.allocate(numBytes)
        F.bind(
          F.suspend(chan.read(buf, offset)))(len => F.pure {
          if (len < 0) None else if (len == 0) Some(Chunk.empty) else Some(Chunk.bytes(buf.array.take(len)))
        }
        )
      }

      override def size: F[Long] =
        F.suspend(chan.size)

      override def truncate(size: Long): F[Unit] =
        F.suspend { chan.truncate(size); () }

      override def tryLock: F[Option[Lock]] =
        F.suspend(Option(chan.tryLock()))

      override def tryLock(position: Long, size: Long, shared: Boolean): F[Option[Lock]] =
        F.suspend(Option(chan.tryLock(position, size, shared)))

      override def unlock(f: Lock): F[Unit] =
        F.suspend(f.release())

      override def write(bytes: Chunk[Byte], offset: Long): F[Int] =
        F.suspend(chan.write(ByteBuffer.wrap(bytes.toArray), offset))
    }
  }

  //
  // Stream constructors
  //

  /**
    * Reads all data synchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAll[F[_]](path: Path, chunkSize: Int)(implicit F: Monad[F]): Stream[F, Byte] =
    open(path, List(StandardOpenOption.READ)).flatMap(readAllFromFileHandle(chunkSize)).run

  /**
    * Reads all data asynchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAllAsync[F[_]](path: Path, chunkSize: Int)(implicit F: Async[F], S: Strategy): Stream[F, Byte] =
    openAsync(path, List(StandardOpenOption.READ)).flatMap(readAllFromFileHandle(chunkSize)).run

  //
  // Stream transducers
  //

  /**
    * Writes all data synchronously to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAll[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE))(implicit F: Monad[F]): Sink[F, Byte] =
    s => (for {
      in <- s.open
      out <- open(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- _writeAll0(in, out, 0)
    } yield ()).run

  /**
    * Writes all data asynchronously to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAllAsync[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE))(implicit F: Async[F], S: Strategy): Sink[F, Byte] =
    s => (for {
      in <- s.open
      out <- openAsync(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- _writeAll0(in, out, 0)
    } yield ()).run

  private def _writeAll0[F[_]](in: Handle[F, Byte], out: FileHandle[F], offset: Long)(implicit F: Monad[F]): Pull[F, Nothing, Unit] = for {
    hd #: tail <- in.await
    _ <- _writeAll1(hd, out, offset)
    next <- _writeAll0(tail, out, offset + hd.size)
  } yield next

  private def _writeAll1[F[_]](buf: Chunk[Byte], out: FileHandle[F], offset: Long)(implicit F: Monad[F]): Pull[F, Nothing, Unit] =
    Pull.eval(out.write(buf, offset)) flatMap { (written: Int) =>
      if (written >= buf.size)
        Pull.pure(())
      else
        _writeAll1(buf.drop(written), out, offset + written)
    }

  //
  // Pull constructors
  //

  /**
    * Given a `FileHandle[F]`, creates a `Pull` which reads all data from the associated file.
    */
  private[fs2] def readAllFromFileHandle[F[_]](chunkSize: Int)(h: FileHandle[F]): Pull[F, Byte, Unit] =
    _readAllFromFileHandle0(chunkSize, 0)(h)

  private def _readAllFromFileHandle0[F[_]](chunkSize: Int, offset: Long)(h: FileHandle[F]): Pull[F, Byte, Unit] = for {
    res <- Pull.eval(h.read(chunkSize, offset))
    next <- res.fold[Pull[F, Byte, Unit]](Pull.done)(o => Pull.output(o) >> _readAllFromFileHandle0(chunkSize, offset + o.size)(h))
  } yield next

  /**
    * Creates a `Pull` which allows synchronous file operations against the file at the specified `java.nio.file.Path`.
    *
    * The `Pull` closes the acquired `java.nio.channels.FileChannel` when it is done.
    */
  def open[F[_]](path: Path, flags: Seq[OpenOption])(implicit F: Monad[F]): Pull[F, Nothing, FileHandle[F]] =
    Pull.acquire(F.suspend(fromFileChannel(FileChannel.open(path, flags: _*))))(_.close())

  /**
    * Creates a `Pull` which allows asynchronous file operations against the file at the specified `java.nio.file.Path`.
    *
    * The `Pull` closes the acquired `java.nio.channels.AsynchronousFileChannel` when it is done.
    */
  def openAsync[F[_]](path: Path, flags: Seq[OpenOption])(implicit F: Async[F], S: Strategy): Pull[F, Nothing, FileHandle[F]] =
    Pull.acquire(F.suspend(fromAsynchronousFileChannel(AsynchronousFileChannel.open(path, flags: _*))))(_.close())
}
