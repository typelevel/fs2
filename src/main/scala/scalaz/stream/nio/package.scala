package scalaz.stream

import java.net.{StandardSocketOptions, InetSocketAddress}
import java.nio.channels._
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import scalaz.-\/
import scalaz.\/-
import scalaz.concurrent.Task
import scalaz.stream.Process._
import java.lang.Thread.UncaughtExceptionHandler


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
    )(implicit AG: AsynchronousChannelGroup = DefaultAsynchronousChannelGroup)
  : Process[Task, Process[Task, Exchange[Bytes, Bytes]]] = {

    def setup(ch: AsynchronousServerSocketChannel): AsynchronousServerSocketChannel = {
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, rcvBufferSize)
      ch.bind(bind)
      ch
    }

    def release(ch: AsynchronousChannel): Process[Task, Nothing] =
      eval_(Task.delay(ch.close()))


    await(
      Task.delay(setup(AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)))
    )(sch =>
      repeatEval(Task.async[AsynchronousSocketChannel] {
        cb => sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
          def completed(result: AsynchronousSocketChannel, attachment: Void): Unit = cb(\/-(result))
          def failed(exc: Throwable, attachment: Void): Unit = cb(-\/(exc))
        })
      }).map {
        ch1 => eval(Task.now(new AsynchronousSocketExchange {
          val ch: AsynchronousSocketChannel = ch1
        })) onComplete release(ch1)

      } onComplete release(sch))
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
    )(implicit AG: AsynchronousChannelGroup = DefaultAsynchronousChannelGroup)
  : Process[Task, Exchange[Bytes, Bytes]] = {

    def setup(ch: AsynchronousSocketChannel): AsynchronousSocketChannel = {
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sndBufferSize)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, rcvBufferSize)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
      ch
    }

    def release(ch: AsynchronousSocketChannel): Process[Task, Nothing] = {
      suspend(eval(Task.now(ch.close()))).drain
    }

    await(
      Task.delay(setup(AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(AG)))
    )(ch1 => {
      await(Task.async[AsynchronousSocketChannel] {
        cb => ch1.connect(to, null, new CompletionHandler[Void, Void] {
          def completed(result: Void, attachment: Void): Unit = cb(\/-(ch1))
          def failed(exc: Throwable, attachment: Void): Unit = cb(-\/(exc))
        })
      })(_ => eval(Task.now(
        new AsynchronousSocketExchange {
          val ch: AsynchronousSocketChannel = ch1
        }))
          , release(ch1)
          , release(ch1)
        )
    }
        , halt
        , halt
      )

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

}
