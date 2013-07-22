package scalaz.stream

import scalaz.stream.Process._
import scalaz.concurrent.Task
import scalaz.stream.processes._
import java.util.zip.{Inflater, Deflater}
import scala.annotation.tailrec

trait gzip {

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
  def inflate(bufferSize: Int = 1024 * 32): Channel[Task, Bytes, Bytes] = {

    resource(Task.delay(new Inflater))(i => Task.delay(i.end())) {
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
