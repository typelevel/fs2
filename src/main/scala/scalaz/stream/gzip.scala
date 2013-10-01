package scalaz.stream

import scalaz._
import scalaz.stream.Process._
import scalaz.concurrent.Task
import scalaz.syntax.monad._
import scalaz.stream.processes._
import java.util.zip.{Inflater, Deflater}
import scala.annotation.tailrec

trait gzip {

  def gunzip(bufferSize: Int = 1024 * 32): Channel[Task, Bytes, Bytes] = {
    def skipUntil[F[_], A](f: Process[F, A])(p: A => Boolean): Process[F, Int] = for {
      y <- f
      a <- if (p(y)) emit(1) else skipUntil(f)(p)
    } yield a + 1

    def skipString = skipUntil(await1[Byte])(_ == 0)
    def unchunk = await1[Bytes].flatMap(bs => emitAll(bs.toArray)).repeat

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

    def skipHeader(skipped: Int,
                   skipper: Process1[Byte, Int],
                   c: Channel[Task, Bytes, Bytes]): Channel[Task, Bytes, Bytes] =
      c match {
        case Emit(Seq(), t) => t
        case Emit(h, t) =>
          Emit(((bs: Bytes) => {
            val fed = feed(bs.toArray)(skipper)
            fed match {
              case Emit(ns, _) =>
                h.head(Bytes(bs.toArray.drop(ns.sum - skipped)))
              case Await(_, _, _, _) => Task.now(Bytes(Array()))
              case Halt(e) => h.head(bs)
            }
          }) +: h.tail, skipHeader(skipped, skipper, t))
        case Await(snd, rec, fo, cu) =>
          Await(snd, (x:Any) => skipHeader(skipped, skipper, rec(x)), fo, cu)
        case x => x
      }

    skipHeader(0, readHeader, inflate(bufferSize, true))
  }

  /**
   * Channel that deflates (compresses) its input Bytes, using
   * a `java.util.zip.Deflater`. May emit empty arrays if the compressor
   * is waiting for more data to produce a chunk. The returned `Channel`
   * flushes any buffered compressed data when it encounters a `None`.
   *
   * @param bufferSize buffer to use when flushing data out of Deflater. Defaults to 32k
   */
  def deflate(bufferSize: Int = 1024 * 32): Channel[Task, Option[Bytes], Bytes] = {

    lazy val emptyArray = Array[Byte]() // ok to share this, since it cannot be modified

    @tailrec
    def collectFromDeflater(deflater: Deflater, sofar: Array[Byte]): Array[Byte] = {
      val buff = new Array[Byte](bufferSize)

      deflater.deflate(buff) match {
        case deflated if deflated < bufferSize => sofar ++ buff.take(deflated)
        case _ => collectFromDeflater(deflater, sofar ++ buff)
      }
    }

    bufferedChannel[Deflater, Bytes, Bytes](Task.delay { new Deflater }) {
      deflater => Task.delay {
        deflater.finish()
        Bytes(collectFromDeflater(deflater, emptyArray))
      }
    } (deflater => Task.delay(deflater.end())) {
      deflater => Task.now {
        in =>
          deflater.setInput(in.bytes, 0, in.n)
          if (deflater.needsInput())
            Task.now(Bytes.empty)
          else
            Task.now(Bytes(collectFromDeflater(deflater, emptyArray)))
        }
    }
  }

  /**
   * Channel that inflates (decompresses) the input Bytes. May emit empty
   * Bytes if decompressor is not ready to produce data. Last emit will always
   * contain all data inflated.
   * @param bufferSize buffer to use when flushing data out of Inflater. Defaults to 32k
   * @return
   */
  def inflate(bufferSize: Int = 1024 * 32, gzip: Boolean = false): Channel[Task, Bytes, Bytes] = {

    resource(Task.delay(new Inflater(gzip)))(i => Task.delay(i.end())) {
      inflater => {
        @tailrec
        def collectFromInflater(sofar: Array[Byte]): Array[Byte] = {
          val buff = new Array[Byte](bufferSize)
          inflater.inflate(buff) match {
            case inflated if inflated < bufferSize => sofar ++ buff.take(inflated)
            case _ => collectFromInflater(sofar ++ buff)
          }
        }

        Task.delay {
          in =>
            if (inflater.finished) {
              throw End
            } else {
              inflater.setInput(in.bytes, 0, in.n)
              if (inflater.needsInput()) {
                Task.now(Bytes.empty)
              } else {
                Task.now(Bytes(collectFromInflater(Array[Byte]())))
              }
            }
        }
      }
    }
  }

}
