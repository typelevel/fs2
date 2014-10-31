package scalaz.stream

import java.util.zip.{Deflater, Inflater}
import scala.annotation.tailrec
import scodec.bits.ByteVector

import Process._
import process1._

/**
 * @define MutableProcess1 [[https://github.com/scalaz/scalaz-stream/blob/master/src/test/scala/scalaz/stream/examples/MutableProcess1.scala `MutableProcess1`]]
 * @define MutableImpl @note This process uses mutable state as an
 *   implementation detail which can become observable under certain
 *   conditions. See $MutableProcess1 for more information.
 */
object compress {
  /**
   * Returns a `Process1` that deflates (compresses) its input elements using
   * a `java.util.zip.Deflater` with the parameters `level` and `nowrap`.
   * @param level the compression level (0-9)
   * @param nowrap if true then use GZIP compatible compression
   * @param bufferSize size of the internal buffer that is used by the
   *                   compressor. Default size is 32 KB.
   * $MutableImpl
   */
  def deflate(level: Int = Deflater.DEFAULT_COMPRESSION,
              nowrap: Boolean = false,
              bufferSize: Int = 1024 * 32): Process1[ByteVector,ByteVector] = {
    @tailrec
    def collect(deflater: Deflater,
                buf: Array[Byte],
                flush: Int,
                acc: Vector[ByteVector] = Vector.empty): Vector[ByteVector] =
      deflater.deflate(buf, 0, buf.length, flush) match {
        case 0 => acc
        case n => collect(deflater, buf, flush, acc :+ ByteVector.view(buf.take(n)))
      }

    def go(deflater: Deflater, buf: Array[Byte]): Process1[ByteVector,ByteVector] =
      receive1 { bytes =>
        deflater.setInput(bytes.toArray)
        val chunks = collect(deflater, buf, Deflater.NO_FLUSH)
        emitAll(chunks) fby go(deflater, buf)
      }

    def flush(deflater: Deflater, buf: Array[Byte]): Process0[ByteVector] =
      emitAll(collect(deflater, buf, Deflater.FULL_FLUSH))

    suspend {
      val deflater = new Deflater(level, nowrap)
      val buf = Array.ofDim[Byte](bufferSize)
      drainLeading(go(deflater, buf) onComplete flush(deflater, buf))
    }
  }

  /**
   * Returns a `Process1` that inflates (decompresses) its input elements using
   * a `java.util.zip.Inflater` with the parameter `nowrap`.
   * @param nowrap if true then support GZIP compatible compression
   * @param bufferSize size of the internal buffer that is used by the
   *                   decompressor. Default size is 32 KB.
   * $MutableImpl
   */
  def inflate(nowrap: Boolean = false,
              bufferSize: Int = 1024 * 32): Process1[ByteVector,ByteVector] = {
    @tailrec
    def collect(inflater: Inflater,
                buf: Array[Byte],
                acc: Vector[ByteVector]): Vector[ByteVector] =
      inflater.inflate(buf) match {
        case 0 => acc
        case n => collect(inflater, buf, acc :+ ByteVector.view(buf.take(n)))
      }

    def go(inflater: Inflater, buf: Array[Byte]): Process1[ByteVector,ByteVector] =
      receive1 { bytes =>
        inflater.setInput(bytes.toArray)
        val chunks = collect(inflater, buf, Vector.empty)
        emitAll(chunks) fby go(inflater, buf)
      }

    suspend {
      val inflater = new Inflater(nowrap)
      val buf = Array.ofDim[Byte](bufferSize)
      go(inflater, buf)
    }
  }
}
