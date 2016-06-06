package fs2.io

import java.nio.channels.CompletionHandler
import java.nio.file.{Path, StandardOpenOption}

import fs2._
import fs2.Stream.Handle
import fs2.util.Effect

package object file {

  /**
    * Provides a handler for NIO methods which require a `java.nio.channels.CompletionHandler` instance.
    */
  private[fs2] def asyncCompletionHandler[F[_], O](f: CompletionHandler[O, Null] => F[Unit])(implicit F: Async[F], FR: Async.Run[F]): F[O] = {
    F.async[O] { cb =>
      f(new CompletionHandler[O, Null] {
        override def completed(result: O, attachment: Null): Unit = FR.unsafeRunAsyncEffects(F.delay(cb(Right(result))))(_ => ())
        override def failed(exc: Throwable, attachment: Null): Unit = FR.unsafeRunAsyncEffects(F.delay(cb(Left(exc))))(_ => ())
      })
    }
  }

  //
  // Stream constructors
  //

  /**
    * Reads all data synchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAll[F[_]](path: Path, chunkSize: Int)(implicit F: Effect[F]): Stream[F, Byte] =
    pulls.fromPath(path, List(StandardOpenOption.READ)).flatMap(pulls.readAllFromFileHandle(chunkSize)).close

  /**
    * Reads all data asynchronously from the file at the specified `java.nio.file.Path`.
    */
  def readAllAsync[F[_]](path: Path, chunkSize: Int)(implicit F: Async[F], FR: Async.Run[F]): Stream[F, Byte] =
    pulls.fromPathAsync(path, List(StandardOpenOption.READ)).flatMap(pulls.readAllFromFileHandle(chunkSize)).close

  //
  // Stream transducers
  //

  /**
    * Writes all data synchronously to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAll[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE))(implicit F: Effect[F]): Sink[F, Byte] =
    s => (for {
      in <- s.open
      out <- pulls.fromPath(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- pulls.writeAllToFileHandle(in, out)
    } yield ()).close

  /**
    * Writes all data asynchronously to the file at the specified `java.nio.file.Path`.
    *
    * Adds the WRITE flag to any other `OpenOption` flags specified. By default, also adds the CREATE flag.
    */
  def writeAllAsync[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE))(implicit F: Async[F], FR: Async.Run[F]): Sink[F, Byte] =
    s => (for {
      in <- s.open
      out <- pulls.fromPathAsync(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- _writeAll0(in, out, 0)
    } yield ()).close

  private def _writeAll0[F[_]](in: Handle[F, Byte], out: FileHandle[F], offset: Long): Pull[F, Nothing, Unit] = for {
    hd #: tail <- in.await
    _ <- _writeAll1(hd, out, offset)
    next <- _writeAll0(tail, out, offset + hd.size)
  } yield next

  private def _writeAll1[F[_]](buf: Chunk[Byte], out: FileHandle[F], offset: Long): Pull[F, Nothing, Unit] =
    Pull.eval(out.write(buf, offset)) flatMap { (written: Int) =>
      if (written >= buf.size)
        Pull.pure(())
      else
        _writeAll1(buf.drop(written), out, offset + written)
    }
}
