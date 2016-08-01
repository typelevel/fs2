package fs2

import fs2.util.{Async, Effect}
import fs2.util.syntax._
import java.io.{InputStream, OutputStream}

package object io {

  private[io] def readBytesFromInputStream[F[_]](is: InputStream, buf: Array[Byte])(implicit F: Effect[F]): F[Option[Chunk[Byte]]] =
    F.delay(is.read(buf)).map { numBytes =>
      if (numBytes < 0) None
      else if (numBytes == 0) Some(Chunk.empty)
      else Some(Chunk.bytes(buf, 0, numBytes))
    }

  private[io] def readInputStreamGeneric[F[_]](fis: F[InputStream], chunkSize: Int, f: (InputStream, Array[Byte]) => F[Option[Chunk[Byte]]], closeAfterUse: Boolean = true)(implicit F: Effect[F]): Stream[F, Byte] = {
    val buf = new Array[Byte](chunkSize)

    def useIs(is: InputStream) =
      Stream.eval(f(is, buf))
        .repeat
        .through(pipe.unNoneTerminate)
        .flatMap(Stream.chunk)

    if (closeAfterUse)
      Stream.bracket(fis)(useIs, is => F.delay(is.close()))
    else
      Stream.eval(fis).flatMap(useIs)
  }

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * Blocks the current thread.
    */
  def readInputStream[F[_]](fis: F[InputStream], chunkSize: Int, closeAfterUse: Boolean = true)(implicit F: Effect[F]): Stream[F, Byte] =
    readInputStreamGeneric(fis, chunkSize, readBytesFromInputStream[F], closeAfterUse)

  /**
    * Reads all bytes from the specified `InputStream` with a buffer size of `chunkSize`.
    * Set `closeAfterUse` to false if the `InputStream` should not be closed after use.
    *
    * This will block a thread in the current `Async` instance, so the size of any associated
    * threadpool should be sized appropriately.
    */
  def readInputStreamAsync[F[_]](fis: F[InputStream], chunkSize: Int, closeAfterUse: Boolean = true)(implicit F: Async[F]): Stream[F, Byte] = {
    def readAsync(is: InputStream, buf: Array[Byte]) =
      F.start(readBytesFromInputStream(is, buf)).flatMap(identity)

    readInputStreamGeneric(fis, chunkSize, readAsync, closeAfterUse)
  }

  private[io] def writeBytesToOutputStream[F[_]](os: OutputStream, bytes: Chunk[Byte])(implicit F: Effect[F]): F[Unit] =
    F.delay(os.write(bytes.toArray))

  private[io] def writeOutputStreamGeneric[F[_]](fos: F[OutputStream], closeAfterUse: Boolean, f: (OutputStream, Chunk[Byte]) => F[Unit])(implicit F: Effect[F]): Sink[F, Byte] = s => {
    def useOs(os: OutputStream): Stream[F, Unit] =
      s.chunks.evalMap(f(os, _))

    if (closeAfterUse)
      Stream.bracket(fos)(useOs, os => F.delay(os.close()))
    else
      Stream.eval(fos).flatMap(useOs)
  }

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * Blocks the current thread.
    */
  def writeOutputStream[F[_]](fos: F[OutputStream], closeAfterUse: Boolean = true)(implicit F: Effect[F]): Sink[F, Byte] =
    writeOutputStreamGeneric(fos, closeAfterUse, writeBytesToOutputStream[F])

  /**
    * Writes all bytes to the specified `OutputStream`. Set `closeAfterUse` to false if
    * the `OutputStream` should not be closed after use.
    *
    * This will block a thread in the current `Async` instance, so the size of any associated
    * threadpool should be sized appropriately.
    */
  def writeOutputStreamAsync[F[_]](fos: F[OutputStream], closeAfterUse: Boolean = true)(implicit F: Async[F]): Sink[F, Byte] = {
    def writeAsync(os: OutputStream, buf: Chunk[Byte]) =
      F.start(writeBytesToOutputStream(os, buf)).flatMap(identity)

    writeOutputStreamGeneric(fos, closeAfterUse, writeAsync)
  }

  //
  // STDIN/STDOUT Helpers

  def stdin[F[_]](bufSize: Int)(implicit F: Effect[F]): Stream[F, Byte] =
    readInputStream(F.delay(System.in), bufSize, false)

  def stdinAsync[F[_]](bufSize: Int)(implicit F: Async[F]): Stream[F, Byte] =
    readInputStreamAsync(F.delay(System.in), bufSize, false)

  def stdout[F[_]](implicit F: Effect[F]): Sink[F, Byte] =
    writeOutputStream(F.delay(System.out), false)

  def stdoutAsync[F[_]](implicit F: Async[F]): Sink[F, Byte] =
    writeOutputStreamAsync(F.delay(System.out), false)
}
