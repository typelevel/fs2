package scalaz.stream

import scalaz.stream.Process._
import scalaz.concurrent.Task
import scalaz.stream.processes._
import java.util.zip.{Inflater, Deflater}
import scala.annotation.tailrec
import scala.Some


trait gzip {

  /**
   * Channel that deflates (compress) the array of Bytes. It may emit empty arrays, 
   * if the Deflater is waiting for more data to produce deflated chunk. 
   * Chanel flushes deflated data as the last step when the 
   * @param bufferSize buffer to use when flushing data out of Deflater. Defaults to 32k
   * @return
   */
  def deflate(bufferSize: Int = 1024 * 32) = {

    val deflater = new Deflater

    lazy val emptyArray = Array[Byte]()

    @tailrec
    def collectFromDeflater(sofar: Array[Byte]): Array[Byte] = {
      val buff = new Array[Byte](bufferSize)

      deflater.deflate(buff) match {
        case deflated if deflated < bufferSize => sofar ++ buff.take(deflated)
        case _ => collectFromDeflater(sofar ++ buff)
      }

    }

    flushChannel[Deflater, Bytes, Bytes](Task.now(deflater))(
      _ => Task.delay {
        deflater.finish()
        val l = collectFromDeflater(emptyArray) 
        if (l.length > 0) Some(Bytes(l)) else None
      }
    )(_ => Task.delay(deflater.end()))({
      _ =>
        Task.delay {
          in => 
            deflater.setInput(in.bytes, 0, in.n)
            if (deflater.needsInput()) {
              Task.now(Bytes.empty)
            } else {
              Task.now(Bytes(collectFromDeflater(emptyArray)))
            }
        }
    })
  }


  /**
   * Channel that inflates (decompress) the array of Bytes. 
   * May emit empty Bytes if the Inflater is not ready to produce inflated data. 
   * Last emit will always contain all data inflated.
   * @param bufferSize buffer to use when flushing data out of Inflater. Defaults to 32k
   * @return
   */
  def inflate(bufferSize: Int = 1024 * 32): Channel[Task, Bytes, Bytes] = {

    resource(Task.now(new Inflater))(i => Task.delay(i.end())) {
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
