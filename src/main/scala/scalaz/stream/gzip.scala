package scalaz.stream

import scalaz._
import scalaz.stream.Process._
import scalaz.concurrent.Task
import scalaz.syntax.monad._
import scalaz.stream.processes._
import java.util.zip.{Inflater, Deflater}
import scala.annotation.tailrec

trait gzip {

  def gunzip(bufferSize: Int = 1024 * 32): Channel[Task, Array[Byte], Array[Byte]] = {
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
                   c: Channel[Task, Array[Byte], Array[Byte]]): Channel[Task, Array[Byte], Array[Byte]] =
      c match {
        case Emit(Seq(), t) => t
        case Emit(h, t) =>
          Emit(((bs: Array[Byte]) => {
            val fed = feed(bs)(skipper)
            fed match {
              case Emit(ns, _) =>
                h.head(bs.drop(ns.sum))
              case Await(_, _, _, _) => Task.now(Array[Byte]())
              case Halt(e) => h.head(bs)
            }
          }) +: h.tail, skipHeader(skipper, t))
        case Await(snd, rec, fo, cu) =>
          Await(snd, (x:Any) => skipHeader(skipper, rec(x)), fo, cu)
        case x => x
      }

    skipHeader(readHeader, inflate(bufferSize, true))
  }

  /**
   * Channel that deflates (compresses) its input `Array[Byte]`, using
   * a `java.util.zip.Deflater`. May emit empty arrays if the compressor
   * is waiting for more data to produce a chunk. The returned `Channel`
   * flushes any buffered compressed data when it encounters a `None`.
   *
   * @param bufferSize buffer to use when flushing data out of Deflater. Defaults to 32k
   */
  def deflate(bufferSize: Int = 1024 * 32): Channel[Task, Option[Array[Byte]], Array[Byte]] = {

    lazy val emptyArray = Array[Byte]() // ok to share this, since it cannot be modified

    @tailrec
    def collectFromDeflater(deflater: Deflater, sofar: Array[Byte]): Array[Byte] = {
      val buff = new Array[Byte](bufferSize)

      deflater.deflate(buff) match {
        case deflated if deflated < bufferSize => sofar ++ buff.take(deflated)
        case _ => collectFromDeflater(deflater, sofar ++ buff)
      }
    }

    bufferedChannel[Deflater, Array[Byte], Array[Byte]](Task.delay { new Deflater }) {
      deflater => Task.delay {
        deflater.finish()
        collectFromDeflater(deflater, emptyArray)
      }
    } (deflater => Task.delay(deflater.end())) {
      deflater => Task.now {
        in => Task.delay {
          deflater.setInput(in, 0, in.length)
          if (deflater.needsInput())
            emptyArray
          else
            collectFromDeflater(deflater, emptyArray)
        }
      }
    }
  }

  /**
   * Channel that inflates (decompresses) the input bytes. May emit empty
   * `Array[Byte]` if decompressor is not ready to produce data. Last emit will always
   * contain all data inflated.
   * @param bufferSize buffer to use when flushing data out of Inflater. Defaults to 32k
   * @return
   */
  def inflate(bufferSize: Int = 1024 * 32, gzip: Boolean = false): Channel[Task, Array[Byte], Array[Byte]] = {

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

        Task.now {
          in => Task.delay {
            if (inflater.finished) throw End
            else {
              inflater.setInput(in, 0, in.length)
              if (inflater.needsInput())
                Array[Byte]()
              else
                collectFromInflater(Array[Byte]())
            }
          }
        }
      }
    }
  }
}
