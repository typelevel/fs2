package scalaz.stream.nio

import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.{CompletionHandler, AsynchronousSocketChannel}
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.{Sink, Process, Exchange}
import scalaz.{\/-, -\/}


trait AsynchronousSocketExchange extends Exchange[Array[Byte], Array[Byte]] {

  val ch: AsynchronousSocketChannel



  private[nio] def readOne: Task[Array[Byte]] = {
    val readBuffSize = ch.getOption[java.lang.Integer](StandardSocketOptions.SO_RCVBUF)
    println("buffSize",readBuffSize)
    val buff = Array.ofDim[Byte](readBuffSize)
    Task.async {
      cb =>
        ch.read(ByteBuffer.wrap(buff), null, new CompletionHandler[Integer, Void] {
          def completed(result: Integer, attachment: Void): Unit = {
            println("Just read", result, ch)
            Strategy.DefaultStrategy(cb(\/-(buff.take(result))))
          }
          def failed(exc: Throwable, attachment: Void): Unit = cb(-\/(exc))
        })
    }
  }

  private[nio]  def writeOne(a: Array[Byte]): Task[Unit] = {
    def go(offset: Int): Task[Int] = {

      Task.async[Int] {
        cb =>
          println("About to write",offset,a.size, ch )
          ch.write(ByteBuffer.wrap(a, offset, a.length - offset), null, new CompletionHandler[Integer, Void] {
            def completed(result: Integer, attachment: Void): Unit = {
              println("wrote",offset, result, a.size, ch)
              cb(\/-(result))
            }
            def failed(exc: Throwable, attachment: Void): Unit = cb(-\/(exc))
          })
      }.flatMap {
        written =>
          if (offset + written >= a.length)  Task.now(a.length)
          else {
            println("waiting for write to be ready", a.size - offset, offset, written, ch)
            go(offset + written)
          }

      }
    }
    go(0).map(_=>())
  }

  def read: Process[Task, Array[Byte]] = Process.repeatEval(readOne)
  def write: Sink[Task, Array[Byte]] = Process.constant( (a:Array[Byte]) => writeOne(a))

 // private[stream] def send2: Sink[Task, Array[Byte]] = Task.now( (a:Array[Byte]) => writeOne(a))
}
