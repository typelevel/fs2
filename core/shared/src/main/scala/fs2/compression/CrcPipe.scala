package fs2.compression

import cats.effect.Deferred
import fs2.{Chunk, Pipe, Pull, Stream}

object CrcPipe {

  def apply[F[_]](deferredCrc: Deferred[F, Long]): Pipe[F, Byte, Byte] = {
    def pull(crcBuilder: CRC32): Stream[F, Byte] => Pull[F, Byte, Long] =
      _.pull.uncons.flatMap {
        case None => Pull.pure(crcBuilder.getValue())
        case Some((c: Chunk[Byte], rest: Stream[F, Byte])) =>
          val slice = c.toArraySlice
          crcBuilder.update(slice.values, slice.offset, slice.length)
          for {
            _ <- Pull.output(c)
            hexString <- pull(crcBuilder)(rest)
          } yield hexString
      }

    def calculateCrcOf(input: Stream[F, Byte]): Pull[F, Byte, Unit] =
      for {
        crcBuilder <- Pull.pure(new CRC32)
        crc <- pull(crcBuilder)(input)
        _ <- Pull.eval(deferredCrc.complete(crc))
      } yield ()

    calculateCrcOf(_).stream
  }

}
