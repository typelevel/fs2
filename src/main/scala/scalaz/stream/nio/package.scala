package scalaz.stream

import Cause._
import java.net.{StandardSocketOptions, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels._
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import scalaz.-\/
import scalaz.\/-
import scalaz.concurrent.Task
import scalaz.stream.Process._
import scodec.bits.ByteVector

package object nio {


  /**
   * Process that binds to supplied address and provides accepted exchanges
   * representing incoming connections (TCP) with remote clients
   * When this process terminates all the incoming exchanges will terminate as well
   * @param bind               address to which this process has to be bound
   * @param reuseAddress       whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
   * @param rcvBufferSize      size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
   * @return
   */
  def server(bind: InetSocketAddress
    , reuseAddress: Boolean = true
    , rcvBufferSize: Int = 256 * 1024
    )(implicit AG: AsynchronousChannelGroup)
  : Process[Task, Process[Task, Exchange[ByteVector, ByteVector]]] = {

    def setup: Task[AsynchronousServerSocketChannel] =
      Task.delay {
        val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, rcvBufferSize)
        ch.bind(bind)
        ch
      }

    def awaitClientConnection(sch: AsynchronousServerSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
          def completed(result: AsynchronousSocketChannel, attachment: Void): Unit = cb(\/-(result))
          def failed(rsn: Throwable, attachment: Void): Unit = cb(-\/(rsn))
        })
      }

    def release(ch: AsynchronousChannel): Process[Task, Nothing] =
      eval_(Task.delay(ch.close()))

    await(setup) { sch =>
      repeatEval(awaitClientConnection(sch)) onHalt { _.asHalt } map { cchn =>
        Process(nioExchange(cchn)) onComplete release(cchn)
      } onComplete release(sch)
    }
  }


  /**
   * Process that connects to remote server (TCP) and provides one exchange representing connection to that server
   * @param to              Address of remote server
   * @param reuseAddress    whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
   * @param sndBufferSize   size of send buffer  (@see [[java.net.StandardSocketOptions.SO_SNDBUF]])
   * @param rcvBufferSize   size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
   * @param keepAlive       whether keep-alive on tcp is used (@see [[java.net.StandardSocketOptions.SO_KEEPALIVE]])
   * @param noDelay         whether tcp no-delay flag is set  (@see [[java.net.StandardSocketOptions.TCP_NODELAY]])
   * @return
   */
  def connect(to: InetSocketAddress
    , reuseAddress: Boolean = true
    , sndBufferSize: Int = 256 * 1024
    , rcvBufferSize: Int = 256 * 1024
    , keepAlive: Boolean = false
    , noDelay: Boolean = false
    )(implicit AG: AsynchronousChannelGroup)
  : Process[Task, Exchange[ByteVector, ByteVector]] = {

    def setup: Task[AsynchronousSocketChannel] = Task.delay {
      val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sndBufferSize)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, rcvBufferSize)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
      ch
    }

    def connect(ch: AsynchronousSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        ch.connect(to, null, new CompletionHandler[Void, Void] {
          def completed(result: Void, attachment: Void): Unit = cb(\/-(ch))
          def failed(rsn: Throwable, attachment: Void): Unit = cb(-\/(rsn))
        })
      }

    def release(ch: AsynchronousSocketChannel): Process[Task, Nothing] = {
      eval_(Task.delay(ch.close()))
    }

    await(setup flatMap connect) { ch =>
      Process(nioExchange(ch)) onComplete release(ch)
    }

  }


  lazy val DefaultAsynchronousChannelGroup = {
    val idx = new AtomicInteger(0)
    AsynchronousChannelProvider.provider().openAsynchronousChannelGroup(
      Runtime.getRuntime.availableProcessors() * 2 max 2
      , new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r, s"scalaz-stream-nio-${idx.incrementAndGet() }")
          t.setDaemon(true)
          t
        }
      }
    )
  }


  private def nioExchange(ch: AsynchronousSocketChannel, readBufferSize: Int = 0): Exchange[ByteVector, ByteVector] = {

    lazy val bufSz: Int =
      if (readBufferSize <= 0) ch.getOption[java.lang.Integer](StandardSocketOptions.SO_RCVBUF)
      else readBufferSize

    lazy val a = Array.ofDim[Byte](bufSz)
    lazy val buff = ByteBuffer.wrap(a)

    def readOne: Task[ByteVector] = {
      Task.async { cb =>
        buff.clear()
        ch.read(buff, null, new CompletionHandler[Integer, Void] {
          def completed(result: Integer, attachment: Void): Unit = {
            buff.flip()
            val bs = ByteVector(buff)
            if (result < 0) cb(-\/(Terminated(End)))
            else cb(\/-(bs))
          }

          def failed(exc: Throwable, attachment: Void): Unit = cb(-\/(exc))
        })
      }
    }

    def writeOne(a: ByteVector): Task[Unit] = {
      Task.async[Int] { cb =>
        ch.write(a.toByteBuffer, null, new CompletionHandler[Integer, Void] {
          def completed(result: Integer, attachment: Void): Unit = cb(\/-(result))
          def failed(exc: Throwable, attachment: Void): Unit = cb(-\/(exc))
        })
      }.flatMap { w =>
        if (w == a.length) Task.now(())
        else writeOne(a.drop(w))
      }
    }


    def read: Process[Task, ByteVector] = Process.repeatEval(readOne).onHalt(_.asHalt)
    def write: Sink[Task, ByteVector] = Process.constant((a: ByteVector) => writeOne(a))

    Exchange(read, write)
  }


}
