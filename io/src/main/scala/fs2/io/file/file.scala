package fs2
package io

import java.io.{InputStream, OutputStream}
import java.nio.channels.CompletionHandler
import java.nio.file.{Path, StandardOpenOption}

import fs2.util.{Async, Effect}
import fs2.util.syntax._

package object file {

  /**
    * Provides a handler for NIO methods which require a `java.nio.channels.CompletionHandler` instance.
    */
  private[fs2] def asyncCompletionHandler[F[_], O](f: CompletionHandler[O, Null] => F[Unit])(implicit F: Async[F]): F[O] = {
    F.async[O] { cb =>
      f(new CompletionHandler[O, Null] {
        override def completed(result: O, attachment: Null): Unit = F.unsafeRunAsync(F.start(F.delay(cb(Right(result)))))(_ => ())
        override def failed(exc: Throwable, attachment: Null): Unit = F.unsafeRunAsync(F.start(F.delay(cb(Left(exc)))))(_ => ())
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
  def readAllAsync[F[_]](path: Path, chunkSize: Int)(implicit F: Async[F]): Stream[F, Byte] =
    pulls.fromPathAsync(path, List(StandardOpenOption.READ)).flatMap(pulls.readAllFromFileHandle(chunkSize)).close

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    */
  def readInputStream[F[_]](fis: F[InputStream], chunkSize: Int, closeAfterUse: Boolean = true)(implicit F: Effect[F]): Stream[F, Byte] = {
    val buf = new Array[Byte](chunkSize)

    def singleRead(is: InputStream, buf: Array[Byte]): F[Option[Chunk[Byte]]] =
      F.delay(is.read(buf)).map { numBytes =>
        if (numBytes < 0) None
        else if (numBytes == 0) Some(Chunk.empty)
        else Some(Chunk.bytes(buf, 0, numBytes))
      }

    def useIs(is: InputStream) =
      Stream.eval(singleRead(is, buf))
        .repeat
        .through(pipe.unNoneTerminate)
        .flatMap(Stream.chunk)

    if (closeAfterUse)
      Stream.bracket(fis)(useIs, is => F.delay(is.close()))
    else
      Stream.eval(fis).flatMap(useIs)
  }

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
  def writeAllAsync[F[_]](path: Path, flags: Seq[StandardOpenOption] = List(StandardOpenOption.CREATE))(implicit F: Async[F]): Sink[F, Byte] =
    s => (for {
      in <- s.open
      out <- pulls.fromPathAsync(path, StandardOpenOption.WRITE :: flags.toList)
      _ <- _writeAll0(in, out, 0)
    } yield ()).close

  private def _writeAll0[F[_]](in: Handle[F, Byte], out: FileHandle[F], offset: Long): Pull[F, Nothing, Unit] = for {
    (hd, tail) <- in.await
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

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    */
  def writeOutputStream[F[_]](fos: F[OutputStream], closeAfterUse: Boolean = true)(implicit F: Effect[F]): Sink[F, Byte] = s => {
    def useOs(os: OutputStream): Stream[F, Unit] =
      s.chunks.evalMap { bytes => F.delay(os.write(bytes.toArray))}

    if (closeAfterUse)
      Stream.bracket(fos)(useOs, os => F.delay(os.close()))
    else
      Stream.eval(fos).flatMap(useOs)
  }

  //
  // STDIN/STDOUT Helpers

  def stdin[F[_]](bufSize: Int)(implicit F: Effect[F]): Stream[F, Byte] =
    readInputStream(F.delay(System.in), bufSize, false)

  def stdout[F[_]](implicit F: Effect[F]): Sink[F, Byte] =
    writeOutputStream(F.delay(System.out), false)

}
