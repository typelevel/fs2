package fs2
package io

import java.io.{InputStream, OutputStream}

import fs2.util.Effect
import fs2.util.syntax._

private[io] object JavaInputOutputStream {
  def readBytesFromInputStream[F[_]](is: InputStream, buf: Array[Byte])(implicit F: Effect[F]): F[Option[Chunk[Byte]]] =
    F.delay(is.read(buf)).map { numBytes =>
      if (numBytes < 0) None
      else if (numBytes == 0) Some(Chunk.empty)
      else Some(Chunk.bytes(buf, 0, numBytes))
    }

  def readInputStreamGeneric[F[_]](fis: F[InputStream], chunkSize: Int, f: (InputStream, Array[Byte]) => F[Option[Chunk[Byte]]], closeAfterUse: Boolean = true)(implicit F: Effect[F]): Stream[F, Byte] = {
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

  def writeBytesToOutputStream[F[_]](os: OutputStream, bytes: Chunk[Byte])(implicit F: Effect[F]): F[Unit] =
    F.delay(os.write(bytes.toArray))

  def writeOutputStreamGeneric[F[_]](fos: F[OutputStream], closeAfterUse: Boolean, f: (OutputStream, Chunk[Byte]) => F[Unit])(implicit F: Effect[F]): Sink[F, Byte] = s => {
    def useOs(os: OutputStream): Stream[F, Unit] =
      s.chunks.evalMap(f(os, _))

    if (closeAfterUse)
      Stream.bracket(fos)(useOs, os => F.delay(os.close()))
    else
      Stream.eval(fos).flatMap(useOs)
  }

}
