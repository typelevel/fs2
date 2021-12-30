package fs2.compression

import cats.effect.{Deferred, Sync}
import fs2.{Chunk, Pipe, Pull, Stream}

object CountPipe {

  def apply[F[_]](deferredCount: Deferred[F, Long])(implicit F: Sync[F]): Pipe[F, Byte, Byte] = {
    def pull(count: Long): Stream[F, Byte] => Pull[F, Byte, Long] =
      _.pull.uncons.flatMap {
        case None => Pull.eval(F.delay(count))
        case Some((c: Chunk[Byte], rest: Stream[F, Byte])) =>
          for {
            _ <- Pull.output(c)
            hexString <- pull(count + c.size)(rest)
          } yield hexString
      }

    def calculateSizeOf(input: Stream[F, Byte]): Pull[F, Byte, Unit] =
      for {
        count <- pull(0)(input)
        _ <- Pull.eval(deferredCount.complete(count))
      } yield ()

    calculateSizeOf(_).stream
  }

}
