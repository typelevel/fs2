package fs2

import java.util.zip.{DataFormatException, Deflater, Inflater}
import fs2.Stream.Handle

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

object compress {
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
              strategy: Int = Deflater.DEFAULT_STRATEGY): Pipe[F,Byte,Byte] = {
    val pure: Pipe[Pure,Byte,Byte] =
      _ pull { _.awaitNonempty flatMap { step =>
        val deflater = new Deflater(level, nowrap)
        deflater.setStrategy(strategy)
        val buffer = new Array[Byte](bufferSize)
        _deflate_step(deflater, buffer)(step)
      }}
    pipe.covary[F,Byte,Byte](pure)
  }
  private def _deflate_step(deflater: Deflater, buffer: Array[Byte]): Step[Chunk[Byte], Handle[Pure, Byte]] => Pull[Pure, Byte, Handle[Pure, Byte]] = {
    case c #: h =>
      deflater.setInput(c.toArray)
      val result = _deflate_collect(deflater, buffer, ArrayBuffer.empty, false).toArray
      Pull.output(Chunk.bytes(result)) >> _deflate_handle(deflater, buffer)(h)
  }
  private def _deflate_handle(deflater: Deflater, buffer: Array[Byte]): Handle[Pure, Byte] => Pull[Pure, Byte, Handle[Pure, Byte]] =
    _.awaitNonempty flatMap _deflate_step(deflater, buffer) or _deflate_finish(deflater, buffer)
  @tailrec
  private def _deflate_collect(deflater: Deflater, buffer: Array[Byte], acc: ArrayBuffer[Byte], fin: Boolean): ArrayBuffer[Byte] = {
    if ((fin && deflater.finished) || (!fin && deflater.needsInput)) acc
    else {
      val count = deflater deflate buffer
      _deflate_collect(deflater, buffer, acc ++ buffer.iterator.take(count), fin)
    }
  }
  private def _deflate_finish(deflater: Deflater, buffer: Array[Byte]): Pull[Pure, Byte, Nothing] = {
    deflater.setInput(Array.empty)
    deflater.finish()
    val result = _deflate_collect(deflater, buffer, ArrayBuffer.empty, true).toArray
    deflater.end()
    Pull.output(Chunk.bytes(result)) >> Pull.done
  }

  /**
    * Returns a `Pipe` that inflates (decompresses) its input elements using
    * a `java.util.zip.Inflater` with the parameter `nowrap`.
    * @param nowrap if true then support GZIP compatible compression
    * @param bufferSize size of the internal buffer that is used by the
    *                   decompressor. Default size is 32 KB.
    */
  def inflate[F[_]](nowrap: Boolean = false,
              bufferSize: Int = 1024 * 32): Pipe[F,Byte,Byte] = {
    val pure: Pipe[Pure,Byte,Byte] =
      _ pull { _.awaitNonempty flatMap { case c #: h =>
        val inflater = new Inflater(nowrap)
        val buffer = new Array[Byte](bufferSize)
        inflater.setInput(c.toArray)
        val result = _inflate_collect(inflater, buffer, ArrayBuffer.empty).toArray
        Pull.output(Chunk.bytes(result)) >> _inflate_handle(inflater, buffer)(h)
      }}
    pipe.covary[F,Byte,Byte](pure)
  }
  private def _inflate_step(inflater: Inflater, buffer: Array[Byte]): Step[Chunk[Byte], Handle[Pure, Byte]] => Pull[Pure, Byte, Handle[Pure, Byte]] = {
    case c #: h =>
      inflater.setInput(c.toArray)
      val result = _inflate_collect(inflater, buffer, ArrayBuffer.empty).toArray
      Pull.output(Chunk.bytes(result)) >> _inflate_handle(inflater, buffer)(h)
  }
  private def _inflate_handle(inflater: Inflater, buffer: Array[Byte]): Handle[Pure, Byte] => Pull[Pure, Byte, Handle[Pure, Byte]] =
    _.awaitNonempty flatMap _inflate_step(inflater, buffer) or _inflate_finish(inflater)
  @tailrec
  private def _inflate_collect(inflater: Inflater, buffer: Array[Byte], acc: ArrayBuffer[Byte]): ArrayBuffer[Byte] = {
    if (inflater.finished || inflater.needsInput) acc
    else {
      val count = inflater inflate buffer
      _inflate_collect(inflater, buffer, acc ++ buffer.iterator.take(count))
    }
  }
  private def _inflate_finish(inflater: Inflater): Pull[Pure, Nothing, Nothing] = {
    if (!inflater.finished) Pull.fail(new DataFormatException("Insufficient data"))
    else { inflater.end(); Pull.done }
  }
}
