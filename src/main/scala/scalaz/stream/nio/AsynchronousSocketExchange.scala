package scalaz.stream.nio

import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.{CompletionHandler, AsynchronousSocketChannel}
import scalaz.concurrent.Task
import scalaz.stream.{Bytes, Sink, Process, Exchange}
import scalaz.{\/-, -\/}
import scalaz.stream.Process.End


trait AsynchronousSocketExchange extends Exchange[Bytes, Bytes] {

  val ch: AsynchronousSocketChannel

  private lazy val readBuffSize = ch.getOption[java.lang.Integer](StandardSocketOptions.SO_RCVBUF)
  private lazy val a = Array.ofDim[Byte](readBuffSize)
  private lazy val buff = ByteBuffer.wrap(a)

  private[nio] def readOne: Task[Bytes] = {
    Task.async { cb =>
      buff.clear()
      ch.read(buff, null, new CompletionHandler[Integer, Void] {
        def completed(result: Integer, attachment: Void): Unit = {
          buff.flip()
          val bs = Bytes.of(buff)
          if (result < 0 ) cb(-\/(End))
          else cb(\/-(bs))
        }

        def failed(exc: Throwable, attachment: Void): Unit =  cb(-\/(exc))
      })
    }
  }

  private[nio]  def writeOne(a: Bytes): Task[Unit] = {
    Task.async[Int] { cb =>
      ch.write(a.asByteBuffer, null, new CompletionHandler[Integer, Void] {
        def completed(result: Integer, attachment: Void): Unit =   cb(\/-(result))
        def failed(exc: Throwable, attachment: Void): Unit = cb(-\/(exc))
      })
    }.flatMap { w =>
      if (w == a.length)  Task.now(())
      else writeOne(a.drop(w))
    }
  }


  def read: Process[Task, Bytes] = Process.repeatEval(readOne)
  def write: Sink[Task, Bytes] = Process.constant( (a:Bytes) => writeOne(a))

}
