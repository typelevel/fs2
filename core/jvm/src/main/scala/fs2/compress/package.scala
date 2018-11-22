package fs2

import cats.effect.Sync
import cats.syntax.all._

import java.io.ByteArrayOutputStream
import java.util.zip.{DataFormatException, Deflater, GZIPInputStream, GZIPOutputStream, Inflater}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/** Provides utilities for compressing/decompressing byte streams. */
package object compress {

  /**
    * Returns a `Pipe` that deflates (compresses) its input elements using
    * a `java.util.zip.Deflater` with the parameters `level`, `nowrap` and `strategy`.
    * @param level the compression level (0-9)
    * @param nowrap if true then use GZIP compatible compression
    * @param bufferSize size of the internal buffer that is used by the
    *                   compressor. Default size is 32 KB.
    * @param strategy compression strategy -- see `java.util.zip.Deflater` for details
    */
  def deflate[F[_]](level: Int = Deflater.DEFAULT_COMPRESSION,
                    nowrap: Boolean = false,
                    bufferSize: Int = 1024 * 32,
                    strategy: Int = Deflater.DEFAULT_STRATEGY): Pipe[F, Byte, Byte] = { in =>
    Pull.suspend {
      val deflater = new Deflater(level, nowrap)
      deflater.setStrategy(strategy)
      val buffer = new Array[Byte](bufferSize)
      _deflate_stream(deflater, buffer)(in)
    }.stream
  }

  private def _deflate_stream[F[_]](deflater: Deflater,
                                    buffer: Array[Byte]): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.uncons.flatMap {
      case Some((hd, tl)) =>
        deflater.setInput(hd.toArray)
        val result =
          _deflate_collect(deflater, buffer, ArrayBuffer.empty, false).toArray
        Pull.output(Chunk.bytes(result)) >> _deflate_stream(deflater, buffer)(tl)
      case None =>
        deflater.setInput(Array.empty[Byte])
        deflater.finish()
        val result =
          _deflate_collect(deflater, buffer, ArrayBuffer.empty, true).toArray
        deflater.end()
        Pull.output(Chunk.bytes(result))
    }

  @tailrec
  private def _deflate_collect(deflater: Deflater,
                               buffer: Array[Byte],
                               acc: ArrayBuffer[Byte],
                               fin: Boolean): ArrayBuffer[Byte] =
    if ((fin && deflater.finished) || (!fin && deflater.needsInput)) acc
    else {
      val count = deflater.deflate(buffer)
      _deflate_collect(deflater, buffer, acc ++ buffer.iterator.take(count), fin)
    }

  /**
    * Returns a `Pipe` that inflates (decompresses) its input elements using
    * a `java.util.zip.Inflater` with the parameter `nowrap`.
    * @param nowrap if true then support GZIP compatible decompression
    * @param bufferSize size of the internal buffer that is used by the
    *                   decompressor. Default size is 32 KB.
    */
  def inflate[F[_]](nowrap: Boolean = false, bufferSize: Int = 1024 * 32)(
      implicit ev: RaiseThrowable[F]): Pipe[F, Byte, Byte] =
    _.pull.uncons.flatMap {
      case None => Pull.pure(None)
      case Some((hd, tl)) =>
        val inflater = new Inflater(nowrap)
        val buffer = new Array[Byte](bufferSize)
        inflater.setInput(hd.toArray)
        val result =
          _inflate_collect(inflater, buffer, ArrayBuffer.empty).toArray
        Pull.output(Chunk.bytes(result)) >> _inflate_stream(inflater, buffer)(ev)(tl)
    }.stream

  private def _inflate_stream[F[_]](inflater: Inflater, buffer: Array[Byte])(
      implicit ev: RaiseThrowable[F]): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.uncons.flatMap {
      case Some((hd, tl)) =>
        inflater.setInput(hd.toArray)
        val result =
          _inflate_collect(inflater, buffer, ArrayBuffer.empty).toArray
        Pull.output(Chunk.bytes(result)) >> _inflate_stream(inflater, buffer)(ev)(tl)
      case None =>
        if (!inflater.finished)
          Pull.raiseError[F](new DataFormatException("Insufficient data"))
        else { inflater.end(); Pull.done }
    }

  @tailrec
  private def _inflate_collect(inflater: Inflater,
                               buffer: Array[Byte],
                               acc: ArrayBuffer[Byte]): ArrayBuffer[Byte] =
    if (inflater.finished || inflater.needsInput) acc
    else {
      val count = inflater.inflate(buffer)
      _inflate_collect(inflater, buffer, acc ++ buffer.iterator.take(count))
    }

  /**
    * Returns a pipe that incrementally compresses input into the GZIP format
    * by delegating to `java.util.zip.GZIPOutputStream`. Output is compatible
    * with the GNU utils `gunzip` utility, as well as really anything else that
    * understands GZIP. Note, however, that the GZIP format is not "stable" in
    * the sense that all compressors will produce identical output given
    * identical input. Part of the header seeding is arbitrary and chosen by
    * the compression implementation. For this reason, the exact bytes produced
    * by this pipe will differ in insignificant ways from the exact bytes produced
    * by a tool like the GNU utils `gzip`.
    *
    * @param bufferSize The buffer size which will be used to page data
    *                   from the OutputStream back into chunks. This will
    *                   be the chunk size of the output stream. You should
    *                   set it to be equal to the size of the largest
    *                   chunk in the input stream. Setting this to a size
    *                   which is ''smaller'' than the chunks in the input
    *                   stream will result in performance degradation of
    *                   roughly 50-75%.
    */
  def gzip[F[_]: Sync](bufferSize: Int): Pipe[F, Byte, Byte] = { in =>
    for {
      bos <- Stream.eval(Sync[F].delay(new ByteArrayOutputStream(bufferSize)))
      gzos <- Stream.eval(Sync[F].delay(new GZIPOutputStream(bos, bufferSize, true)))

      slurpBytes = Sync[F].delay {
        val back = bos.toByteArray
        bos.reset()
        back
      }

      body = in.chunks.flatMap { chunk =>
        Stream.evalUnChunk {
          for {
            _ <- Sync[F].delay {
              chunk match {
                case Chunk.Bytes(values, off, len) =>
                  gzos.write(values, off, len)

                case Chunk.ByteVectorChunk(bv) =>
                  bv.copyToStream(gzos)

                // TODO is there a better way of doing this?
                case chunk =>
                  val len = chunk.size
                  val buf = new Array[Byte](len)

                  chunk.copyToArray(buf, 0)
                  gzos.write(buf)
              }
            }

            _ <- Sync[F].delay(gzos.flush()) // eagerly flush on each chunk

            arr <- slurpBytes
          } yield Chunk.bytes(arr)
        }
      }

      b <- body ++ Stream.eval_(Sync[F].delay(gzos.close())) ++ Stream.evalUnChunk(
        slurpBytes.map(Chunk.bytes(_)))
    } yield b
  }

  /**
    * Returns a pipe that incrementally decompresses input according to the GZIP
    * format. Any errors in decompression will be sequenced as exceptions into the
    * output stream. The implementation of this pipe delegates directly to
    * `GZIPInputStream`. Despite this, decompression is still handled in a streaming
    * and async fashion without any thread blockage. Under the surface, this is
    * handled by enqueueing chunks into a special type of byte array InputStream
    * which throws exceptions when exhausted rather than blocking. These signal
    * exceptions are caught by the pipe and treated as an async suspension. Thus,
    * there are no issues with arbitrarily-framed data and chunk boundaries. Also
    * note that there is almost no performance impact from these exceptions, due
    * to the way that the JVM handles throw/catch.
    *
    * The chunk size here is actually really important. If you set it to be too
    * small, then there will be insufficient buffer space for `GZIPInputStream` to
    * read the GZIP header preamble. This can result in repeated, non-progressing
    * async suspensions. This case is caught internally and will be raised as an
    * exception (`NonProgressiveDecompressionException`) within the output stream.
    * Under normal circumstances, you shouldn't have to worry about this. Just, uh,
    * don't set the buffer size to something tiny. Matching the input stream largest
    * chunk size, or roughly 8 KB (whichever is larger) is a good rule of thumb.
    *
    * @param bufferSize The bounding size of the input buffer. This should roughly
    *                   match the size of the largest chunk in the input stream.
    *                   The chunk size in the output stream will be determined by
    *                   double this value.
    */
  def gunzip[F[_]: Sync](bufferSize: Int): Pipe[F, Byte, Byte] = { in =>
    Stream.eval(AsyncByteArrayInputStream(bufferSize)).flatMap { abis =>
      def push(chunk: Chunk[Byte]): F[Unit] =
        for {
          arr <- Sync[F].delay {
            val buf = new Array[Byte](chunk.size)
            chunk.copyToArray(buf) // TODO we can be slightly better than this for Chunk.Bytes if we track incoming offsets in abis
            buf
          }

          pushed <- abis.push(arr)

          _ <- if (!pushed)
            Sync[F].raiseError(NonProgressiveDecompressionException(bufferSize))
          else
            ().pure[F]
        } yield ()

      def pageBeginning(in: Stream[F, Byte]): Pull[F, (GZIPInputStream, Stream[F, Byte]), Unit] =
        in.pull.uncons.flatMap {
          case Some((chunk, tail)) =>
            val tryAcquire = abis.checkpoint >> Sync[F]
              .delay(new GZIPInputStream(abis, bufferSize))
              .attempt // GZIPInputStream has no resources, so we don't need to bracket
            val createOrLoop = Pull.eval(tryAcquire).flatMap {
              case Right(gzis) =>
                Pull.output1((gzis, tail)) >> Pull.eval(abis.release) >> Pull.done
              case Left(AsyncByteArrayInputStream.AsyncError) =>
                Pull.eval(abis.restore) >> pageBeginning(tail)
              case Left(t) => Pull.raiseError(t)
            }

            Pull.eval(push(chunk)) >> createOrLoop

          // we got all the way to the end of the input without moving forward
          case None =>
            Pull.raiseError(NonProgressiveDecompressionException(bufferSize))
        }

      pageBeginning(in).stream.flatMap {
        case (gzis, in) =>
          lazy val stepDecompress: Stream[F, Byte] = Stream.force {
            Sync[F].delay {
              val inner = new Array[Byte](bufferSize * 2) // double the input buffer size since we're decompressing

              val len = try {
                gzis.read(inner)
              } catch {
                case AsyncByteArrayInputStream.AsyncError => 0
              }

              if (len > 0)
                Stream.chunk(Chunk.bytes(inner, 0, len)).covary[F] ++ stepDecompress
              else
                Stream.empty[F]
            }
          }

          // TODO: It is possible for this to fail with a non-progressive error
          //       if `in` contains bytes in addition to the compressed data.
          val mainline = in.chunks.flatMap { chunk =>
            Stream.eval_(push(chunk)) ++ stepDecompress
          }

          stepDecompress ++ mainline
      }
    }
  }

}
