package scalaz.stream

import java.util.zip.{Deflater, Inflater}
import scala.annotation.tailrec
import scodec.bits.ByteVector

import Process._
import process1._

/*
trait gzip {

  def gunzip(bufferSize: Int = 1024 * 32): Channel[Task, ByteVector, ByteVector] = {
    def skipUntil[F[_], A](f: Process[F, A])(p: A => Boolean): Process[F, Int] = for {
      y <- f
      a <- if (p(y)) emit(1) else skipUntil(f)(p)
    } yield a + 1

    def skipString = skipUntil(await1[Byte])(_ == 0)
    def unchunk = await1[Array[Byte]].flatMap(x => emitAll(x)).repeat

    def readShort: Process1[Byte, Int] = for {
      b1 <- await1[Byte]
      b2 <- await1[Byte]
    } yield (b1 + (b2 << 8)) & 0xffff

    def readHeader: Process1[Byte, Int] = for {
      magic <- readShort
      compression <- if (magic != 0x8b1f)
                       fail(sys.error(String.format("Bad magic number: %02x", Int.box(magic))))
                     else await1[Byte]
      flags <- if (compression != 8)
                 fail(sys.error("Unsupported compression method."))
               else await1[Byte]
      extra <- drop(6) |> (if ((flags & 4) == 4) readShort else emit(0))
      name <- drop(extra) |> (if ((flags & 8) == 8) skipString else emit(0))
      comment <- if ((flags & 16) == 16) skipString else emit(0)
      crc <- if ((flags & 2) == 2) readShort map (_ => 2) else emit(0)
    } yield 10 + extra + name + comment + crc

    def skipHeader(skipper: Process1[Byte, Int],
                   c: Channel[Task, ByteVector, ByteVector]): Channel[Task, ByteVector, ByteVector] =
      c match {
        case Emit(Seq(), t) => t
        case Emit(h, t) =>
          Emit(((bs: ByteVector) => {
            val fed = feed(bs.toSeq)(skipper)
            fed match {
              case Emit(ns, _) =>
                h.head(bs.drop(ns.sum))
              case Await(_, _, _, _) => Task.now(ByteVector.empty)
              case Halt(e) => h.head(bs)
            }
          }) +: h.tail, skipHeader(skipper, t))
        case Await(snd, rec, fo, cu) =>
          Await(snd, (x:Any) => skipHeader(skipper, rec(x)), fo, cu)
        case x => x
      }

    skipHeader(readHeader, inflate(bufferSize, true))
  }
}
*/

object compress {
  def gzip: Process1[ByteVector,ByteVector] = {
    // http://www.gzip.org/zlib/rfc-gzip.html
    val header = ByteVector(
      0x1f, // ID1
      0x8b, // ID2
      0x08, // CM
      0x00, // FLG
      0x00, 0x00, 0x00, 0x00, // MTIME
      0x02, // XFL
      0xff  // OS
    )
    await1[ByteVector].flatMap { bytes =>
      emit(header) fby feed1(bytes)(deflate(level = 9, nowrap = true))
    }
  }

  def gunzip: Process1[ByteVector,ByteVector] = id

  /**
   * Returns a `Process1` that deflates (compresses) its input elements using
   * a `java.util.zip.Deflater` with the parameters `level` and `nowrap`.
   * @param level the compression level (0-9)
   * @param nowrap if true then use GZIP compatible compression
   * @param bufferSize size of the internal buffer that is used by the
   *                   compressor. Default size is 32 KB.
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
      await1[ByteVector].flatMap { bytes =>
        deflater.setInput(bytes.toArray)
        val chunks = collect(deflater, buf, Deflater.NO_FLUSH)
        emitSeq(chunks) fby go(deflater, buf) orElse {
          emitSeqLazy(collect(deflater, buf, Deflater.FULL_FLUSH))
        }
      }

    suspend1 {
      val deflater = new Deflater(level, nowrap)
      val buf = Array.ofDim[Byte](bufferSize)
      go(deflater, buf)
    }
  }

  /**
   * Returns a `Process1` that inflates (decompresses) its input elements using
   * a `java.util.zip.Inflater` with the parameter `nowrap`.
   * @param nowrap if true then support GZIP compatible compression
   * @param bufferSize size of the internal buffer that is used by the
   *                   decompressor. Default size is 32 KB.
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
      await1[ByteVector].flatMap { bytes =>
        inflater.setInput(bytes.toArray)
        val chunks = collect(inflater, buf, Vector.empty)
        emitSeq(chunks) fby go(inflater, buf)
      }

    suspend1 {
      val inflater = new Inflater(nowrap)
      val buf = Array.ofDim[Byte](bufferSize)
      go(inflater, buf)
    }
  }
}
