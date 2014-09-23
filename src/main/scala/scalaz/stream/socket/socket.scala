package scalaz.stream

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, AsynchronousServerSocketChannel, CompletionHandler}
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadFactory
import scala.concurrent.duration._
import scalaz.concurrent.Task
import scalaz.\/._
import scalaz.~>
import scodec.bits.ByteVector

object socket {

  trait Socket[+A] {
    private[stream] def run(channel: AsynchronousSocketChannel): Task[A]
  }

  private[stream] def ask: Process[Socket,AsynchronousSocketChannel] =
    Process.eval { new Socket[AsynchronousSocketChannel] {
      def run(channel: AsynchronousSocketChannel) = Task.now(channel)
    }}

  def lift[A](t: Task[A]): Socket[A] =
    new Socket[A] { def run(channel: AsynchronousSocketChannel) = t }

  /**
   * Read exactly `numBytes` from the peer. If `timeout` is provided
   * and no data arrives within the specified duration, the returned
   * `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
   */
  def read(numBytes: Int,
           timeout: Option[Duration] = None,
           allowPeerClosed: Boolean = false): Process[Socket,ByteVector] =
    available(numBytes, timeout, allowPeerClosed) flatMap { bs =>
      if (bs.size == numBytes) Process.emit(bs)
      else Process.emit(bs) ++ read(numBytes, timeout, allowPeerClosed)
    }

  /**
   * Read up to `maxBytes` from the peer. If `timeout` is provided
   * and the operation does not complete in the specified duration,
   * the returned `Process` fails with a [[java.nio.channels.InterruptedByTimeoutException]].
   */
  def available(
        maxBytes: Int,
        timeout: Option[Duration] = None,
        allowPeerClosed: Boolean = false): Process[Socket,ByteVector] =
    Process.eval { new Socket[ByteVector] {
      def run(channel: AsynchronousSocketChannel) = Task.async { cb =>
        val arr = new Array[Byte](maxBytes)
        val buf = ByteBuffer.wrap(arr)
        val handler = new CompletionHandler[Integer, Void] {
          def completed(result: Integer, attachment: Void): Unit = {
            buf.flip()
            val bs = ByteVector(buf)
            if (result < 0) cb(left(Cause.Terminated(Cause.End)))
            else cb(right(bs.take(result.intValue)))
          }
          // todo - if allowPeerClosed, convert certain types of exceptions to
          // regular termination
          def failed(exc: Throwable, attachment: Void): Unit = cb(left(exc))
        }
        timeout match {
          case None => channel.read(buf, null, handler)
          case Some(d) => channel.read(buf, d.length, d.unit, null, handler)
        }
      }
    }}

  def write(bytes: ByteVector,
            timeout: Option[Duration] = None,
            allowPeerClosed: Boolean = false): Process[Socket,Unit] =
    Process.eval { new Socket[Unit] {
      def run(channel: AsynchronousSocketChannel) =
        writeOne(channel, bytes, timeout, allowPeerClosed)
    }}

  private def writeOne(ch: AsynchronousSocketChannel, a: ByteVector,
               timeout: Option[Duration] = None,
               allowPeerClosed: Boolean = false): Task[Unit] = {
    Task.async[Int] { cb =>
      val handler = new CompletionHandler[Integer, Void] {
        def completed(result: Integer, attachment: Void): Unit = cb(right(result))
        def failed(exc: Throwable, attachment: Void): Unit = cb(left(exc))
      }
      timeout match {
        case None => ch.write(a.toByteBuffer, null, handler)
        case Some(d) => ch.write(a.toByteBuffer, d.length, d.unit, null, handler)
      }
    }.flatMap { w =>
      if (w == a.length) Task.now(())
      else writeOne(ch, a.drop(w), timeout, allowPeerClosed)
    }
  }

  /**
   * Channel which interprets a stream of client connections.
   *
   * Process that connects to remote server (TCP) and provides one exchange representing connection to that server
   * @param to              Address of remote server
   * @param reuseAddress    whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
   * @param sndBufferSize   size of send buffer  (@see [[java.net.StandardSocketOptions.SO_SNDBUF]])
   * @param rcvBufferSize   size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
   * @param keepAlive       whether keep-alive on tcp is used (@see [[java.net.StandardSocketOptions.SO_KEEPALIVE]])
   * @param noDelay         whether tcp no-delay flag is set  (@see [[java.net.StandardSocketOptions.TCP_NODELAY]])
   * @return
   */
  def client(to: InetSocketAddress,
             reuseAddress: Boolean = true,
             sndBufferSize: Int = 256 * 1024,
             rcvBufferSize: Int = 256 * 1024,
             keepAlive: Boolean = false,
             noDelay: Boolean = false)(
             implicit AG: AsynchronousChannelGroup):
             Process[Task, Process[Socket,ByteVector] => Process[Task,ByteVector]] = {

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
          def completed(result: Void, attachment: Void): Unit = cb(right(ch))
          def failed(rsn: Throwable, attachment: Void): Unit = cb(left(rsn))
        })
      }

    val runner = (output: Process[Socket,ByteVector]) =>
      Process.await(setup flatMap connect)(ch =>
        bindTo(ch)(output).onComplete(Process.eval_(Task.delay(ch.close))))

    Process.emit(runner).repeat
  }

  /**
   * Process that binds to supplied address and provides accepted exchanges
   * representing incoming connections (TCP) with remote clients

   * When this process terminates all the incoming exchanges will terminate as well
   * @param bind               address to which this process has to be bound
   * @param reuseAddress       whether address has to be reused (@see [[java.net.StandardSocketOptions.SO_REUSEADDR]])
   * @param rcvBufferSize      size of receive buffer  (@see [[java.net.StandardSocketOptions.SO_RCVBUF]])
   * @return
   */
  def server(bind: InetSocketAddress,
             reuseAddress: Boolean = true,
             rcvBufferSize: Int = 256 * 1024)(
             implicit AG: AsynchronousChannelGroup):
             Sink[Task, Process[Socket,ByteVector]] = {

    def setup: Task[AsynchronousServerSocketChannel] =
      Task.delay {
        val ch = AsynchronousChannelProvider.provider().openAsynchronousServerSocketChannel(AG)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, rcvBufferSize)
        ch.bind(bind)
        ch
      }

    def accept(sch: AsynchronousServerSocketChannel): Task[AsynchronousSocketChannel] =
      Task.async[AsynchronousSocketChannel] { cb =>
        sch.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
          def completed(result: AsynchronousSocketChannel, attachment: Void): Unit = cb(right(result))
          def failed(rsn: Throwable, attachment: Void): Unit = cb(left(rsn))
        })
      }

    Process.eval(setup) flatMap { sch =>
      Process.constant { (output: Process[Socket,ByteVector]) =>
        // note that server does even accept a connection until
        // it has a handler for that connection!
        accept(sch) flatMap { conn => runSocket(conn)(output) }
      }.onComplete (Process.eval_(Task.delay(sch.close)))
    }
  }

  private def bindTo[A](c: AsynchronousSocketChannel)(p: Process[Socket,A]): Process[Task,A] =
    p.translate(new (Socket ~> Task) { def apply[A](s: Socket[A]) = s.run(c) })

  private def runSocket(c: AsynchronousSocketChannel)(
                        p: Process[Socket,ByteVector]): Task[Unit] =
    bindTo(c)(p)
     .evalMap(bytes => writeOne(c, bytes))
     .onComplete(Process.eval_(Task.delay(c.close)))
     .run

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
